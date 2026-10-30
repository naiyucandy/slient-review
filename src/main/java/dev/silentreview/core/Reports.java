package dev.silentreview.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Reports {
    public enum Decision { UNREVIEWED, CONFIRMED, ACCEPTED, CLOSED }
    // The UI-only branch wrote PENDING. Normalize that alias only; existing
    // ACCEPTED/CLOSED decisions and all audit events retain their exact values.
    private static final Gson RESOLUTION_JSON=new GsonBuilder().registerTypeAdapter(Decision.class,
        (JsonDeserializer<Decision>)(json,type,context)->{
            String value=json.getAsString();
            if("PENDING".equals(value))return Decision.UNREVIEWED;
            try{return Decision.valueOf(value);}
            catch(IllegalArgumentException unknown){throw new JsonParseException("未知报告处理状态："+value,unknown);}
        }).create();
    public record AuditEvent(Decision decision,String at,String actor,String note) {}
    public record Resolution(String reportId,Decision decision,String updatedAt,String actor,String note,List<AuditEvent> history) {
        public Resolution { history=history==null?List.of():List.copyOf(history); }
    }
    public record FindingAuditEvent(boolean confirmed,String at,String actor,String note) {}
    public record FindingResolution(String reportId,int findingIndex,String findingKey,boolean confirmed,
                                    String updatedAt,String actor,String note,List<FindingAuditEvent> history) {
        public FindingResolution { history=history==null?List.of():List.copyOf(history); }
    }
    private record ResolutionFile(int schemaVersion,Map<String,Resolution> resolutions,
                                  Map<String,Map<Integer,FindingResolution>> findingResolutions) {}
    private static final String RESOLUTIONS="resolutions.json";
    private static final java.util.regex.Pattern ID=java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    // Settings actions and report writes can arrive through different service instances.
    private static final Map<Path,Object> LOCKS=new ConcurrentHashMap<>();
    private final Path directory;
    private final Object lock;
    public Reports(Path directory) { this.directory=directory.toAbsolutePath().normalize();lock=LOCKS.computeIfAbsent(this.directory,p->new Object()); }
    public void save(Review.Report report) throws IOException {
        Objects.requireNonNull(report,"report");requireId(report.id());
        synchronized(lock) {
            ResolutionFile state=readStateLocked();
            writeAtomically(directory.resolve(report.id()+".json"),Review.JSON.toJson(report));
            List<Review.Report> reports=loadLocked();
            if(reports.size()>100) {
                for(int i=100;i<reports.size();i++)Files.deleteIfExists(directory.resolve(reports.get(i).id()+".json"));
                reports=reports.subList(0,100);
            }
            Set<String> retained=new HashSet<>();reports.forEach(item->retained.add(item.id()));
            boolean changed=state.resolutions().keySet().retainAll(retained);
            changed=state.findingResolutions().keySet().retainAll(retained)||changed;
            Map<Integer,FindingResolution> findings=state.findingResolutions().get(report.id());
            if(findings!=null) {
                boolean hadFindingStates=!findings.isEmpty();
                boolean invalidated=findings.entrySet().removeIf(entry->findingResolution(report,entry.getKey(),findings)==null);
                if(findings.isEmpty())state.findingResolutions().remove(report.id());
                changed=changed||invalidated;
                Resolution old=state.resolutions().get(report.id());
                int findingCount=report.result().findings()==null?0:report.result().findings().size();
                if(hadFindingStates&&(invalidated||confirmedFindingCount(report,findings)!=findingCount)
                    &&old!=null&&old.decision()==Decision.CONFIRMED) {
                    appendResolution(state,report.id(),Decision.UNREVIEWED,Instant.now().toString(),"system",
                        invalidated?"报告问题内容发生变化，原有逐项确认不再适用，任务恢复待处理。"
                            :"报告问题清单发生变化，逐项确认未全部完成，任务恢复待处理。");
                    changed=true;
                }
            }
            if(changed)writeStateLocked(state);
        }
    }
    public List<Review.Report> load() throws IOException {
        synchronized(lock) { return loadLocked(); }
    }
    private List<Review.Report> loadLocked() throws IOException {
        if(!Files.exists(directory)) return List.of();
        List<Review.Report> reports=new ArrayList<>();
        try(var stream=Files.list(directory)) {
            for(Path file:stream.filter(Reports::reportFile).sorted(Comparator.comparingLong(Reports::modified).reversed().thenComparing(Path::toString)).toList()) {
                try { Review.Report report=Review.JSON.fromJson(Files.readString(file),Review.Report.class);
                    if(report!=null && validId(report.id()) && file.getFileName().toString().equals(report.id()+".json") && report.result()!=null && report.target()!=null) reports.add(report);
                } catch(RuntimeException | IOException ignored) { /* Corrupt history must not stop other reports. */ }
            }
        }
        return List.copyOf(reports);
    }
    public Map<String,Resolution> loadResolutions() throws IOException {
        synchronized(lock) {
            Map<String,Resolution> resolutions=readStateLocked().resolutions();
            Set<String> reportIds=new HashSet<>();loadLocked().forEach(r->reportIds.add(r.id()));
            resolutions.keySet().retainAll(reportIds);
            return Map.copyOf(resolutions);
        }
    }
    public Map<String,Map<Integer,FindingResolution>> loadFindingResolutions() throws IOException {
        synchronized(lock) {
            ResolutionFile state=readStateLocked();Map<String,Map<Integer,FindingResolution>> result=new LinkedHashMap<>();
            for(Review.Report report:loadLocked()) {
                Map<Integer,FindingResolution> saved=state.findingResolutions().get(report.id());
                if(saved==null)continue;
                Map<Integer,FindingResolution> valid=new LinkedHashMap<>();
                for(Integer index:saved.keySet()) {
                    FindingResolution finding=findingResolution(report,index,saved);
                    if(finding!=null)valid.put(index,finding);
                }
                if(!valid.isEmpty())result.put(report.id(),Map.copyOf(valid));
            }
            return Map.copyOf(result);
        }
    }
    public Resolution acknowledge(String reportId,Decision decision,String note,String actor) throws IOException {
        requireId(reportId);Objects.requireNonNull(decision,"decision");
        String safeNote=safeNote(note),safeActor=safeActor(actor);
        synchronized(lock) {
            if(loadLocked().stream().noneMatch(r->r.id().equals(reportId)))throw new IllegalArgumentException("报告不存在，无法记录处理状态");
            ResolutionFile state=readStateLocked();
            Resolution updated=appendResolution(state,reportId,decision,Instant.now().toString(),safeActor,safeNote);
            writeStateLocked(state);return updated;
        }
    }
    public FindingResolution acknowledgeFinding(String reportId,int findingIndex,boolean confirmed,String note,String actor) throws IOException {
        requireId(reportId);String safeNote=safeNote(note),safeActor=safeActor(actor);
        synchronized(lock) {
            Review.Report report=loadLocked().stream().filter(item->item.id().equals(reportId)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("报告不存在，无法记录问题处理状态"));
            requireFinding(report,findingIndex);
            ResolutionFile state=readStateLocked();
            Map<Integer,FindingResolution> findings=state.findingResolutions().computeIfAbsent(reportId,id->new LinkedHashMap<>());
            findings.entrySet().removeIf(entry->findingResolution(report,entry.getKey(),findings)==null);
            FindingResolution old=findings.get(findingIndex);String now=Instant.now().toString();
            List<FindingAuditEvent> history=new ArrayList<>(old==null?List.of():old.history());
            history.add(new FindingAuditEvent(confirmed,now,safeActor,safeNote));
            FindingResolution updated=new FindingResolution(reportId,findingIndex,findingKey(report.result().findings().get(findingIndex)),
                confirmed,now,safeActor,safeNote,history);
            findings.put(findingIndex,updated);
            Decision previous=resolution(reportId,state.resolutions()).decision();
            boolean allConfirmed=confirmedFindingCount(report,findings)==report.result().findings().size();
            if(allConfirmed&&previous!=Decision.CONFIRMED&&previous!=Decision.CLOSED&&previous!=Decision.ACCEPTED) {
                appendResolution(state,reportId,Decision.CONFIRMED,now,safeActor,"全部 "+report.result().findings().size()+" 项问题已逐条确认解决，任务已确认。");
            } else if(!allConfirmed&&previous==Decision.CONFIRMED) {
                appendResolution(state,reportId,Decision.UNREVIEWED,now,safeActor,confirmed
                    ?"问题确认尚未全部完成，任务恢复待处理。"
                    :"第 "+(findingIndex+1)+" 项问题的已解决确认已撤销，任务恢复待处理。");
            }
            // Per-finding progress and its resulting task decision share one atomic write.
            writeStateLocked(state);return updated;
        }
    }
    public static FindingResolution findingResolution(Review.Report report,int findingIndex,Map<Integer,FindingResolution> states) {
        if(report==null||report.result()==null||report.result().findings()==null||findingIndex<0
            ||findingIndex>=report.result().findings().size()||states==null)return null;
        Review.Finding finding=report.result().findings().get(findingIndex);FindingResolution saved=states.get(findingIndex);
        if(finding==null||saved==null||!Objects.equals(report.id(),saved.reportId())||findingIndex!=saved.findingIndex()
            ||!findingKey(finding).equals(saved.findingKey()))return null;
        return saved;
    }
    public static int confirmedFindingCount(Review.Report report,Map<Integer,FindingResolution> states) {
        if(report==null||report.result()==null||report.result().findings()==null)return 0;
        int count=0;
        for(int index=0;index<report.result().findings().size();index++) {
            FindingResolution saved=findingResolution(report,index,states);if(saved!=null&&saved.confirmed())count++;
        }
        return count;
    }
    public static Resolution resolution(String reportId,Map<String,Resolution> resolutions) {
        return resolutions.getOrDefault(reportId,new Resolution(reportId,Decision.UNREVIEWED,"","","",List.of()));
    }
    private ResolutionFile readStateLocked() throws IOException {
        Path file=directory.resolve(RESOLUTIONS);
        if(!Files.exists(file))return new ResolutionFile(2,new LinkedHashMap<>(),new LinkedHashMap<>());
        try {
            ResolutionFile saved=RESOLUTION_JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),ResolutionFile.class);
            if(saved==null||(saved.schemaVersion()!=1&&saved.schemaVersion()!=2)||saved.resolutions()==null
                ||saved.schemaVersion()==2&&saved.findingResolutions()==null)throw new IllegalArgumentException();
            Map<String,Resolution> result=new LinkedHashMap<>();
            for(var entry:saved.resolutions().entrySet()) {
                Resolution r=entry.getValue();requireId(entry.getKey());
                if(r==null||!entry.getKey().equals(r.reportId())||r.decision()==null||r.updatedAt()==null||r.actor()==null||r.note()==null||r.history().isEmpty())throw new IllegalArgumentException();
                for(AuditEvent event:r.history()) {
                    if(event==null||event.decision()==null||event.at()==null||event.actor()==null||event.note()==null)throw new IllegalArgumentException();
                    Instant.parse(event.at());
                }
                AuditEvent last=r.history().get(r.history().size()-1);
                if(last.decision()!=r.decision()||!last.at().equals(r.updatedAt())||!last.actor().equals(r.actor())||!last.note().equals(r.note()))throw new IllegalArgumentException();
                result.put(entry.getKey(),r);
            }
            Map<String,Map<Integer,FindingResolution>> findingResolutions=new LinkedHashMap<>();
            if(saved.schemaVersion()==2)for(var reportEntry:saved.findingResolutions().entrySet()) {
                requireId(reportEntry.getKey());if(reportEntry.getValue()==null)throw new IllegalArgumentException();
                Map<Integer,FindingResolution> findings=new LinkedHashMap<>();
                for(var entry:reportEntry.getValue().entrySet()) {
                    FindingResolution r=entry.getValue();
                    if(entry.getKey()==null||entry.getKey()<0||r==null||!reportEntry.getKey().equals(r.reportId())
                        ||entry.getKey()!=r.findingIndex()||r.findingKey()==null||!r.findingKey().matches("[0-9a-f]{64}")
                        ||r.updatedAt()==null||r.actor()==null||r.note()==null||r.history().isEmpty())throw new IllegalArgumentException();
                    for(FindingAuditEvent event:r.history()) {
                        if(event==null||event.at()==null||event.actor()==null||event.note()==null)throw new IllegalArgumentException();
                        Instant.parse(event.at());
                    }
                    FindingAuditEvent last=r.history().get(r.history().size()-1);
                    if(last.confirmed()!=r.confirmed()||!last.at().equals(r.updatedAt())||!last.actor().equals(r.actor())
                        ||!last.note().equals(r.note()))throw new IllegalArgumentException();
                    findings.put(entry.getKey(),r);
                }
                findingResolutions.put(reportEntry.getKey(),findings);
            }
            return new ResolutionFile(2,result,findingResolutions);
        } catch(RuntimeException e) {throw new IOException("报告处理记录损坏，已保留原文件，未覆盖历史",e);}
    }
    private void writeStateLocked(ResolutionFile state) throws IOException {
        writeAtomically(directory.resolve(RESOLUTIONS),Review.JSON.toJson(new ResolutionFile(2,state.resolutions(),state.findingResolutions())));
    }
    private static Resolution appendResolution(ResolutionFile state,String reportId,Decision decision,String at,String actor,String note) {
        Resolution old=resolution(reportId,state.resolutions());List<AuditEvent> history=new ArrayList<>(old.history());
        history.add(new AuditEvent(decision,at,actor,note));
        Resolution updated=new Resolution(reportId,decision,at,actor,note,history);state.resolutions().put(reportId,updated);return updated;
    }
    private static void requireFinding(Review.Report report,int index) {
        if(report.result().findings()==null||index<0||index>=report.result().findings().size()||report.result().findings().get(index)==null)
            throw new IllegalArgumentException("报告问题索引无效");
    }
    private static String findingKey(Review.Finding finding) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Review.JSON.toJson(finding).getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String safeNote(String note) {
        String safe=note==null?"":note.strip();if(safe.length()>10_000)throw new IllegalArgumentException("处理备注过长");return safe;
    }
    private static String safeActor(String actor) {
        String safe=actor==null||actor.isBlank()?"unknown":actor.strip();if(safe.length()>256)throw new IllegalArgumentException("操作者过长");return safe;
    }
    private void writeAtomically(Path target,String text) throws IOException {
        Files.createDirectories(directory);Path temp=Files.createTempFile(directory,"report-",".tmp");
        try {
            Files.writeString(temp,text,StandardCharsets.UTF_8);
            try {Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e) {Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temp);}
    }
    public void clear() throws IOException {
        synchronized(lock) {
            if(!Files.isDirectory(directory)) return;
            // The filename identifies plugin-owned reports even if their JSON was damaged.
            try(var files=Files.list(directory)){for(Path file:files.filter(Reports::reportFile).toList())Files.deleteIfExists(file);}
            Files.deleteIfExists(directory.resolve(RESOLUTIONS));
        }
    }
    private static boolean reportFile(Path p){String name=p.getFileName().toString();return Files.isRegularFile(p)&&name.endsWith(".json")&&validId(name.substring(0,name.length()-5));}
    private static boolean validId(String id){return id!=null&&ID.matcher(id).matches();}
    private static void requireId(String id){if(!validId(id))throw new IllegalArgumentException("报告 ID 无效");}
    private static long modified(Path path) { try { return Files.getLastModifiedTime(path).toMillis(); } catch(IOException e) { return 0; } }
    public static String markdown(Review.Report report) {
        return markdown(report,resolution(report.id(),Map.of()));
    }
    public static String markdown(Review.Report report,Resolution resolution) {
        return markdown(report,resolution,Map.of());
    }
    public static String markdown(Review.Report report,Resolution resolution,Map<Integer,FindingResolution> findingResolutions) {
        if(resolution==null)resolution=resolution(report.id(),Map.of());
        if(!report.id().equals(resolution.reportId()))throw new IllegalArgumentException("处理记录与报告不匹配");
        var summary=ReportPresentation.summarize(report);
        Map<Integer,FindingResolution> findingStates=findingResolutions==null?Map.of():new HashMap<>(findingResolutions);
        int confirmedFindings=confirmedFindingCount(report,findingStates);
        StringBuilder out=new StringBuilder("# 静默代码检测报告\n\n## 检查结论\n\n");
        out.append("**").append(markdownText(ReportPresentation.conclusion(report))).append("**\n\n")
            .append("- 执行状态：").append(label(report.result().status()))
            .append("\n- 处理状态：").append(label(resolution.decision()))
            .append("\n- 问题：").append(summary.findings()).append(" 项；检查涉及 ").append(summary.files()).append(" 个文件")
            .append("\n- 问题处理：待确认 ").append(summary.findings()-confirmedFindings).append(" 项，已确认解决 ").append(confirmedFindings).append(" 项")
            .append("\n- 检查覆盖：已检查 ").append(summary.reviewedChecks()).append(" 项，待补查 ").append(summary.incompleteChecks()).append(" 项")
            .append("\n- AI 标记待人工核实：").append(summary.needsConfirmationFindings()).append(" 项；限制说明：").append(summary.limitations()).append(" 项\n\n")
            .append("| 严重级别 | 问题数 |\n| --- | ---: |\n| 错误 | ").append(summary.errors())
            .append(" |\n| 警告 | ").append(summary.warnings()).append(" |\n| 提示 | ").append(summary.infos()).append(" |\n");
        if(summary.otherSeverities()>0)out.append("| 其他 | ").append(summary.otherSeverities()).append(" |\n");
        out.append("\n时间：").append(markdownText(report.createdAt())).append("  \n仓库：").append(markdownText(report.root()))
            .append("  \n分支：").append(markdownText(report.branch())).append("\n\n");
        if(!report.result().limitations().isEmpty()) {
            out.append("## 限制与未完成项\n\n");
            report.result().limitations().forEach(l->out.append("- ").append(markdownText(l).replace("\n","  \n  ")).append('\n'));
            out.append('\n');
        }
        out.append("## 问题清单\n\n");
        if(summary.findings()==0)out.append(report.result().status()==Review.Status.COMPLETED
            ?"没有问题条目。\n\n":"尚无可展示的问题条目，请结合执行状态和待补查项目判断结果。\n\n");
        int number=0;
        for(var group:ReportPresentation.groups(report)) {
            out.append("### ").append(markdownText(group.filePath())).append("（").append(group.findings().size()).append(" 项）\n\n");
            for(var indexed:group.findings()) {
                Review.Finding f=indexed.finding();
                out.append("#### ").append(++number).append(". ").append(markdownText(ReportPresentation.severityLabel(f.severity())))
                    .append(" · ").append(markdownText(f.ruleId())).append(" · ").append(markdownText(ReportPresentation.kindLabel(f.kind())))
                    .append("\n\n**位置：** 第 ").append(f.startLine());
                if(f.endLine()!=f.startLine())out.append('–').append(f.endLine());
                out.append(" 行（").append(markdownText(sideLabel(f.side()))).append("）\n\n");
                FindingResolution findingState=findingResolution(report,indexed.index(),findingStates);
                out.append("**解决确认：** ").append(findingState!=null&&findingState.confirmed()?"已确认解决":"待确认").append("\n\n");
                if(findingState!=null) {
                    out.append("<details>\n<summary>问题处理记录（").append(findingState.history().size()).append(" 条）</summary>\n\n")
                        .append("- 更新时间：").append(markdownText(findingState.updatedAt()))
                        .append("\n- 操作者：").append(markdownText(findingState.actor()))
                        .append("\n- 备注：").append(markdownText(findingState.note()).replace("\n","  \n  ")).append("\n\n")
                        .append("| 时间 | 解决确认 | 操作者 | 备注 |\n| --- | --- | --- | --- |\n");
                    for(FindingAuditEvent event:findingState.history())out.append("| ").append(cell(event.at())).append(" | ")
                        .append(event.confirmed()?"已确认解决":"恢复待确认").append(" | ").append(cell(event.actor())).append(" | ").append(cell(event.note())).append(" |\n");
                    out.append("\n</details>\n\n");
                }
                out.append("**原因：** ").append(markdownText(ReportPresentation.firstLine(f.explanation(),120))).append("\n\n")
                    .append("**建议：** ").append(markdownText(ReportPresentation.firstLine(f.suggestion(),120))).append("\n\n");
                if(!Objects.equals(f.explanation(),ReportPresentation.firstLine(f.explanation(),120))
                    ||!Objects.equals(f.suggestion(),ReportPresentation.firstLine(f.suggestion(),120))) {
                    out.append("<details>\n<summary>完整原因与建议</summary>\n\n")
                        .append("**原因：** ").append(markdownText(f.explanation())).append("\n\n")
                        .append("**建议：** ").append(markdownText(f.suggestion())).append("\n\n</details>\n\n");
                }
                out.append("<details>\n<summary>代码证据与变更关系</summary>\n\n");
                fenced(out,f.evidence(),"");
                out.append("**与变更的关系：** ").append(markdownText(f.changeRelation())).append("\n\n</details>\n\n");
                ruleDefinition(report,f.ruleId()).ifPresent(rule->{
                    out.append("<details>\n<summary>规范要求与依据</summary>\n\n");
                    appendRuleField(out,rule,"title","规则标题");appendRuleField(out,rule,"requirement","规范要求");
                    if(rule.has("source")&&rule.get("source").isJsonObject()) {
                        var source=rule.getAsJsonObject("source");
                        appendRuleField(out,source,"document","规范文档");appendRuleField(out,source,"excerpt","规范依据");
                    }
                    out.append("</details>\n\n");
                });
            }
        }
        out.append("## 检查覆盖\n\n<details>\n<summary>查看逐文件、逐规则的检查状态</summary>\n\n")
            .append("| 文件 | 规则 ID | 检查状态 | 说明 |\n| --- | --- | --- | --- |\n");
        report.result().coverage().stream().sorted(Comparator.comparing(Review.Coverage::filePath).thenComparing(Review.Coverage::ruleId)).forEach(c->
            out.append("| ").append(cell(c.filePath())).append(" | ").append(cell(c.ruleId())).append(" | ")
                .append(cell(ReportPresentation.coverageLabel(c.status()))).append(" | ").append(cell(c.reason())).append(" |\n"));
        if(report.result().coverage().isEmpty())out.append("| — | — | 未提供覆盖记录 | 请结合执行状态判断，不代表检查通过 |\n");
        out.append("\n</details>\n\n## 人工处理\n\n- 处理状态：").append(label(resolution.decision())).append('\n');
        if(!resolution.updatedAt().isBlank())out.append("- 更新时间：").append(markdownText(resolution.updatedAt()))
            .append("\n- 操作者：").append(markdownText(resolution.actor())).append("\n- 备注：").append(markdownText(resolution.note()).replace("\n","  \n  ")).append('\n');
        out.append("\n人工确认、采纳或关闭仅记录处理进度，不改变 AI 执行状态、检查覆盖或未完成项；采纳不会自动修改代码。\n\n")
            .append("逐问题的“已确认解决”表示人工确认，不代表 AI 已重新检查；即使全部问题已确认解决，部分完成或失败的检查仍需补查。\n\n");
        if(!resolution.history().isEmpty()) {
            out.append("<details>\n<summary>处理历史（").append(resolution.history().size()).append(" 条）</summary>\n\n")
                .append("| 时间 | 处理 | 操作者 | 备注 |\n| --- | --- | --- | --- |\n");
            for(AuditEvent event:resolution.history())out.append("| ").append(cell(event.at())).append(" | ").append(label(event.decision())).append(" | ").append(cell(event.actor())).append(" | ").append(cell(event.note())).append(" |\n");
            out.append("\n</details>\n\n");
        }
        out.append("## 报告信息\n\n<details>\n<summary>范围、版本和技术元数据</summary>\n\n");
        Map<String,String> metadata=new LinkedHashMap<>();
        metadata.put("报告 ID",report.id());metadata.put("时间",report.createdAt());metadata.put("仓库",report.root());
        metadata.put("分支",report.branch());metadata.put("范围",report.scope());metadata.put("基线",report.base());metadata.put("提交",report.target());
        metadata.put("规则集 ID",report.ruleSetId());metadata.put("规则集",report.ruleSetName());metadata.put("规则集版本",report.ruleSetVersion());
        metadata.put("规则指纹",report.fingerprint());metadata.put("审查指令",report.instructionVersion());metadata.put("模型",report.model());
        out.append("| 项目 | 内容 |\n| --- | --- |\n");
        metadata.forEach((name,value)->out.append("| ").append(name).append(" | ").append(cell(value)).append(" |\n"));
        out.append('\n');
        if(report.previousPaths()!=null&&!report.previousPaths().isEmpty()) {
            out.append("历史路径映射：\n\n");fenced(out,Review.JSON.toJson(report.previousPaths()),"json");
        }
        if(report.ruleDefinitions()!=null&&!report.ruleDefinitions().isEmpty()) {
            out.append("本次规则快照：\n\n");fenced(out,Review.JSON.toJson(report.ruleDefinitions()),"json");
        }
        if(report.usage()!=null) {out.append("接口用量：\n\n");fenced(out,Review.JSON.toJson(report.usage()),"json");}
        out.append("</details>\n");
        return out.toString();
    }
    private static Optional<com.google.gson.JsonObject> ruleDefinition(Review.Report report,String ruleId) {
        if(report.ruleDefinitions()==null)return Optional.empty();
        return report.ruleDefinitions().stream().filter(Objects::nonNull).filter(rule->rule.has("id")&&rule.get("id").isJsonPrimitive()
            &&Objects.equals(ruleId,rule.get("id").getAsString())).findFirst();
    }
    private static void appendRuleField(StringBuilder out,com.google.gson.JsonObject object,String key,String name) {
        if(!object.has(key)||object.get(key).isJsonNull())return;
        var value=object.get(key);
        out.append("**").append(name).append("：** ").append(markdownText(value.isJsonPrimitive()?value.getAsString():Review.JSON.toJson(value))).append("\n\n");
    }
    private static String sideLabel(String side) {
        return "after".equals(side)?"变更后代码":"before".equals(side)?"变更前代码":side;
    }
    private static String markdownText(String value) {
        if(value==null)return "";
        StringBuilder out=new StringBuilder();
        for(char ch:value.replace("\r\n","\n").replace('\r','\n').toCharArray()) {
            switch(ch) {
                case '&'->out.append("&amp;");case '<'->out.append("&lt;");case '>'->out.append("&gt;");case '"'->out.append("&quot;");
                default->{if("\\`*_{}[]()#+-.!|".indexOf(ch)>=0)out.append('\\');out.append(ch);}
            }
        }
        return out.toString();
    }
    private static String cell(String value){return markdownText(value).replace("\n","<br>");}
    private static void fenced(StringBuilder out,String value,String language) {
        String text=value==null?"":value.replace("\r\n","\n").replace('\r','\n');
        int longest=0,run=0;
        for(char ch:text.toCharArray()) {run=ch=='`'?run+1:0;longest=Math.max(longest,run);}
        String fence="`".repeat(Math.max(3,longest+1));
        out.append(fence).append(language).append('\n').append(text);
        if(!text.endsWith("\n"))out.append('\n');
        out.append(fence).append("\n\n");
    }
    public static String label(Decision decision) {
        return switch(decision) {case UNREVIEWED->"待处理";case CONFIRMED->"已确认";case ACCEPTED->"已采纳";case CLOSED->"已关闭";};
    }
    public static String label(Review.Status status) {
        return switch(status) {
            case QUEUED -> "等待检查"; case PREPARING -> "准备检查"; case RUNNING -> "正在检查";
            case COMPLETED -> "检查完成"; case PARTIAL -> "部分完成／待确认"; case FAILED -> "执行失败";
            case CANCELED -> "已取消"; case NOT_APPLICABLE -> "无适用检查";
        };
    }
}
