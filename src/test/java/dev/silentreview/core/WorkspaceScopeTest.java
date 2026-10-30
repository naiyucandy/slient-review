package dev.silentreview.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceScopeTest {
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
        write(root,"src/OrderService.java","class OrderService {}\n");write(root,"README.md","documentation\n");
        git(root,"add",".");git(root,"commit","-m","initial");
        write(root,"src/OrderService.java","class OrderService { void save() { System.out.println(1); } }\n");
        write(root,"README.md","updated documentation\n");git(root,"add",".");git(root,"commit","-m","change");return root;
    }
    private void write(Path root,String name,String text)throws IOException{
        Path file=root.resolve(name);Files.createDirectories(file.getParent());Files.writeString(file,text,StandardCharsets.UTF_8);
    }
    private void sharedRules()throws IOException{write(workspace,".silent-review/rules.json",TestFixtures.RULES);}
    private WorkspaceScope.Prepared collect(List<Path> roots)throws IOException{
        return WorkspaceScope.collect(roots,workspace,".silent-review/rules.json",null,()->false);
    }
    @Test void collectsTwoActualGitRootsWithoutCrossContaminatingEqualPaths()throws Exception{
        sharedRules();Path first=repository("cable"),second=repository("hdpos46");
        var prepared=collect(List.of(first,second));
        assertEquals(2,prepared.state().repositories().size());assertEquals(2,prepared.state().includedCount());
        assertEquals(4,prepared.state().fileCount());assertEquals(2,prepared.state().excludedCount());
        assertEquals(2,prepared.tasks().size());
        for(var task:prepared.tasks()){
            assertEquals(List.of("src/OrderService.java"),task.changes().files().stream().map(GitRepository.ChangedFile::path).toList());
            assertTrue(task.changes().files().get(0).after().contains("System.out.println"));
        }
        var onlySecond=prepared.include(first.toString(),"src/OrderService.java",false);
        assertEquals(1,onlySecond.tasks().size());assertEquals(second.toString(),onlySecond.tasks().get(0).changes().root());
        assertEquals(2,prepared.tasks().size(),"Selection edits must not mutate the original snapshot");
    }
    @Test void brokenRootDoesNotStopHealthyRepository()throws Exception{
        sharedRules();Path healthy=repository("latin"),broken=Files.createDirectories(workspace.resolve("broken"));
        var prepared=collect(List.of(broken,healthy));
        assertEquals(1,prepared.state().failedRepositoryCount());assertEquals(1,prepared.tasks().size());
        assertFalse(prepared.state().repositories().get(0).error().isBlank());assertEquals(healthy.toString(),prepared.tasks().get(0).changes().root());
    }
    @Test void resolvesEachRepositoryRulesBeforeProjectFallbackAndSharesAbsoluteRules()throws Exception{
        sharedRules();Path first=repository("cable"),second=repository("latin");
        write(first,".silent-review/rules.json",TestFixtures.RULES.replace("erp-test","cable-local"));
        var prepared=collect(List.of(first,second));
        assertEquals(List.of("cable-local","erp-test"),prepared.tasks().stream().map(t->t.rules().id()).toList());
        var shared=WorkspaceScope.collect(List.of(first,second),workspace,workspace.resolve(".silent-review/rules.json").toString(),null,()->false);
        assertEquals(List.of("erp-test","erp-test"),shared.tasks().stream().map(t->t.rules().id()).toList());
    }
    @Test void invalidLocalRulesAreVisibleAndNeverSilentlyReplacedByFallback()throws Exception{
        sharedRules();Path first=repository("cable"),second=repository("latin");write(first,".silent-review/rules.json","{}");
        var prepared=collect(List.of(first,second));
        assertEquals(1,prepared.state().failedRepositoryCount());assertEquals(1,prepared.tasks().size());
        var failed=prepared.state().repositories().get(0);assertEquals(2,failed.files().size());
        assertTrue(failed.files().stream().noneMatch(WorkspaceScope.File::selectable));
    }
    @Test void missingBaselineInOneRootDoesNotPreventOtherRootRange()throws Exception{
        sharedRules();Path first=repository("cable"),second=repository("latin");git(first,"branch","main");
        var prepared=WorkspaceScope.collect(List.of(first,second),workspace,".silent-review/rules.json","main",()->false);
        assertEquals(2,prepared.state().repositories().size());assertEquals(1,prepared.state().failedRepositoryCount());
        assertEquals(first.toString(),prepared.state().repositories().get(0).root());
        assertTrue(prepared.state().repositories().get(0).files().isEmpty());
    }
    @Test void excludedFilesNeverEnterAiRequestOrReportScope()throws Exception{
        sharedRules();Path root=repository("hdpos46");
        write(root,"src/sub/OrderService.java","class OrderService { void run() { System.out.println(2); } }\n");
        git(root,"add",".");git(root,"commit","-m","add second service");
        // Review against the initial commit so both rule-matched files participate.
        String initial=git(root,"rev-list","--max-parents=0","HEAD");
        var prepared=WorkspaceScope.collect(List.of(root),workspace,".silent-review/rules.json",initial,()->false)
            .include(root.toString(),"src/OrderService.java",false);
        var task=prepared.tasks().get(0);List<Review.Request> received=new ArrayList<>();
        var outcome=new ReviewRunner().run(task.rules(),task.changes(),request->{
            received.add(request);JsonObject response=new JsonObject();response.add("findings",new JsonArray());
            response.add("coverage",Review.JSON.toJsonTree(request.requiredChecks().stream().map(c->new Review.Coverage(c.filePath(),c.ruleId(),"reviewed","")).toList()));
            response.add("limitations",new JsonArray());return new AiClient.Response(Review.JSON.toJson(response),"test",null);
        },()->false,ignored->{});
        assertEquals(1,received.size());assertEquals(List.of("src/sub/OrderService.java"),received.get(0).changes().files().stream().map(GitRepository.ChangedFile::path).toList());
        assertFalse(received.get(0).json().contains("documentation"));
        Review.Report report=Review.report(outcome.reference(),task.description(),outcome.model(),null,outcome.result());
        assertEquals(Set.of("src/sub/OrderService.java"),report.previousPaths().keySet());
        assertEquals(1,report.result().coverage().size());assertTrue(report.scope().contains("用户纳入 1 个文件"));
    }
    @Test void includeAllCannotEnableUnmatchedFilesAndExcludeAllClearsActualTasks()throws Exception{
        sharedRules();var prepared=collect(List.of(repository("latin")));
        assertEquals(1,prepared.includeAll(true).state().includedCount());
        assertTrue(prepared.state().repositories().get(0).files().stream().filter(f->f.path().equals("README.md")).noneMatch(WorkspaceScope.File::selectable));
        var excluded=prepared.includeAll(false);assertFalse(excluded.state().ready());assertTrue(excluded.tasks().isEmpty());
        assertEquals(1,excluded.includeAll(true).tasks().size());
    }
    @Test void headChangeRequiresRefreshButWorkingTreeEditsCannotAlterSnapshot()throws Exception{
        sharedRules();Path root=repository("hdpos46");var prepared=collect(List.of(root));
        write(root,"src/OrderService.java","uncommitted working tree\n");
        WorkspaceScope.validateCurrent(prepared.tasks(),()->false);
        assertFalse(prepared.tasks().get(0).changes().files().get(0).after().contains("uncommitted"));
        git(root,"add",".");git(root,"commit","-m","next commit");
        assertThrows(IOException.class,()->WorkspaceScope.validateCurrent(prepared.tasks(),()->false));
    }
    @Test void branchChangeWithSameHeadRequiresRefresh()throws Exception{
        sharedRules();Path root=repository("latin");var prepared=collect(List.of(root));git(root,"checkout","-b","other");
        assertThrows(IOException.class,()->WorkspaceScope.validateCurrent(prepared.tasks(),()->false));
    }
    @Test void commitParticipatingRootsUseActualSelectedFilesAndDeepestRoot()throws Exception{
        Path outer=workspace.resolve("hdpos46"),nested=outer.resolve("nested"),other=workspace.resolve("latin");
        assertEquals(List.of(nested,other),WorkspaceScope.participatingRoots(List.of(outer,nested,other),List.of(nested.resolve("src/a.java"),other.resolve("removed.java"))));
        assertTrue(WorkspaceScope.participatingRoots(List.of(outer,other),List.of()).isEmpty());
    }
    @Test void cancelCollectionCannotReturnAnApparentlyReadyScope()throws Exception{
        assertThrows(java.util.concurrent.CancellationException.class,()->WorkspaceScope.collect(List.of(workspace),workspace,"rules.json",null,()->true));
    }
    @Test void initialCommitDiffUsesFrozenBytesWithoutResolvingEmptyTreeAsCommit()throws Exception{
        sharedRules();Path root=Files.createDirectories(workspace.resolve("initial"));git(root,"init","-b","feature/test");
        git(root,"config","user.name","SilentReview Tests");git(root,"config","user.email","test@example.invalid");
        write(root,"src/OrderService.java","class OrderService {}\n");git(root,"add",".");git(root,"commit","-m","initial");
        var prepared=collect(List.of(root));var repo=prepared.state().repositories().get(0);
        var file=prepared.file(repo.root(),repo.head(),"src/OrderService.java");
        assertTrue(file.before().isEmpty());assertEquals("class OrderService {}\n",file.after());
        assertTrue(file.diff().contains("new file mode"));assertEquals(1,prepared.tasks().size());
    }
    @Test void scopeDiffRemainsTheFrozenSelectionAfterHeadAndWorkingTreeChange()throws Exception{
        sharedRules();Path root=repository("latin");var prepared=collect(List.of(root));var repo=prepared.state().repositories().get(0);
        var frozen=prepared.file(repo.root(),repo.head(),"src/OrderService.java");
        write(root,"src/OrderService.java","committed later\n");git(root,"add",".");git(root,"commit","-m","later");
        write(root,"src/OrderService.java","uncommitted after that\n");
        assertEquals(frozen,prepared.file(repo.root(),repo.head(),"src/OrderService.java"));
        assertFalse(prepared.file(repo.root(),repo.head(),"src/OrderService.java").after().contains("later"));
    }
    @Test void refreshedScopeRejectsObsoleteHeadAndWrongRepositoryOrPath()throws Exception{
        sharedRules();Path root=repository("latin");var original=collect(List.of(root));var repo=original.state().repositories().get(0);
        write(root,"src/OrderService.java","later\n");git(root,"add",".");git(root,"commit","-m","later");
        var refreshed=collect(List.of(root));
        var failure=assertThrows(IllegalArgumentException.class,()->refreshed.file(repo.root(),repo.head(),"src/OrderService.java"));
        assertTrue(failure.getMessage().contains("范围已刷新"));
        var current=refreshed.state().repositories().get(0);
        assertThrows(IllegalArgumentException.class,()->refreshed.file("wrong-root",current.head(),"src/OrderService.java"));
        assertThrows(IllegalArgumentException.class,()->refreshed.file(current.root(),current.head(),"missing.java"));
    }
    @Test void bulkInclusionChangesOnlySelectedRepositoriesAndRetainsLegacyAllBehavior()throws Exception{
        sharedRules();Path first=repository("cable"),second=repository("latin");var prepared=collect(List.of(first,second));
        var onlyFirst=prepared.includeAll(false).includeAll(true,Set.of(first.toString()));
        assertEquals(1,onlyFirst.state().includedCount());assertEquals(1,onlyFirst.tasks().size());
        assertEquals(first.toString(),onlyFirst.tasks().get(0).changes().root());
        assertEquals(onlyFirst,onlyFirst.includeAll(false,Set.of()),"Empty selection must not alter hidden repositories");
        assertTrue(onlyFirst.includeAll(false,Set.of(first.toString())).tasks().isEmpty());
        assertEquals(2,onlyFirst.includeAll(true).tasks().size());assertEquals(2,prepared.state().includedCount());
    }
    @Test void repositorySelectionLimitsActualAiPayloadAndReportNotJustVisibleRows()throws Exception{
        sharedRules();Path first=repository("cable"),second=repository("latin");var prepared=collect(List.of(first,second));
        var tasks=prepared.tasks(Set.of(second.toString()));assertEquals(1,tasks.size());assertTrue(prepared.tasks(Set.of()).isEmpty());
        List<Review.Request> sent=new ArrayList<>();var task=tasks.get(0);
        var outcome=new ReviewRunner().run(task.rules(),task.changes(),request->{
            sent.add(request);JsonObject response=new JsonObject();response.add("findings",new JsonArray());
            response.add("coverage",Review.JSON.toJsonTree(request.requiredChecks().stream().map(check->new Review.Coverage(check.filePath(),check.ruleId(),"reviewed","")).toList()));
            response.add("limitations",new JsonArray());return new AiClient.Response(response.toString(),"test",null);
        },()->false,ignored->{});
        assertEquals(1,sent.size());assertEquals(second.toString(),sent.get(0).changes().root());
        var report=Review.report(outcome.reference(),task.description(),outcome.model(),null,outcome.result());assertEquals(second.toString(),report.root());
        assertEquals(2,prepared.tasks().size(),"Preparing a subset must leave the immutable full snapshot intact");
        git(first,"checkout","-b","unselected-branch");WorkspaceScope.validateCurrent(tasks,()->false);
        assertThrows(IOException.class,()->WorkspaceScope.validateCurrent(prepared.tasks(),()->false));
    }
    @Test void unselectedPreparationErrorsDoNotEnterSelectedBatch()throws Exception{
        sharedRules();Path first=repository("latin"),broken=Files.createDirectory(workspace.resolve("broken"));var prepared=collect(List.of(first,broken));
        assertEquals(1,prepared.preparationErrors(null).size());
        assertTrue(prepared.preparationErrors(Set.of(first.toString())).isEmpty());assertTrue(prepared.preparationErrors(Set.of()).isEmpty());
        assertEquals(1,prepared.preparationErrors(Set.of(broken.toString())).size());
        assertTrue(prepared.preparationErrors(Set.of(broken.toString())).get(0).contains(broken.toString()));
        assertEquals(1,prepared.tasks(Set.of(first.toString())).size());assertTrue(prepared.tasks(Set.of(broken.toString())).isEmpty());
    }
}
