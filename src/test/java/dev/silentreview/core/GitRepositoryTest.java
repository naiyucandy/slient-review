package dev.silentreview.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GitRepositoryTest {
    @TempDir Path directory;
    private String git(String...args)throws Exception{
        List<String> cmd=new ArrayList<>(List.of("git","-C",directory.toString(),"-c","core.hooksPath="+directory.resolve("no-hooks"),"-c","commit.gpgsign=false"));cmd.addAll(List.of(args));
        ProcessBuilder builder=new ProcessBuilder(cmd).redirectErrorStream(true);builder.environment().put("GIT_CONFIG_NOSYSTEM","1");builder.environment().put("GIT_CONFIG_GLOBAL",directory.resolve("no-global-config").toString());
        Process p=builder.start();String out=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertEquals(0,p.waitFor(),out);return out.strip();
    }
    @BeforeEach void init()throws Exception{git("init","-b","feature/test");git("config","user.name","SilentReview Tests");git("config","user.email","test@example.invalid");}
    private void write(String path,String text)throws IOException{Path p=directory.resolve(path);Files.createDirectories(p.getParent());Files.writeString(p,text);}
    private String commit()throws Exception{git("add",".");git("commit","-m","test");return git("rev-parse","HEAD");}
    @Test void initialCommitUsesEmptyTreeAndAddedLines()throws Exception{
        write("src/OrderService.java","class OrderService {}\n");String head=commit();var change=new GitRepository(directory,()->false).collect(head,null);
        assertEquals(1,change.files().size());assertTrue(change.files().get(0).before().isEmpty());assertEquals(Set.of(1),change.files().get(0).addedLines());
    }
    @Test void workingTreeChangesCannotContaminateCommittedSnapshot()throws Exception{
        write("src/OrderService.java","class OrderService {\n}\n");commit();write("src/OrderService.java","class OrderService {\n  System.out.println(order);\n}\n");String target=commit();
        write("src/OrderService.java","uncommitted unrelated content");var change=new GitRepository(directory,()->false).collect(target,null);
        assertTrue(change.files().get(0).after().contains("System.out.println"));assertFalse(change.files().get(0).after().contains("uncommitted"));assertEquals(Set.of(2),change.files().get(0).addedLines());
    }
    @Test void handlesUnicodePathsAndRename()throws Exception{
        write("src/订单.java","class Order {}\n");commit();Files.move(directory.resolve("src/订单.java"),directory.resolve("src/新订单.java"));String head=commit();
        var file=new GitRepository(directory,()->false).collect(head,null).files().get(0);assertEquals("src/新订单.java",file.path());assertEquals("src/订单.java",file.beforePath());assertTrue(file.status().startsWith("R"));
    }
    @Test void deletedFileKeepsBeforeSnapshot()throws Exception{
        write("src/a.js","eval(value);\n");commit();Files.delete(directory.resolve("src/a.js"));String head=commit();var file=new GitRepository(directory,()->false).collect(head,null).files().get(0);assertTrue(file.after().isEmpty());assertTrue(file.before().contains("eval"));assertEquals(Set.of(1),file.deletedLines());
    }
    @Test void fixedIssueDisappearsFromRangeNetDiff()throws Exception{
        write("src/a.js","const a = 1;\n");String base=commit();write("src/a.js","eval(value);\n");commit();write("src/a.js","const a = 1;\n");String head=commit();
        assertTrue(new GitRepository(directory,()->false).collect(head,base).files().isEmpty());
    }
    @Test void cannotResolveOptionAsRevision()throws Exception{write("a.txt","x");commit();assertThrows(IOException.class,()->new GitRepository(directory,()->false).resolve("--help"));}
    @Test void cancellationIsNotConvertedToSuccess()throws Exception{assertThrows(java.util.concurrent.CancellationException.class,()->new GitRepository(directory,()->true));}
}
