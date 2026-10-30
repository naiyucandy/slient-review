package dev.silentreview.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Integration coverage for rule context -> AI payload -> validation -> persisted report reference. */
class RuleContextPipelineTest {
    @TempDir Path workspace;
    private String git(Path root,String...args)throws Exception{
        List<String> command=new ArrayList<>(List.of("git","-C",root.toString(),"-c","core.hooksPath="+workspace.resolve("no-hooks"),"-c","commit.gpgsign=false"));
        command.addAll(List.of(args));ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM","1");builder.environment().put("GIT_CONFIG_GLOBAL",workspace.resolve("no-global-config").toString());
        Process process=builder.start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),output);return output.strip();
    }
    private Path repository()throws Exception{
        Path root=Files.createDirectories(workspace.resolve("repository"));git(root,"init","-b","feature/test");
        git(root,"config","user.name","SilentReview Tests");git(root,"config","user.email","test@example.invalid");
        write(root,"README.md","fixture\n");commit(root);return root;
    }
    private void write(Path root,String name,String content)throws IOException{
        Path file=root.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,content,StandardCharsets.UTF_8);
    }
    private String commit(Path root)throws Exception{git(root,"add",".");git(root,"commit","-m","fixture");return git(root,"rev-parse","HEAD");}
    private GitRepository.ChangeSet changes(Path root)throws Exception{return new GitRepository(root,()->false).collect(git(root,"rev-parse","HEAD"),null);}
    private Rules.Snapshot rule(boolean explicit){
        JsonObject root=JsonParser.parseString(TestFixtures.RULES).getAsJsonObject();JsonObject rule=root.getAsJsonArray("rules").get(0).getAsJsonObject();
        rule.addProperty("id","CONFIG-42");rule.addProperty("title","New handlers require matching configuration");rule.addProperty("severity","error");
        rule.addProperty("requirement",explicit?"Every new handler must be declared in modules/{version}/handlers.json.":"Every new handler must be declared in handlers.json.");
        rule.getAsJsonObject("source").addProperty("excerpt","Handler configuration must include every new implementation.");
        rule.getAsJsonObject("appliesTo").add("include",JsonParser.parseString("[\"modules/{version}/code/*.java\"]"));
        rule.getAsJsonObject("appliesTo").add("exclude",new JsonArray());
        if(explicit)rule.add("contextFiles",JsonParser.parseString("[\"modules/{version}/handlers.json\"]"));
        return Rules.parse(root.toString());
    }
    private JsonArray contexts(Review.Request request){return JsonParser.parseString(request.json()).getAsJsonObject().getAsJsonArray("contextFiles");}
    private AiClient.Response reviewed(Review.Request request,List<Review.Finding> findings){
        JsonObject result=new JsonObject();result.add("findings",Review.JSON.toJsonTree(findings));
        result.add("coverage",Review.JSON.toJsonTree(request.requiredChecks().stream().map(c->new Review.Coverage(c.filePath(),c.ruleId(),"reviewed","Mock reviewed the supplied scope")).toList()));
        result.add("limitations",new JsonArray());return new AiClient.Response(result.toString(),"mock-config-review",null);
    }
    private Review.Finding missingHandler(Review.Request request){
        GitRepository.ChangedFile file=request.changes().files().get(0);
        return new Review.Finding("CONFIG-42","violation","error",file.path(),"after",1,1,file.after().split("\\R",-1)[0],
            "The frozen handlers.json does not declare this newly added handler.","Add its handler entry and commit the companion configuration change.","This class is newly introduced in the reviewed commit.");
    }
    @Test void unchangedGenericCompanionReachesAiAndItsEvidenceBasedFindingSurvives()throws Exception{
        Path root=repository();write(root,"modules/v1/handlers.json","{\"handlers\":[\"existing\"]}\n");commit(root);
        write(root,"modules/v1/code/NewHandler.java","class NewHandler {}\n");commit(root);AtomicInteger calls=new AtomicInteger();
        var outcome=new ReviewRunner().run(rule(true),changes(root),request->{
            calls.incrementAndGet();assertEquals(1,request.changes().files().size());assertEquals("modules/v1/code/NewHandler.java",request.changes().files().get(0).path());
            JsonObject related=contexts(request).get(0).getAsJsonObject();assertEquals("present",related.get("status").getAsString());
            assertEquals("modules/v1/handlers.json",related.get("path").getAsString());assertTrue(related.get("content").getAsString().startsWith("1: "));
            assertTrue(related.get("content").getAsString().contains("existing"));return reviewed(request,List.of(missingHandler(request)));
        },()->false,ignored->{});
        assertEquals(1,calls.get());assertEquals(Review.Status.COMPLETED,outcome.result().status());assertEquals(1,outcome.result().findings().size());
        assertEquals("CONFIG-42",outcome.result().findings().get(0).ruleId());assertEquals("class NewHandler {}",outcome.result().findings().get(0).evidence());
        assertEquals("reviewed",outcome.result().coverage().get(0).status());
        var report=Review.report(outcome.reference(),"Selected files",outcome.model(),null,outcome.result());
        assertTrue(report.scope().contains("modules/v1/handlers.json"));assertTrue(report.scope().contains("已读取"));
        assertEquals(Set.of("modules/v1/code/NewHandler.java"),report.previousPaths().keySet());
    }
    @Test void missingCompanionRemainsMissingDespiteUncommittedLocalRepairAndAiCanFlagIt()throws Exception{
        Path root=repository();write(root,"modules/v1/code/NewHandler.java","class NewHandler {}\n");commit(root);
        write(root,"modules/v1/handlers.json","{\"handlers\":[\"NewHandler\"]}\n");AtomicInteger calls=new AtomicInteger();
        var outcome=new ReviewRunner().run(rule(true),changes(root),request->{
            calls.incrementAndGet();JsonObject related=contexts(request).get(0).getAsJsonObject();
            assertEquals("missing",related.get("status").getAsString());assertEquals("",related.get("content").getAsString());
            assertTrue(JsonParser.parseString(request.json()).getAsJsonObject().getAsJsonArray("incompleteContextChecks").isEmpty());
            return reviewed(request,List.of(missingHandler(request)));
        },()->false,ignored->{});
        assertEquals(1,calls.get());assertEquals(Review.Status.COMPLETED,outcome.result().status());assertEquals(1,outcome.result().findings().size());
        assertTrue(Review.report(outcome.reference(),"Selected files",outcome.model(),null,outcome.result()).scope().contains("提交中缺失"));
    }
    @Test void sharedContextIsReattachedForBOnlyRequestAndForEachActualAiBatch()throws Exception{
        Path root=repository();String shared="{\"handlers\":[\"A\",\"B\"]}\n";write(root,"modules/v1/handlers.json",shared);commit(root);
        write(root,"modules/v1/code/A.java","class A { /*"+"a".repeat(30_000)+"*/ }\n");
        write(root,"modules/v1/code/B.java","class B { /*"+"b".repeat(30_000)+"*/ }\n");commit(root);
        var rules=rule(true);var changes=changes(root);var context=RuleContext.capture(rules,changes,()->false);
        assertEquals(2,context.entries().size());assertFalse(context.entries().get(0).content().isEmpty());assertTrue(context.entries().get(1).content().isEmpty());
        var b=changes.files().stream().filter(file->file.path().endsWith("/B.java")).findFirst().orElseThrow();
        var bOnly=new GitRepository.ChangeSet(changes.root(),changes.branch(),changes.base(),changes.target(),List.of(b),List.of());
        Review.Request requestB=Review.request(rules,bOnly,context);
        assertEquals(1,contexts(requestB).size());assertEquals(Review.numbered(shared),contexts(requestB).get(0).getAsJsonObject().get("content").getAsString());
        assertEquals(Review.Status.COMPLETED,Review.validate(requestB,reviewed(requestB,List.of()).content()).status());
        AtomicInteger calls=new AtomicInteger();Set<String> checked=new HashSet<>();
        var outcome=new ReviewRunner().run(rules,changes,request->{
            calls.incrementAndGet();assertEquals(1,request.changes().files().size());checked.add(request.changes().files().get(0).path());
            assertEquals(Review.numbered(shared),contexts(request).get(0).getAsJsonObject().get("content").getAsString());return reviewed(request,List.of());
        },()->false,ignored->{});
        assertEquals(2,calls.get());assertEquals(Set.of("modules/v1/code/A.java","modules/v1/code/B.java"),checked);
        assertEquals(Review.Status.COMPLETED,outcome.result().status());assertEquals(2,outcome.result().coverage().size());
    }
    @Test void ambiguousContextForcesNeedsContextEvenWhenAiClaimsReviewed()throws Exception{
        Path root=repository();write(root,"modules/v1/a/handlers.json","{\"a\":true}\n");write(root,"modules/v1/b/handlers.json","{\"b\":true}\n");commit(root);
        write(root,"modules/v1/code/NewHandler.java","class NewHandler {}\n");commit(root);AtomicInteger calls=new AtomicInteger();
        var outcome=new ReviewRunner().run(rule(false),changes(root),request->{
            calls.incrementAndGet();assertEquals("ambiguous",contexts(request).get(0).getAsJsonObject().get("status").getAsString());
            assertEquals(1,JsonParser.parseString(request.json()).getAsJsonObject().getAsJsonArray("incompleteContextChecks").size());return reviewed(request,List.of());
        },()->false,ignored->{});
        assertEquals(1,calls.get());assertEquals(Review.Status.PARTIAL,outcome.result().status());assertEquals("needs_context",outcome.result().coverage().get(0).status());
        assertFalse(outcome.result().limitations().isEmpty());
    }
    @Test void unavailableContextForcesNeedsContextEvenWhenAiClaimsReviewed()throws Exception{
        Path root=repository();Path companion=root.resolve("modules/v1/handlers.json");Files.createDirectories(companion.getParent());Files.write(companion,new byte[]{'{',0,'}'});commit(root);
        write(root,"modules/v1/code/NewHandler.java","class NewHandler {}\n");commit(root);AtomicInteger calls=new AtomicInteger();
        var outcome=new ReviewRunner().run(rule(true),changes(root),request->{
            calls.incrementAndGet();assertEquals("unavailable",contexts(request).get(0).getAsJsonObject().get("status").getAsString());
            assertEquals(1,JsonParser.parseString(request.json()).getAsJsonObject().getAsJsonArray("incompleteContextChecks").size());return reviewed(request,List.of());
        },()->false,ignored->{});
        assertEquals(1,calls.get());assertEquals(Review.Status.PARTIAL,outcome.result().status());assertEquals("needs_context",outcome.result().coverage().get(0).status());
        assertTrue(Review.report(outcome.reference(),"Selected files",outcome.model(),null,outcome.result()).scope().contains("未能读取"));
    }
    @Test void sourceAndCompanionShareOneRequestBudgetRatherThanIndependentAllowances()throws Exception{
        Path root=repository();String companion="{\"padding\":\""+"c".repeat(25_000)+"\"}\n";
        write(root,"modules/v1/handlers.json",companion);write(root,"modules/v1/code/LargeHandler.java","class LargeHandler { /*"+"a".repeat(25_000)+"*/ }\n");commit(root);
        write(root,"modules/v1/code/LargeHandler.java","class LargeHandler { /*"+"b".repeat(25_000)+"*/ }\n");commit(root);
        var rules=rule(true);var changes=changes(root);var file=changes.files().get(0);
        int sourceCost=file.before().length()+file.after().length()+file.diff().length();int contextCost=RuleContext.capture(rules,changes,()->false).characters();
        assertTrue(sourceCost<ReviewRunner.MAX_SOURCE_CHARACTERS);assertTrue(contextCost<ReviewRunner.MAX_CONTEXT_CHARACTERS);
        assertTrue(sourceCost+contextCost>ReviewRunner.MAX_SOURCE_CHARACTERS,"Fixture must fit separately but exceed the single combined request allowance");
        AtomicInteger calls=new AtomicInteger();var outcome=new ReviewRunner().run(rules,changes,request->{calls.incrementAndGet();return reviewed(request,List.of());},()->false,ignored->{});
        assertEquals(0,calls.get(),"The oversize source/context combination must never be sent to the AI");
        assertEquals(Review.Status.PARTIAL,outcome.result().status());assertTrue(outcome.result().limitations().stream().anyMatch(reason->reason.contains("预算")));
        assertEquals(1,outcome.reference().requiredChecks().size());
        assertEquals(1,outcome.result().coverage().size());assertEquals("skipped",outcome.result().coverage().get(0).status());
    }
}
