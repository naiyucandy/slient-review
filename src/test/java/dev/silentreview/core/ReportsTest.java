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
        assertEquals(Reports.Decision.PENDING,Reports.resolution(report.id(),store.loadResolutions()).decision());
        byte[] original=Files.readAllBytes(directory.resolve(report.id()+".json"));
        var acknowledged=store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"需人工补查未覆盖范围","tester");
        assertArrayEquals(original,Files.readAllBytes(directory.resolve(report.id()+".json")));
        assertEquals(Review.Status.PARTIAL,store.load().get(0).result().status());
        assertEquals(Reports.Decision.CONFIRMED,acknowledged.decision());
        String markdown=Reports.markdown(store.load().get(0),acknowledged);
        assertTrue(markdown.contains("部分完成"));assertTrue(markdown.contains("不改变 AI 执行状态"));assertTrue(markdown.contains("需人工补查未覆盖范围"));
    }
    @Test void legacyFourStateResolutionsLoadAsTwoStates()throws Exception{
        var report=report(Review.Status.COMPLETED,"/repo");Reports store=new Reports(directory);store.save(report);
        String legacy="""
            {"schemaVersion":1,"resolutions":{"%1$s":{"reportId":"%1$s","decision":"CLOSED","updatedAt":"2024-01-03T00:00:00Z","actor":"tester","note":"legacy","history":[
            {"decision":"UNREVIEWED","at":"2024-01-01T00:00:00Z","actor":"tester","note":""},
            {"decision":"ACCEPTED","at":"2024-01-02T00:00:00Z","actor":"tester","note":""},
            {"decision":"CLOSED","at":"2024-01-03T00:00:00Z","actor":"tester","note":"legacy"}]}}}
            """.formatted(report.id());
        Files.writeString(directory.resolve("resolutions.json"),legacy);
        var resolution=new Reports(directory).loadResolutions().get(report.id());
        assertEquals(Reports.Decision.CONFIRMED,resolution.decision());
        assertEquals(List.of(Reports.Decision.PENDING,Reports.Decision.CONFIRMED,Reports.Decision.CONFIRMED),
            resolution.history().stream().map(Reports.AuditEvent::decision).toList());
        assertTrue(Reports.markdown(report,resolution).contains("处理状态：已确认"));
    }
    @Test void resolutionAndAuditHistorySurviveReloadAndConfirmedReportRemainsVisible()throws Exception{
        var report=report(Review.Status.COMPLETED,"/repo");Reports store=new Reports(directory);store.save(report);
        store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"看过了","alice");
        store.acknowledge(report.id(),Reports.Decision.PENDING,"重新检查","bob");
        store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"看过了","alice");
        Reports reopened=new Reports(directory);var resolution=reopened.loadResolutions().get(report.id());
        assertEquals(Reports.Decision.CONFIRMED,resolution.decision());assertEquals("看过了",resolution.note());
        assertEquals(List.of(Reports.Decision.CONFIRMED,Reports.Decision.PENDING,Reports.Decision.CONFIRMED),resolution.history().stream().map(Reports.AuditEvent::decision).toList());
        assertEquals(List.of("alice","bob","alice"),resolution.history().stream().map(Reports.AuditEvent::actor).toList());
        assertEquals(1,reopened.load().size());assertFalse(resolution.updatedAt().isBlank());
        assertThrows(UnsupportedOperationException.class,()->resolution.history().clear());
        assertTrue(Reports.markdown(report,resolution).contains("重新检查"));
    }
    @Test void confirmationOfFailureDoesNotClaimSuccessfulAiReview()throws Exception{
        var report=report(Review.Status.FAILED,"/repo");Reports store=new Reports(directory);store.save(report);
        var resolution=store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"人工记录","tester");
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
        assertThrows(java.io.IOException.class,()->store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"","tester"));
        assertEquals("broken audit evidence",Files.readString(state));assertEquals(1,store.load().size());
    }
    @Test void clearRemovesResolutionHistoryAndPreservesUnrelatedJson()throws Exception{
        var report=report(Review.Status.COMPLETED,"/repo");Reports store=new Reports(directory);store.save(report);
        store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"","tester");Files.writeString(directory.resolve("notes.json"),"{\"keep\":true}");
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
        var resolution=store.acknowledge(report.id(),Reports.Decision.PENDING,"重新检查","bob");
        String markdown=Reports.markdown(report,resolution);
        assertTrue(markdown.contains("scope \\| checked<br>next"));assertTrue(markdown.contains("重新检查"));assertTrue(markdown.contains("处理状态：待处理"));
        assertThrows(IllegalArgumentException.class,()->Reports.markdown(report,new Reports.Resolution(UUID.randomUUID().toString(),Reports.Decision.CONFIRMED,"","","",List.of())));
    }
    private static Review.Report report(Review.Status status,String root){
        var req=TestFixtures.request();var original=Review.report(req,"single","test",null,new Review.Result(List.of(),List.of(),status==Review.Status.COMPLETED?List.of():List.of("待补查"),status));
        return new Review.Report(original.id(),original.createdAt(),root,original.branch(),original.base(),original.target(),original.scope(),original.ruleSetId(),original.ruleSetName(),original.ruleSetVersion(),original.fingerprint(),original.ruleDefinitions(),original.previousPaths(),original.instructionVersion(),original.model(),original.usage(),original.result());
    }
}
