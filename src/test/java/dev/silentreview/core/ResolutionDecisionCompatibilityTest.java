package dev.silentreview.core;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResolutionDecisionCompatibilityTest {
    @TempDir Path directory;

    @Test void schemaOneUiPendingLoadsAsUnreviewedAndNormalizesOnlyWhenWriting()throws Exception{
        Reports store=new Reports(directory);Review.Report report=report();store.save(report);
        store.acknowledge(report.id(),Reports.Decision.UNREVIEWED,"原 UI 待处理","alice");
        JsonObject state=readState();state.addProperty("schemaVersion",1);state.remove("findingResolutions");
        JsonObject resolution=state.getAsJsonObject("resolutions").getAsJsonObject(report.id());markPending(resolution);
        String original=Review.JSON.toJson(state);Files.writeString(statePath(),original);
        Reports reopened=new Reports(directory);var loaded=reopened.loadResolutions().get(report.id());
        assertEquals(Reports.Decision.UNREVIEWED,loaded.decision());assertEquals(Reports.Decision.UNREVIEWED,loaded.history().get(0).decision());
        assertEquals("原 UI 待处理",loaded.note());assertEquals(original,Files.readString(statePath()));
        reopened.acknowledge(report.id(),Reports.Decision.CONFIRMED,"本版确认","bob");
        JsonObject updated=readState().getAsJsonObject("resolutions").getAsJsonObject(report.id());
        assertEquals("CONFIRMED",updated.get("decision").getAsString());
        assertEquals("UNREVIEWED",updated.getAsJsonArray("history").get(0).getAsJsonObject().get("decision").getAsString());
        assertEquals(2,readState().get("schemaVersion").getAsInt());
    }

    @Test void pendingAliasDoesNotCollapseLegacyTaskDecisionsOrFindingHistoryInSchemaTwo()throws Exception{
        Reports store=new Reports(directory);Review.Report pending=report(),accepted=report(),closed=report();
        for(var report:List.of(pending,accepted,closed))store.save(report);
        store.acknowledgeFinding(pending.id(),0,true,"问题已修复","alice");
        store.acknowledge(accepted.id(),Reports.Decision.ACCEPTED,"保留采纳状态","bob");
        store.acknowledge(closed.id(),Reports.Decision.CLOSED,"保留关闭状态","carol");
        JsonObject state=readState();JsonObject resolutions=state.getAsJsonObject("resolutions");
        markPending(resolutions.getAsJsonObject(pending.id()));
        JsonObject findingState=state.getAsJsonObject("findingResolutions").deepCopy();
        JsonObject originalAccepted=resolutions.getAsJsonObject(accepted.id()).deepCopy(),originalClosed=resolutions.getAsJsonObject(closed.id()).deepCopy();
        Files.writeString(statePath(),Review.JSON.toJson(state));
        Reports reopened=new Reports(directory);var decisions=reopened.loadResolutions();
        assertEquals(Reports.Decision.UNREVIEWED,decisions.get(pending.id()).decision());
        assertEquals(Reports.Decision.ACCEPTED,decisions.get(accepted.id()).decision());assertEquals(Reports.Decision.CLOSED,decisions.get(closed.id()).decision());
        assertEquals(1,Reports.confirmedFindingCount(pending,reopened.loadFindingResolutions().get(pending.id())));
        reopened.acknowledge(pending.id(),Reports.Decision.CONFIRMED,"任务已阅","dave");
        JsonObject updated=readState();assertEquals(findingState,updated.getAsJsonObject("findingResolutions"));
        assertEquals(originalAccepted,updated.getAsJsonObject("resolutions").getAsJsonObject(accepted.id()));
        assertEquals(originalClosed,updated.getAsJsonObject("resolutions").getAsJsonObject(closed.id()));
    }

    @Test void unknownDecisionStillRejectsWriteAndPreservesDamagedState()throws Exception{
        Reports store=new Reports(directory);Review.Report report=report();store.save(report);
        store.acknowledge(report.id(),Reports.Decision.UNREVIEWED,"","alice");
        JsonObject state=readState();JsonObject resolution=state.getAsJsonObject("resolutions").getAsJsonObject(report.id());
        resolution.addProperty("decision","UNKNOWN");String damaged=Review.JSON.toJson(state);Files.writeString(statePath(),damaged);
        assertThrows(IOException.class,store::loadResolutions);
        assertThrows(IOException.class,()->store.acknowledge(report.id(),Reports.Decision.CONFIRMED,"","bob"));
        assertEquals(damaged,Files.readString(statePath()));
    }

    private static void markPending(JsonObject resolution){
        resolution.addProperty("decision","PENDING");
        resolution.getAsJsonArray("history").get(resolution.getAsJsonArray("history").size()-1).getAsJsonObject().addProperty("decision","PENDING");
    }
    private Path statePath(){return directory.resolve("resolutions.json");}
    private JsonObject readState()throws IOException{return Review.JSON.fromJson(Files.readString(statePath()),JsonObject.class);}
    private static Review.Report report(){
        var request=TestFixtures.request();return Review.report(request,"test","test",null,Review.validate(request,TestFixtures.response(true).toString()));
    }
}
