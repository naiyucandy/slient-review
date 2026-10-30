package dev.silentreview.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuleContextTest {
    @TempDir Path workspace;
    private String git(Path root,String...args)throws Exception{
        List<String> command=new ArrayList<>(List.of("git","-C",root.toString(),"-c","core.hooksPath="+workspace.resolve("no-hooks"),"-c","commit.gpgsign=false"));
        command.addAll(List.of(args));ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_NOSYSTEM","1");builder.environment().put("GIT_CONFIG_GLOBAL",workspace.resolve("no-global-config").toString());
        Process process=builder.start();String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),output);return output.strip();
    }
    private Path repository(String name)throws Exception{
        Path root=Files.createDirectories(workspace.resolve(name));git(root,"init","-b","feature/test");
        git(root,"config","user.name","SilentReview Tests");git(root,"config","user.email","test@example.invalid");
        write(root,"README.md","fixture\n");commit(root);return root;
    }
    private void write(Path root,String name,String content)throws IOException{
        Path path=root.resolve(name);Files.createDirectories(path.getParent());Files.writeString(path,content,StandardCharsets.UTF_8);
    }
    private String commit(Path root)throws Exception{git(root,"add",".");git(root,"commit","-m","fixture");return git(root,"rev-parse","HEAD");}
    private GitRepository.ChangeSet changes(Path root)throws Exception{return new GitRepository(root,()->false).collect(git(root,"rev-parse","HEAD"),null);}
    private Rules.Snapshot rules(String include,String requirement,String...contexts){
        JsonObject root=JsonParser.parseString(TestFixtures.RULES).getAsJsonObject(),rule=root.getAsJsonArray("rules").get(0).getAsJsonObject();
        rule.addProperty("id","LINK-1");rule.addProperty("requirement",requirement);JsonArray includes=new JsonArray();includes.add(include);
        rule.getAsJsonObject("appliesTo").add("include",includes);rule.getAsJsonObject("appliesTo").add("exclude",new JsonArray());
        if(contexts.length>0){JsonArray paths=new JsonArray();for(String path:contexts)paths.add(path);rule.add("contextFiles",paths);}
        return Rules.parse(root.toString());
    }
    @Test void declaredContextUsesTargetCommitEvenWhenUnchangedAndWorkingTreeDiffers()throws Exception{
        Path root=repository("module");write(root,"modules/1.0/registry.txt","--0. Entries\nRUN-10\n");commit(root);
        write(root,"modules/1.0/code/change.sql","select 1;\n");commit(root);var changes=changes(root);
        write(root,"modules/1.0/registry.txt","--0. Entries\nRUN-10\nRUN-20\n");
        var context=RuleContext.capture(rules("modules/{version}/code/**","Check related registration","modules/{version}/registry.txt"),changes,()->false);
        assertEquals(1,context.entries().size());var entry=context.entries().get(0);
        assertEquals("present",entry.status());assertEquals("modules/1.0/registry.txt",entry.path());
        assertEquals("1: --0. Entries\n2: RUN-10\n3: \n",entry.content());assertFalse(entry.content().contains("RUN-20"));assertTrue(context.incompleteChecks().isEmpty());
    }
    @Test void committedMissingContextIsNotConcealedByLocalUncommittedFile()throws Exception{
        Path root=repository("module");write(root,"modules/1.0/code/change.sql","select 1;\n");commit(root);
        write(root,"modules/1.0/registry.txt","RUN-20\n");
        var context=RuleContext.capture(rules("modules/{version}/code/**","Related file must declare the change","modules/{version}/registry.txt"),changes(root),()->false);
        assertEquals("missing",context.entries().get(0).status());assertEquals("",context.entries().get(0).content());
        assertTrue(context.incompleteChecks().isEmpty(),"The AI judges a genuinely missing companion against the rule; the context itself is known");
    }
    @Test void genericLegacyPlaceholderAndDuplicateFilenameMentionResolveSameVersionOnly()throws Exception{
        Path root=repository("module");write(root,"modules/2.4/registry.txt","RUN-20\n");write(root,"modules/3.0/registry.txt","other version\n");commit(root);
        write(root,"modules/2.4/code/change.sql","select 1;\n");commit(root);
        JsonObject json=JsonParser.parseString(rules("modules/xxx/code/**","Check modules/xxx/registry.txt.").json()).getAsJsonObject();
        json.getAsJsonArray("rules").get(0).getAsJsonObject().getAsJsonObject("source").addProperty("excerpt","Must appear in registry.txt.");
        var context=RuleContext.capture(Rules.parse(json.toString()),changes(root),()->false);
        assertEquals(1,context.entries().size());assertEquals("modules/2.4/registry.txt",context.entries().get(0).path());assertEquals("present",context.entries().get(0).status());
        assertTrue(context.incompleteChecks().isEmpty());
    }
    @Test void inferredFilenameSearchStaysWithinCapturedModuleVersion()throws Exception{
        Path root=repository("module");write(root,"modules/1.0/registry.json","{\"current\":true}\n");write(root,"modules/2.0/registry.json","{\"other\":true}\n");commit(root);
        write(root,"modules/1.0/code/sub/change.sql","select 1;\n");commit(root);
        var context=RuleContext.capture(rules("modules/{version}/code/**","Compare against registry.json."),changes(root),()->false);
        assertEquals("present",context.entries().get(0).status());assertEquals("modules/1.0/registry.json",context.entries().get(0).path());assertFalse(context.entries().get(0).content().contains("other"));
    }
    @Test void unrelatedSameBasenameCandidatesAreAmbiguousRatherThanAssumedCorrect()throws Exception{
        Path root=repository("module");write(root,"modules/1.0/a/registry.json","a\n");write(root,"modules/1.0/b/registry.json","b\n");commit(root);
        write(root,"modules/1.0/code/change.sql","select 1;\n");commit(root);
        var context=RuleContext.capture(rules("modules/{version}/code/**","Compare registry.json."),changes(root),()->false);
        assertEquals("ambiguous",context.entries().get(0).status());assertEquals(2,context.entries().get(0).candidates().size());
        assertEquals(Set.of(new Review.Check("modules/1.0/code/change.sql","LINK-1")),context.incompleteChecks());
    }
    @Test void explicitDeclarationsOverrideLessPreciseTextInference()throws Exception{
        Path root=repository("module");write(root,"modules/1.0/a/registry.json","a\n");write(root,"modules/1.0/b/registry.json","b\n");commit(root);
        write(root,"modules/1.0/code/change.sql","select 1;\n");commit(root);
        var context=RuleContext.capture(rules("modules/{version}/code/**","Compare registry.json.","modules/{version}/a/registry.json"),changes(root),()->false);
        assertEquals(1,context.entries().size());assertEquals("present",context.entries().get(0).status());assertEquals("modules/1.0/a/registry.json",context.entries().get(0).path());
    }
    @Test void scopedGlobSuppliesAllBoundedCompanionContents()throws Exception{
        Path root=repository("module");write(root,"modules/1.0/config/a.json","a\n");write(root,"modules/1.0/config/b.json","b\n");commit(root);
        write(root,"modules/1.0/code/change.sql","select 1;\n");commit(root);
        var context=RuleContext.capture(rules("modules/{version}/code/**","Compare declared configs","modules/{version}/config/*.json"),changes(root),()->false);
        assertEquals(2,context.entries().size());assertTrue(context.entries().stream().allMatch(e->e.status().equals("present")));assertTrue(context.incompleteChecks().isEmpty());
    }
    @Test void sharedContextBudgetAndPayloadContentAreChargedOnlyOnce()throws Exception{
        Path root=repository("module");String text="a".repeat(200)+"\n";write(root,"modules/1.0/registry.txt",text);commit(root);
        write(root,"modules/1.0/code/first.sql","select 1;\n");write(root,"modules/1.0/code/second.sql","select 2;\n");commit(root);
        int cost=Review.numbered(text).length();
        var context=RuleContext.capture(rules("modules/{version}/code/**","Compare declared file","modules/{version}/registry.txt"),changes(root),()->false,cost);
        assertEquals(2,context.entries().size());assertTrue(context.entries().stream().allMatch(e->e.status().equals("present")));
        assertEquals(1,context.entries().stream().filter(e->!e.content().isEmpty()).count());assertEquals(cost,context.characters());assertTrue(context.incompleteChecks().isEmpty());
    }
    @Test void unavailableAndBudgetLimitedContextsCannotPretendToBeComplete()throws Exception{
        Path root=repository("module");write(root,"modules/1.0/registry.txt","some content\n");commit(root);
        write(root,"modules/1.0/code/change.sql","select 1;\n");commit(root);
        var context=RuleContext.capture(rules("modules/{version}/code/**","Compare declared file","modules/{version}/registry.txt"),changes(root),()->false,1);
        assertEquals("unavailable",context.entries().get(0).status());assertFalse(context.incompleteChecks().isEmpty());assertFalse(context.limitations().isEmpty());assertEquals(0,context.characters());
    }
    @Test void binaryContextAndBrokenRootAreUnavailableWithoutBlockingOtherRoot()throws Exception{
        Path good=repository("good");write(good,"modules/1.0/registry.txt","good\n");commit(good);write(good,"modules/1.0/code/change.sql","select 1;\n");commit(good);
        var rule=rules("modules/{version}/code/**","Compare declared file","modules/{version}/registry.txt");var goodChanges=changes(good);
        var broken=new GitRepository.ChangeSet(workspace.resolve("missing-root").toString(),goodChanges.branch(),goodChanges.base(),goodChanges.target(),goodChanges.files(),List.of());
        assertEquals("unavailable",RuleContext.capture(rule,broken,()->false).entries().get(0).status());
        assertEquals("present",RuleContext.capture(rule,goodChanges,()->false).entries().get(0).status());
        Files.write(good.resolve("modules/1.0/registry.txt"),new byte[]{1,0,2});write(good,"modules/1.0/code/change.sql","select 2;\n");commit(good);
        assertEquals("unavailable",RuleContext.capture(rule,changes(good),()->false).entries().get(0).status());
    }
    @Test void methodNamesAndTheChangedFilenameAreNotInventedCompanionFiles(){
        var request=TestFixtures.request();
        assertTrue(RuleContext.capture(request.rules(),request.changes(),()->false).entries().isEmpty());
        var self=rules("src/**/OrderService.java","OrderService.java must not call System.out.println.");
        assertTrue(RuleContext.capture(self,request.changes(),()->false).entries().isEmpty());
    }
    @Test void unboundTemplateAndRepositoryWideGlobAreVisibleIncompleteContext(){
        var request=TestFixtures.request();
        var unbound=RuleContext.capture(rules("src/**/OrderService.java","Compare config","configs/{version}/rules.json"),request.changes(),()->false);
        assertEquals("ambiguous",unbound.entries().get(0).status());assertFalse(unbound.incompleteChecks().isEmpty());
        var broad=RuleContext.capture(rules("src/**/OrderService.java","Compare config","**/rules.json"),request.changes(),()->false);
        assertEquals("unavailable",broad.entries().get(0).status());assertFalse(broad.incompleteChecks().isEmpty());
    }
    @Test void canceledContextCollectionStopsBeforeReadingGit(){
        var request=TestFixtures.request();assertThrows(java.util.concurrent.CancellationException.class,()->RuleContext.capture(request.rules(),request.changes(),()->true));
    }
}
