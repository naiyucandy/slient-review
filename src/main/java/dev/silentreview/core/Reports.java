package dev.silentreview.core;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Reports {
    public enum Decision { PENDING, CONFIRMED }
    /** Reads both the two current states and the four states written by earlier plugin versions. */
    private static final Gson RESOLUTION_JSON=new GsonBuilder().registerTypeAdapter(Decision.class,
        (JsonDeserializer<Decision>)(json,type,context)->{
            String value=json.getAsString();
            return switch(value) {
                case "PENDING","UNREVIEWED" -> Decision.PENDING;
                case "CONFIRMED","ACCEPTED","CLOSED" -> Decision.CONFIRMED;
                default -> throw new JsonParseException("未知报告处理状态："+value);
            };
        }).create();
    public record AuditEvent(Decision decision,String at,String actor,String note) {}
    public record Resolution(String reportId,Decision decision,String updatedAt,String actor,String note,List<AuditEvent> history) {
        public Resolution { history=history==null?List.of():List.copyOf(history); }
    }
    private record ResolutionFile(int schemaVersion,Map<String,Resolution> resolutions) {}
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
            writeAtomically(directory.resolve(report.id()+".json"),Review.JSON.toJson(report));
            List<Review.Report> reports=loadLocked();
            if(reports.size()>100) {
                Set<String> removed=new HashSet<>();
                for(int i=100;i<reports.size();i++) {String id=reports.get(i).id();Files.deleteIfExists(directory.resolve(id+".json"));removed.add(id);}
                Map<String,Resolution> resolutions=readResolutionsLocked();
                if(resolutions.keySet().removeAll(removed))writeResolutionsLocked(resolutions);
            }
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
            Map<String,Resolution> resolutions=readResolutionsLocked();
            Set<String> reportIds=new HashSet<>();loadLocked().forEach(r->reportIds.add(r.id()));
            resolutions.keySet().retainAll(reportIds);
            return Map.copyOf(resolutions);
        }
    }
    public Resolution acknowledge(String reportId,Decision decision,String note,String actor) throws IOException {
        requireId(reportId);Objects.requireNonNull(decision,"decision");
        String safeNote=note==null?"":note.strip(),safeActor=actor==null||actor.isBlank()?"unknown":actor.strip();
        if(safeNote.length()>10_000||safeActor.length()>256)throw new IllegalArgumentException("处理备注或操作者过长");
        synchronized(lock) {
            if(loadLocked().stream().noneMatch(r->r.id().equals(reportId)))throw new IllegalArgumentException("报告不存在，无法记录处理状态");
            Map<String,Resolution> resolutions=readResolutionsLocked();
            Resolution old=resolution(reportId,resolutions);String now=Instant.now().toString();
            List<AuditEvent> history=new ArrayList<>(old.history());history.add(new AuditEvent(decision,now,safeActor,safeNote));
            Resolution updated=new Resolution(reportId,decision,now,safeActor,safeNote,history);
            resolutions.put(reportId,updated);writeResolutionsLocked(resolutions);return updated;
        }
    }
    public static Resolution resolution(String reportId,Map<String,Resolution> resolutions) {
        return resolutions.getOrDefault(reportId,new Resolution(reportId,Decision.PENDING,"","","",List.of()));
    }
    private Map<String,Resolution> readResolutionsLocked() throws IOException {
        Path file=directory.resolve(RESOLUTIONS);if(!Files.exists(file))return new LinkedHashMap<>();
        try {
            ResolutionFile saved=RESOLUTION_JSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),ResolutionFile.class);
            if(saved==null||saved.schemaVersion()!=1||saved.resolutions()==null)throw new IllegalArgumentException();
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
            return result;
        } catch(RuntimeException e) {throw new IOException("报告处理记录损坏，已保留原文件，未覆盖历史",e);}
    }
    private void writeResolutionsLocked(Map<String,Resolution> resolutions) throws IOException {
        writeAtomically(directory.resolve(RESOLUTIONS),RESOLUTION_JSON.toJson(new ResolutionFile(1,resolutions)));
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
        if(resolution==null)resolution=resolution(report.id(),Map.of());
        if(!report.id().equals(resolution.reportId()))throw new IllegalArgumentException("处理记录与报告不匹配");
        StringBuilder out=new StringBuilder("# 静默代码检测报告\n\n");
        out.append("- 时间：").append(report.createdAt()).append("\n- 仓库：").append(report.root())
            .append("\n- 分支：").append(report.branch()).append("\n- 范围：").append(report.scope())
            .append("\n- 基线：").append(report.base()).append("\n- 提交：").append(report.target())
            .append("\n- 规则集：").append(report.ruleSetName()).append(" / ").append(report.ruleSetVersion())
            .append("\n- 规则指纹：").append(report.fingerprint()).append("\n- 审查指令：").append(report.instructionVersion())
            .append("\n- 模型：").append(report.model()).append("\n- 执行状态：").append(label(report.result().status())).append("\n\n");
        out.append("## 人工处理\n\n- 处理状态：").append(label(resolution.decision())).append('\n');
        if(!resolution.updatedAt().isBlank())out.append("- 更新时间：").append(resolution.updatedAt()).append("\n- 操作者：").append(resolution.actor()).append("\n- 备注：").append(resolution.note()).append('\n');
        out.append("\n人工处理仅记录待处理或已确认的进度，不改变 AI 执行状态、检查覆盖或未完成项。\n\n");
        if(!resolution.history().isEmpty()) {
            out.append("| 时间 | 处理 | 操作者 | 备注 |\n| --- | --- | --- | --- |\n");
            for(AuditEvent event:resolution.history())out.append("| ").append(cell(event.at())).append(" | ").append(label(event.decision())).append(" | ").append(cell(event.actor())).append(" | ").append(cell(event.note())).append(" |\n");
            out.append('\n');
        }
        if(report.result().status()==Review.Status.COMPLETED && report.result().findings().isEmpty()) out.append("本次约定范围内的检查未发现问题。\n\n");
        for(Review.Finding f:report.result().findings()) {
            out.append("## ").append(f.ruleId()).append(" · ").append(f.severity()).append(" · ").append(f.kind()).append("\n\n")
                .append(f.filePath()).append("：").append(f.startLine()).append("–").append(f.endLine()).append("（").append(f.side()).append("）\n\n")
                .append("````\n").append(f.evidence()).append("\n````\n\n")
                .append(f.explanation()).append("\n\n建议：").append(f.suggestion())
                .append("\n\n与变更的关系：").append(f.changeRelation()).append("\n\n");
            if(report.ruleDefinitions()!=null) report.ruleDefinitions().stream().filter(r->f.ruleId().equals(r.get("id").getAsString())).findFirst().ifPresent(r->{
                out.append("规范要求：").append(r.get("requirement").getAsString()).append("\n\n");
                if(r.has("source"))out.append("规范依据：").append(r.getAsJsonObject("source").get("excerpt").getAsString()).append("\n\n");
            });
        }
        out.append("## 检查覆盖\n\n");
        for(Review.Coverage c:report.result().coverage()) out.append("- ").append(c.filePath()).append(" / ").append(c.ruleId()).append("：").append(c.status()).append(" ").append(c.reason()).append('\n');
        if(!report.result().limitations().isEmpty()) {
            out.append("\n## 限制与未完成项\n\n");
            report.result().limitations().forEach(l->out.append("- ").append(l).append('\n'));
        }
        if(report.usage()!=null) out.append("\n接口用量：\n\n```json\n").append(report.usage()).append("\n```\n");
        return out.toString();
    }
    private static String cell(String value){return value.replace("|","\\|").replace("\r","").replace("\n","<br>");}
    public static String label(Decision decision) {
        return switch(decision) {case PENDING->"待处理";case CONFIRMED->"已确认";};
    }
    public static String label(Review.Status status) {
        return switch(status) {
            case QUEUED -> "等待检查"; case PREPARING -> "准备检查"; case RUNNING -> "正在检查";
            case COMPLETED -> "检查完成"; case PARTIAL -> "部分完成／待确认"; case FAILED -> "执行失败";
            case CANCELED -> "已取消"; case NOT_APPLICABLE -> "无适用检查";
        };
    }
}
