package dev.silentreview.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FindingResolutionTest {
    @TempDir Path directory;

    @Test void singleFindingProgressSurvivesReloadWithoutRewritingReportEvidence() throws Exception {
        var report=report();Reports store=new Reports(directory);store.save(report);
        byte[] original=Files.readAllBytes(reportFile(report));
        var first=store.acknowledgeFinding(report.id(),1,true," 已手动修复 "," alice ");
        Reports reopened=new Reports(directory);
        var states=reopened.loadFindingResolutions().get(report.id());
        assertEquals(first,states.get(1));assertTrue(first.confirmed());assertEquals(1,first.findingIndex());
        assertEquals("已手动修复",first.note());assertEquals("alice",first.actor());
        assertEquals(64,first.findingKey().length());assertEquals(1,first.history().size());
        assertEquals(1,Reports.confirmedFindingCount(report,states));
        assertNull(Reports.findingResolution(report,0,states));
        assertEquals(Reports.Decision.UNREVIEWED,Reports.resolution(report.id(),reopened.loadResolutions()).decision());
        assertArrayEquals(original,Files.readAllBytes(reportFile(report)));
        assertEquals(Review.Status.PARTIAL,reopened.load().get(0).result().status());
        assertThrows(UnsupportedOperationException.class,()->states.clear());
        assertThrows(UnsupportedOperationException.class,()->first.history().clear());
    }

    @Test void finalConfirmationAndRevocationUpdateTaskDecisionAndAuditInTheSameFile() throws Exception {
        var report=report();Reports store=new Reports(directory);store.save(report);
        store.acknowledgeFinding(report.id(),1,true,"修复第二项","alice");
        store.acknowledgeFinding(report.id(),0,true,"修复第一项","bob");
        var complete=new Reports(directory).loadResolutions().get(report.id());
        assertEquals(Reports.Decision.CONFIRMED,complete.decision());
        assertEquals("bob",complete.actor());assertTrue(complete.note().contains("全部 2 项问题"));
        JsonObject saved=Review.JSON.fromJson(Files.readString(directory.resolve("resolutions.json")),JsonObject.class);
        assertEquals(2,saved.get("schemaVersion").getAsInt());
        assertEquals("CONFIRMED",saved.getAsJsonObject("resolutions").getAsJsonObject(report.id()).get("decision").getAsString());
        assertTrue(saved.getAsJsonObject("findingResolutions").getAsJsonObject(report.id()).getAsJsonObject("0").get("confirmed").getAsBoolean());

        store.acknowledgeFinding(report.id(),1,false,"需重新处理","alice");
        Reports reopened=new Reports(directory);
        var decision=reopened.loadResolutions().get(report.id());
        var finding=reopened.loadFindingResolutions().get(report.id()).get(1);
        assertEquals(Reports.Decision.UNREVIEWED,decision.decision());assertTrue(decision.note().contains("已撤销"));
        assertEquals(List.of(Reports.Decision.CONFIRMED,Reports.Decision.UNREVIEWED),decision.history().stream().map(Reports.AuditEvent::decision).toList());
        assertEquals(List.of(true,false),finding.history().stream().map(Reports.FindingAuditEvent::confirmed).toList());
        assertEquals(finding.updatedAt(),decision.updatedAt());assertFalse(finding.confirmed());
        assertEquals(1,Reports.confirmedFindingCount(report,reopened.loadFindingResolutions().get(report.id())));
    }

    @Test void revokingFindingPreservesAcceptedAndClosedTaskDecisions() throws Exception {
        Reports store=new Reports(directory);
        for(Reports.Decision decision:List.of(Reports.Decision.ACCEPTED,Reports.Decision.CLOSED)) {
            var report=report();store.save(report);
            store.acknowledgeFinding(report.id(),0,true,"","alice");
            store.acknowledgeFinding(report.id(),1,true,"","alice");
            store.acknowledge(report.id(),decision,"独立人工处理状态","bob");
            store.acknowledgeFinding(report.id(),0,false,"重新处理一项","alice");
            assertEquals(decision,store.loadResolutions().get(report.id()).decision());
            assertEquals(1,Reports.confirmedFindingCount(report,store.loadFindingResolutions().get(report.id())));
        }
    }

    @Test void finalFindingConfirmationPreservesAcceptedTaskDecision() throws Exception {
        assertFinalConfirmationPreservesDecision(Reports.Decision.ACCEPTED);
    }

    @Test void finalFindingConfirmationPreservesClosedTaskDecision() throws Exception {
        assertFinalConfirmationPreservesDecision(Reports.Decision.CLOSED);
    }

    @Test void legacyOverallConfirmationBecomesPendingUntilAllFindingsAreIndividuallyConfirmed() throws Exception {
        var report=report();Reports store=new Reports(directory);store.save(report);
        String at="2026-10-28T01:00:00Z";
        var legacy=new Reports.Resolution(report.id(),Reports.Decision.CONFIRMED,at,"legacy","旧版整体确认",
            List.of(new Reports.AuditEvent(Reports.Decision.CONFIRMED,at,"legacy","旧版整体确认")));
        JsonObject saved=new JsonObject();saved.addProperty("schemaVersion",1);
        saved.add("resolutions",Review.JSON.toJsonTree(Map.of(report.id(),legacy)));
        Files.writeString(directory.resolve("resolutions.json"),Review.JSON.toJson(saved));

        assertTrue(store.loadFindingResolutions().isEmpty());
        assertEquals(Reports.Decision.CONFIRMED,store.loadResolutions().get(report.id()).decision());
        store.acknowledgeFinding(report.id(),1,true,"第二项已修复","alice");
        assertEquals(Reports.Decision.UNREVIEWED,store.loadResolutions().get(report.id()).decision());
        assertEquals(1,Reports.confirmedFindingCount(report,store.loadFindingResolutions().get(report.id())));
        assertNull(Reports.findingResolution(report,0,store.loadFindingResolutions().get(report.id())));

        store.acknowledgeFinding(report.id(),0,true,"首项已修复","bob");
        Reports reopened=new Reports(directory);
        assertEquals(2,Reports.confirmedFindingCount(report,reopened.loadFindingResolutions().get(report.id())));
        assertEquals(List.of(Reports.Decision.CONFIRMED,Reports.Decision.UNREVIEWED,Reports.Decision.CONFIRMED),
            reopened.loadResolutions().get(report.id()).history().stream().map(Reports.AuditEvent::decision).toList());
    }

    @Test void schemaOneManualDecisionIsPreservedAndDoesNotFabricatePerFindingConfirmations() throws Exception {
        var report=report();Reports store=new Reports(directory);store.save(report);
        String at="2026-10-28T01:00:00Z";
        var legacy=new Reports.Resolution(report.id(),Reports.Decision.CLOSED,at,"legacy","旧版确认",
            List.of(new Reports.AuditEvent(Reports.Decision.CLOSED,at,"legacy","旧版确认")));
        JsonObject saved=new JsonObject();saved.addProperty("schemaVersion",1);
        saved.add("resolutions",Review.JSON.toJsonTree(Map.of(report.id(),legacy)));
        Files.writeString(directory.resolve("resolutions.json"),Review.JSON.toJson(saved));

        assertTrue(store.loadFindingResolutions().isEmpty());assertEquals(legacy,store.loadResolutions().get(report.id()));
        store.acknowledgeFinding(report.id(),0,true,"第一项修复","alice");
        var reloaded=new Reports(directory);
        assertEquals(legacy,reloaded.loadResolutions().get(report.id()));
        assertEquals(1,Reports.confirmedFindingCount(report,reloaded.loadFindingResolutions().get(report.id())));
        store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"整体确认不伪造逐项确认","bob");
        assertEquals(1,store.loadFindingResolutions().get(report.id()).size());
        assertNull(Reports.findingResolution(report,1,store.loadFindingResolutions().get(report.id())));
        assertEquals(2,store.loadResolutions().get(report.id()).history().size());
    }

    @Test void originalIndicesAndContentKeysPreventStaleConfirmationAfterRewriteOrReorder() throws Exception {
        var report=report();Reports store=new Reports(directory);store.save(report);
        var first=store.acknowledgeFinding(report.id(),0,true,"原问题修复","alice");
        var second=store.acknowledgeFinding(report.id(),1,true,"第二项修复","alice");
        var states=store.loadFindingResolutions().get(report.id());
        var changed=withFindings(report,List.of(finding("src/Z.java","新的原因",9),report.result().findings().get(1)));
        Files.writeString(reportFile(report),Review.JSON.toJson(changed));
        assertNull(Reports.findingResolution(changed,0,states));
        assertEquals(second,Reports.findingResolution(changed,1,states));
        assertEquals(1,store.loadFindingResolutions().get(report.id()).size());
        var newlyConfirmed=store.acknowledgeFinding(report.id(),0,true,"新的问题单独确认","bob");
        assertNotEquals(first.findingKey(),newlyConfirmed.findingKey());assertEquals(1,newlyConfirmed.history().size());
        assertEquals("bob",newlyConfirmed.history().get(0).actor());

        var reordered=withFindings(changed,List.of(changed.result().findings().get(1),changed.result().findings().get(0)));
        Files.writeString(reportFile(report),Review.JSON.toJson(reordered));
        assertFalse(store.loadFindingResolutions().containsKey(report.id()));
        assertEquals(0,Reports.confirmedFindingCount(reordered,store.loadFindingResolutions().get(report.id())));
        assertNull(Reports.findingResolution(report,-1,states));assertNull(Reports.findingResolution(report,2,states));
        assertNull(Reports.findingResolution(report,0,Map.of(0,new Reports.FindingResolution(UUID.randomUUID().toString(),0,
            first.findingKey(),true,first.updatedAt(),first.actor(),first.note(),first.history()))));
    }

    @Test void savingChangedReportPrunesInvalidFindingStatesAndReopensConfirmedTask() throws Exception {
        var report=report();Reports store=new Reports(directory);store.save(report);
        store.acknowledgeFinding(report.id(),0,true,"","alice");store.acknowledgeFinding(report.id(),1,true,"","alice");
        var changed=withFindings(report,List.of(finding("src/A.java","原因已经更新",2),report.result().findings().get(1)));
        store.save(changed);
        assertEquals(Reports.Decision.UNREVIEWED,store.loadResolutions().get(report.id()).decision());
        assertEquals(1,store.loadFindingResolutions().get(report.id()).size());
        String raw=Files.readString(directory.resolve("resolutions.json"));
        assertFalse(Review.JSON.fromJson(raw,JsonObject.class).getAsJsonObject("findingResolutions").getAsJsonObject(report.id()).has("0"));
        assertTrue(store.loadResolutions().get(report.id()).note().contains("内容发生变化"));
    }

    @Test void addingAnUnconfirmedFindingReopensTaskWhilePreservingExistingConfirmations() throws Exception {
        var report=report();Reports store=new Reports(directory);store.save(report);
        var first=store.acknowledgeFinding(report.id(),0,true,"首项已修复","alice");
        var second=store.acknowledgeFinding(report.id(),1,true,"第二项已修复","bob");
        assertEquals(Reports.Decision.CONFIRMED,store.loadResolutions().get(report.id()).decision());
        List<Review.Finding> expanded=new ArrayList<>(report.result().findings());
        expanded.add(finding("src/C.java","新增问题",10));
        var changed=withFindings(report,expanded);store.save(changed);

        Reports reopened=new Reports(directory);
        var states=reopened.loadFindingResolutions().get(report.id());
        assertEquals(first,states.get(0));assertEquals(second,states.get(1));
        assertNull(Reports.findingResolution(changed,2,states));
        assertEquals(2,Reports.confirmedFindingCount(changed,states));
        var resolution=reopened.loadResolutions().get(report.id());
        assertEquals(Reports.Decision.UNREVIEWED,resolution.decision());
        assertEquals("system",resolution.actor());assertTrue(resolution.note().contains("未全部完成"));
        assertEquals(List.of(Reports.Decision.CONFIRMED,Reports.Decision.UNREVIEWED),resolution.history().stream().map(Reports.AuditEvent::decision).toList());
    }

    @Test void addingFindingDoesNotReplaceLegacyManualConfirmationWithoutPerFindingStates() throws Exception {
        var report=report();Reports store=new Reports(directory);store.save(report);
        var manual=store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"旧版整体人工确认","alice");
        List<Review.Finding> expanded=new ArrayList<>(report.result().findings());
        expanded.add(finding("src/C.java","新增问题",10));
        store.save(withFindings(report,expanded));
        assertEquals(manual,new Reports(directory).loadResolutions().get(report.id()));
        assertTrue(store.loadFindingResolutions().isEmpty());
    }

    @Test void invalidOrMissingFindingCannotCreateProcessingRecords() throws Exception {
        var report=report();Reports store=new Reports(directory);store.save(report);
        assertThrows(IllegalArgumentException.class,()->store.acknowledgeFinding("../escape",0,true,"","tester"));
        assertThrows(IllegalArgumentException.class,()->store.acknowledgeFinding(UUID.randomUUID().toString(),0,true,"","tester"));
        for(int index:List.of(-1,2,999))assertThrows(IllegalArgumentException.class,()->store.acknowledgeFinding(report.id(),index,true,"","tester"));
        assertFalse(Files.exists(directory.resolve("resolutions.json")));
        assertThrows(IllegalArgumentException.class,()->store.acknowledgeFinding(report.id(),0,true,"x".repeat(10_001),"tester"));
        assertThrows(IllegalArgumentException.class,()->store.acknowledgeFinding(report.id(),0,true,"","x".repeat(257)));
    }

    @Test void evictionAndClearRemovePerFindingStateAlongWithTaskHistory() throws Exception {
        var oldest=report();Reports store=new Reports(directory);store.save(oldest);
        store.acknowledgeFinding(oldest.id(),0,true,"","alice");store.acknowledgeFinding(oldest.id(),1,true,"","alice");
        Files.setLastModifiedTime(reportFile(oldest),FileTime.fromMillis(1));
        for(int i=0;i<99;i++) {var other=report();Files.writeString(reportFile(other),Review.JSON.toJson(other));}
        var newest=report();store.save(newest);
        assertEquals(100,store.load().size());assertFalse(Files.exists(reportFile(oldest)));
        assertFalse(store.loadResolutions().containsKey(oldest.id()));assertFalse(store.loadFindingResolutions().containsKey(oldest.id()));
        assertFalse(Files.readString(directory.resolve("resolutions.json")).contains(oldest.id()));
        store.acknowledgeFinding(newest.id(),0,true,"","alice");
        Files.writeString(directory.resolve("notes.json"),"keep");store.clear();
        assertTrue(store.loadFindingResolutions().isEmpty());assertTrue(store.loadResolutions().isEmpty());
        assertFalse(Files.exists(directory.resolve("resolutions.json")));assertEquals("keep",Files.readString(directory.resolve("notes.json")));
    }

    @Test void twoInstancesDoNotLoseFindingOrTaskAuditEvents() throws Exception {
        var report=report();Reports first=new Reports(directory),second=new Reports(directory);first.save(report);
        var workers=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<24;i++) {int event=i;tasks.add(workers.submit(()->{
                try {
                    if(event%2==0)first.acknowledgeFinding(report.id(),0,true,"finding-"+event,"tester");
                    else second.acknowledge(report.id(),Reports.Decision.UNREVIEWED,"task-"+event,"tester");
                } catch(Exception e) {throw new CompletionException(e);}
            }));}
            for(var task:tasks)task.get(15,TimeUnit.SECONDS);
        } finally {workers.shutdownNow();}
        Reports reopened=new Reports(directory);
        assertEquals(12,reopened.loadFindingResolutions().get(report.id()).get(0).history().size());
        assertEquals(12,reopened.loadResolutions().get(report.id()).history().size());
        try(var files=Files.list(directory)){assertFalse(files.anyMatch(path->path.toString().endsWith(".tmp")));}
    }

    @Test void damagedFindingAuditIsPreservedInsteadOfBeingOverwritten() throws Exception {
        var report=report();Reports store=new Reports(directory);store.save(report);
        store.acknowledgeFinding(report.id(),0,true,"真实记录","alice");
        Path state=directory.resolve("resolutions.json");
        JsonObject saved=Review.JSON.fromJson(Files.readString(state),JsonObject.class);
        saved.getAsJsonObject("findingResolutions").getAsJsonObject(report.id()).getAsJsonObject("0").addProperty("confirmed",false);
        String corrupted=Review.JSON.toJson(saved);Files.writeString(state,corrupted);
        assertThrows(IOException.class,store::loadFindingResolutions);
        assertThrows(IOException.class,()->store.acknowledgeFinding(report.id(),1,true,"","bob"));
        assertEquals(corrupted,Files.readString(state));
    }

    private Path reportFile(Review.Report report) {return directory.resolve(report.id()+".json");}

    private void assertFinalConfirmationPreservesDecision(Reports.Decision decision) throws Exception {
        var report=report();Reports store=new Reports(directory);store.save(report);
        store.acknowledgeFinding(report.id(),0,true,"首项已修复","alice");
        var manual=store.acknowledge(report.id(),decision,"保持独立人工处理决定","bob");
        store.acknowledgeFinding(report.id(),1,true,"最后一项已修复","alice");
        Reports reopened=new Reports(directory);
        assertEquals(2,Reports.confirmedFindingCount(report,reopened.loadFindingResolutions().get(report.id())));
        assertEquals(manual,reopened.loadResolutions().get(report.id()));
    }

    private static Review.Report report() {
        var request=TestFixtures.request();
        return Review.report(request,"single","test",null,new Review.Result(List.of(
            finding("src/A.java","首项原因",2),finding("src/B.java","第二项原因",5)),
            List.of(new Review.Coverage("src/A.java","RULE","needs_context","待补上下文")),List.of("仍有待补查项"),Review.Status.PARTIAL));
    }

    private static Review.Report withFindings(Review.Report original,List<Review.Finding> findings) {
        return new Review.Report(original.id(),original.createdAt(),original.root(),original.branch(),original.base(),original.target(),
            original.scope(),original.ruleSetId(),original.ruleSetName(),original.ruleSetVersion(),original.fingerprint(),original.ruleDefinitions(),
            original.previousPaths(),original.instructionVersion(),original.model(),original.usage(),
            new Review.Result(findings,original.result().coverage(),original.result().limitations(),original.result().status()));
    }

    private static Review.Finding finding(String path,String explanation,int line) {
        return new Review.Finding("RULE","violation","warning",path,"after",line,line,"source()",explanation,"修复建议","本次变更新增");
    }
}
