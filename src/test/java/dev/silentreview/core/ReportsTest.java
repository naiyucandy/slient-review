package dev.silentreview.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportsTest {
    @TempDir Path directory;
    @Test void roundTripKeepsSnapshotAndRuleEvidence()throws Exception{
        var req=TestFixtures.request();var report=Review.report(req,"single","test-model",null,Review.validate(req,TestFixtures.response(true).toString()));
        Reports store=new Reports(directory);store.save(report);var loaded=store.load().get(0);
        assertEquals(report.target(),loaded.target());assertEquals(report.fingerprint(),loaded.fingerprint());assertTrue(Reports.markdown(loaded).contains("禁止控制台打印"));
    }
    @Test void corruptHistoryDoesNotHideValidReports()throws Exception{
        var req=TestFixtures.request();var report=Review.report(req,"single","test",null,Review.validate(req,TestFixtures.response(false).toString()));
        Reports store=new Reports(directory);store.save(report);Files.writeString(directory.resolve("corrupt.json"),"not json");assertEquals(1,store.load().size());
    }
    @Test void clearingHistoryDoesNotDeleteUnrelatedFiles()throws Exception{
        var req=TestFixtures.request();var report=Review.report(req,"single","test",null,Review.validate(req,TestFixtures.response(false).toString()));
        Reports store=new Reports(directory);store.save(report);Files.writeString(directory.resolve("notes.txt"),"keep");store.clear();assertTrue(Files.exists(directory.resolve("notes.txt")));assertTrue(store.load().isEmpty());
    }
    @Test void legacyReportStartsUnreviewedAndHumanProcessingDoesNotRewriteEvidence()throws Exception{
        var report=report(Review.Status.PARTIAL,"/repo");
        // This is the unchanged report JSON format written by the earlier plugin.
        Files.writeString(directory.resolve(report.id()+".json"),Review.JSON.toJson(report));
        Reports store=new Reports(directory);assertTrue(store.loadResolutions().isEmpty());
        assertEquals(Reports.Decision.UNREVIEWED,Reports.resolution(report.id(),store.loadResolutions()).decision());
        byte[] original=Files.readAllBytes(directory.resolve(report.id()+".json"));
        var acknowledged=store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"需人工补查未覆盖范围","tester");
        assertArrayEquals(original,Files.readAllBytes(directory.resolve(report.id()+".json")));
        assertEquals(Review.Status.PARTIAL,store.load().get(0).result().status());
        assertEquals(Reports.Decision.CONFIRMED,acknowledged.decision());
        String markdown=Reports.markdown(store.load().get(0),acknowledged);
        assertTrue(markdown.contains("部分完成"));assertTrue(markdown.contains("不改变 AI 执行状态"));assertTrue(markdown.contains("需人工补查未覆盖范围"));
    }
    @Test void resolutionAndAuditHistorySurviveReloadAndClosedReportRemainsVisible()throws Exception{
        var report=report(Review.Status.COMPLETED,"/repo");Reports store=new Reports(directory);store.save(report);
        store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"看过了","alice");
        store.acknowledge(report.id(),Reports.Decision.ACCEPTED,"建议已手动采纳","bob");
        store.acknowledge(report.id(),Reports.Decision.CLOSED,"修复完成","alice");
        Reports reopened=new Reports(directory);var resolution=reopened.loadResolutions().get(report.id());
        assertEquals(Reports.Decision.CLOSED,resolution.decision());assertEquals("修复完成",resolution.note());
        assertEquals(List.of(Reports.Decision.CONFIRMED,Reports.Decision.ACCEPTED,Reports.Decision.CLOSED),resolution.history().stream().map(Reports.AuditEvent::decision).toList());
        assertEquals(List.of("alice","bob","alice"),resolution.history().stream().map(Reports.AuditEvent::actor).toList());
        assertEquals(1,reopened.load().size());assertFalse(resolution.updatedAt().isBlank());
        assertThrows(UnsupportedOperationException.class,()->resolution.history().clear());
        assertTrue(Reports.markdown(report,resolution).contains("建议已手动采纳"));
    }
    @Test void confirmationOfFailureDoesNotClaimSuccessfulAiReview()throws Exception{
        var report=report(Review.Status.FAILED,"/repo");Reports store=new Reports(directory);store.save(report);
        var resolution=store.acknowledge(report.id(),Reports.Decision.ACCEPTED,"人工记录","tester");
        assertEquals(Review.Status.FAILED,store.load().get(0).result().status());
        String markdown=Reports.markdown(report,resolution);
        assertTrue(markdown.contains("执行状态：执行失败"));assertTrue(markdown.contains("待补查"));assertFalse(markdown.contains("本次约定范围内的检查未发现问题"));
    }
    @Test void concurrentUpdatesFromTwoInstancesDoNotLoseAuditEvents()throws Exception{
        var report=report(Review.Status.COMPLETED,"/repo");Reports first=new Reports(directory),second=new Reports(directory);first.save(report);
        ExecutorService workers=Executors.newFixedThreadPool(6);
        try{
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<36;i++){final int n=i;tasks.add(workers.submit(()->{
                try{(n%2==0?first:second).acknowledge(report.id(),Reports.Decision.CONFIRMED,"event-"+n,"tester");}
                catch(Exception e){throw new CompletionException(e);}
            }));}
            for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }finally{workers.shutdownNow();}
        var resolution=new Reports(directory).loadResolutions().get(report.id());
        assertEquals(36,resolution.history().size());assertEquals(36,resolution.history().stream().map(Reports.AuditEvent::note).distinct().count());
        try(var files=Files.list(directory)){assertFalse(files.anyMatch(p->p.toString().endsWith(".tmp")));}
    }
    @Test void invalidAndMissingReportIdsCannotCreateOrEscapeResolutionFile()throws Exception{
        Reports store=new Reports(directory);
        assertThrows(IllegalArgumentException.class,()->store.acknowledge("../escape",Reports.Decision.CONFIRMED,"","tester"));
        assertThrows(IllegalArgumentException.class,()->store.acknowledge(UUID.randomUUID().toString(),Reports.Decision.CONFIRMED,"","tester"));
        assertFalse(Files.exists(directory.resolve("resolutions.json")));
    }
    @Test void corruptAuditHistoryIsPreservedInsteadOfSilentlyOverwritten()throws Exception{
        var report=report(Review.Status.COMPLETED,"/repo");Reports store=new Reports(directory);store.save(report);
        Path state=directory.resolve("resolutions.json");Files.writeString(state,"broken audit evidence");
        assertThrows(java.io.IOException.class,()->store.acknowledge(report.id(),Reports.Decision.CLOSED,"","tester"));
        assertEquals("broken audit evidence",Files.readString(state));assertEquals(1,store.load().size());
    }
    @Test void clearRemovesResolutionHistoryAndPreservesUnrelatedJson()throws Exception{
        var report=report(Review.Status.COMPLETED,"/repo");Reports store=new Reports(directory);store.save(report);
        store.acknowledge(report.id(),Reports.Decision.CLOSED,"","tester");Files.writeString(directory.resolve("notes.json"),"{\"keep\":true}");
        store.clear();assertTrue(store.load().isEmpty());assertTrue(store.loadResolutions().isEmpty());
        assertFalse(Files.exists(directory.resolve("resolutions.json")));assertEquals("{\"keep\":true}",Files.readString(directory.resolve("notes.json")));
    }
    @Test void clearRemovesCorruptPluginOwnedReportAndPreservesUnrelatedFiles()throws Exception{
        Reports store=new Reports(directory);Path corrupt=directory.resolve(UUID.randomUUID()+".json");
        Files.writeString(corrupt,"interrupted report write");Files.writeString(directory.resolve("notes.txt"),"keep text");Files.writeString(directory.resolve("notes.json"),"keep json");
        store.clear();assertFalse(Files.exists(corrupt));assertEquals("keep text",Files.readString(directory.resolve("notes.txt")));assertEquals("keep json",Files.readString(directory.resolve("notes.json")));
    }
    @Test void markdownExportsAllProcessingEventsAndEscapesTableCells()throws Exception{
        var report=report(Review.Status.COMPLETED,"/repo");Reports store=new Reports(directory);store.save(report);
        store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"scope | checked\nnext","alice");
        var resolution=store.acknowledge(report.id(),Reports.Decision.UNREVIEWED,"重新检查","bob");
        String markdown=Reports.markdown(report,resolution);
        assertTrue(markdown.contains("scope \\| checked<br>next"));assertTrue(markdown.contains("重新检查"));assertTrue(markdown.contains("处理状态：待处理"));
        assertThrows(IllegalArgumentException.class,()->Reports.markdown(report,new Reports.Resolution(UUID.randomUUID().toString(),Reports.Decision.CLOSED,"","","",List.of())));
    }
    @Test void markdownLeadsWithConclusionAndGroupsEveryFindingUnderItsFile(){
        var low=finding("src/A","info",30,"long explanation A","suggestion A","evidence A");
        var high=finding("src/B","error",8,"long explanation B","suggestion B","evidence B");
        var warning=finding("src/B","warning",2,"long explanation C","suggestion C","evidence C");
        var result=new Review.Result(List.of(low,warning,high),List.of(
            new Review.Coverage("src/A","JAVA-001","reviewed","checked A"),
            new Review.Coverage("src/B","JAVA-001","reviewed","checked B")),List.of(),Review.Status.COMPLETED);
        String markdown=Reports.markdown(withResult(result));
        assertTrue(markdown.indexOf("## 检查结论")<markdown.indexOf("## 问题清单"));
        assertTrue(markdown.indexOf("### src/B")<markdown.indexOf("### src/A"));
        assertTrue(markdown.indexOf("long explanation B")<markdown.indexOf("long explanation C"));
        assertEquals(1,markdown.split("### src/B（",-1).length-1);
        for(String text:List.of("long explanation A","long explanation B","long explanation C","suggestion A","suggestion B","suggestion C","evidence A","evidence B","evidence C"))assertTrue(markdown.contains(text),text);
        assertTrue(markdown.contains("第 8 行（变更后代码）"));
        assertTrue(markdown.contains("| 错误 | 1 |"));assertTrue(markdown.contains("| 警告 | 1 |"));assertTrue(markdown.contains("| 提示 | 1 |"));
        assertTrue(markdown.indexOf("## 人工处理")>markdown.indexOf("## 问题清单"));
        assertTrue(markdown.indexOf("规则指纹")>markdown.indexOf("## 报告信息"));
    }
    @Test void markdownShowsCoverageGapsAndNeverTreatsHumanAcceptanceAsReviewCompletion(){
        for(Review.Status status:List.of(Review.Status.PARTIAL,Review.Status.FAILED,Review.Status.CANCELED,Review.Status.NOT_APPLICABLE,Review.Status.QUEUED,Review.Status.PREPARING,Review.Status.RUNNING)) {
            var report=withResult(new Review.Result(List.of(),List.of(
                new Review.Coverage("src/A","JAVA-001","reviewed",""),
                new Review.Coverage("src/B","JAVA-001","needs_context","缺少配置文件"),
                new Review.Coverage("src/C","JAVA-001","skipped","文件超限"),
                new Review.Coverage("src/D","JAVA-001","failed","接口失败")),List.of("仓库范围尚未全部覆盖"),status));
            var decision=new Reports.Resolution(report.id(),Reports.Decision.ACCEPTED,"2026-10-28T00:00:00Z","tester","已人工采纳",List.of());
            String markdown=Reports.markdown(report,decision);
            assertFalse(markdown.contains("本次约定范围内的检查未发现问题"),status.name());
            assertTrue(markdown.contains("已检查 1 项，待补查 3 项"));
            assertTrue(markdown.contains("缺少上下文"));assertTrue(markdown.contains("已跳过"));assertTrue(markdown.contains("检查失败"));
            assertTrue(markdown.contains("仓库范围尚未全部覆盖"));assertTrue(markdown.contains("处理状态：已采纳"));
            assertTrue(markdown.contains("不改变 AI 执行状态"));
        }
        assertTrue(Reports.markdown(report(Review.Status.COMPLETED,"/repo")).contains("本次约定范围内的检查未发现问题"));
        String inconsistent=Reports.markdown(withResult(new Review.Result(List.of(),List.of(new Review.Coverage("src/A","JAVA-001","skipped","未检查")),List.of(),Review.Status.COMPLETED)));
        assertFalse(inconsistent.contains("本次约定范围内的检查未发现问题"));
    }
    @Test void markdownTreatsExternalTextAsTextWithoutLosingEvidenceOrSourceRules(){
        String explanation="<script>alert(1)</script>\n# injected heading\n[run](javascript:alert(1))\n</details>";
        var finding=finding("src/<img onerror=alert(1)>","warning",2,explanation,"**do not execute**","System.out.println(order);");
        var report=withResult(new Review.Result(List.of(finding),List.of(new Review.Coverage(finding.filePath(),finding.ruleId(),"reviewed","<b>reason</b> | next\nline")),List.of(),Review.Status.COMPLETED));
        String markdown=Reports.markdown(report);
        assertFalse(markdown.contains("<script>"));assertFalse(markdown.contains("<img"));assertFalse(markdown.contains("<b>reason</b>"));
        assertTrue(markdown.contains("&lt;script&gt;alert\\(1\\)&lt;/script&gt;"));
        assertTrue(markdown.contains("\\# injected heading"));assertTrue(markdown.contains("\\[run\\]\\(javascript:alert\\(1\\)\\)"));
        assertTrue(markdown.contains("&lt;/details&gt;"));assertTrue(markdown.contains("\\*\\*do not execute\\*\\*"));
        assertTrue(markdown.contains("&lt;b&gt;reason&lt;/b&gt; \\| next<br>line"));
        assertTrue(markdown.contains("System.out.println(order);"));assertTrue(markdown.contains("禁止控制台打印"));
        assertTrue(markdown.contains("规范要求与依据"));assertTrue(markdown.contains("本次规则快照"));
    }
    @Test void markdownEvidenceFenceCannotBeClosedByCodeContainingLongBacktickRuns(){
        String evidence="first\n``````\n<script>code only</script>\n# code heading\nlast";
        var report=withResult(new Review.Result(List.of(finding("src/A","warning",2,"explanation","suggestion",evidence)),List.of(),List.of(),Review.Status.COMPLETED));
        String markdown=Reports.markdown(report);
        assertTrue(markdown.contains("```````\n"+evidence+"\n```````"));
        assertEquals(2,markdown.lines().filter("```````"::equals).count());
        assertTrue(markdown.indexOf("代码证据与变更关系")<markdown.indexOf(evidence));
        assertTrue(markdown.indexOf(evidence)<markdown.indexOf("**与变更的关系：**"));
    }
    @Test void markdownKeepsLongReasonsAvailableWithoutFillingTheProblemSummary(){
        String explanation="摘要原因\n完整细节 "+"长".repeat(300);
        String suggestion="建议摘要\n修复步骤 "+"细".repeat(300);
        var report=withResult(new Review.Result(List.of(finding("src/A","warning",12,explanation,suggestion,"code")),List.of(),List.of(),Review.Status.COMPLETED));
        String markdown=Reports.markdown(report);
        int fold=markdown.indexOf("<summary>完整原因与建议</summary>");
        assertTrue(fold>0);assertTrue(markdown.substring(0,fold).contains("**原因：** 摘要原因"));
        assertFalse(markdown.substring(0,fold).contains("完整细节"));
        assertTrue(markdown.substring(fold).contains(explanation));assertTrue(markdown.substring(fold).contains(suggestion));
    }
    private static Review.Finding finding(String path,String severity,int line,String explanation,String suggestion,String evidence){
        return new Review.Finding("JAVA-001","violation",severity,path,"after",line,line,evidence,explanation,suggestion,"新增调用");
    }
    private static Review.Report withResult(Review.Result result){
        var original=report(Review.Status.COMPLETED,"/repo");
        return new Review.Report(original.id(),original.createdAt(),original.root(),original.branch(),original.base(),original.target(),original.scope(),original.ruleSetId(),original.ruleSetName(),original.ruleSetVersion(),original.fingerprint(),original.ruleDefinitions(),original.previousPaths(),original.instructionVersion(),original.model(),original.usage(),result);
    }
    private static Review.Report report(Review.Status status,String root){
        var req=TestFixtures.request();var original=Review.report(req,"single","test",null,new Review.Result(List.of(),List.of(),status==Review.Status.COMPLETED?List.of():List.of("待补查"),status));
        return new Review.Report(original.id(),original.createdAt(),root,original.branch(),original.base(),original.target(),original.scope(),original.ruleSetId(),original.ruleSetName(),original.ruleSetVersion(),original.fingerprint(),original.ruleDefinitions(),original.previousPaths(),original.instructionVersion(),original.model(),original.usage(),original.result());
    }
}
