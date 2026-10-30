package dev.silentreview.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Repository-qualified, immutable review snapshots. Selection never changes the IDE commit selection. */
public final class WorkspaceScope {
    private WorkspaceScope() {}
    public record File(String root,String path,String changeType,List<String> ruleIds,boolean included,String unavailableReason) {
        public File { ruleIds=List.copyOf(ruleIds); }
        public boolean selectable(){return !ruleIds.isEmpty()&&(unavailableReason==null||unavailableReason.isBlank());}
        File include(boolean value){return new File(root,path,changeType,ruleIds,value&&selectable(),unavailableReason);}
    }
    public record Repository(String root,String branch,String head,String baseline,String description,List<File> files,String error) {
        public Repository { files=List.copyOf(files); }
    }
    public record State(String baseline,List<Repository> repositories,boolean preparing,boolean stale) {
        public State { repositories=List.copyOf(repositories); }
        public static State empty(){return new State("",List.of(),false,false);}
        public int fileCount(){return repositories.stream().mapToInt(r->r.files().size()).sum();}
        public int includedCount(){return (int)repositories.stream().flatMap(r->r.files().stream()).filter(File::included).count();}
        public int eligibleCount(){return (int)repositories.stream().flatMap(r->r.files().stream()).filter(File::selectable).count();}
        public int excludedCount(){return fileCount()-includedCount();}
        public int failedRepositoryCount(){return (int)repositories.stream().filter(r->r.error()!=null&&!r.error().isBlank()).count();}
        public boolean ready(){return !preparing&&!stale&&includedCount()>0;}
        public State markStale(){return new State(baseline,repositories,false,true);}
    }
    public record CommitSnapshot(Path root,String previous,String branch) {}
    public record FrozenRepository(Rules.Snapshot rules,GitRepository.ChangeSet changes,String description) {}
    public record Task(Rules.Snapshot rules,GitRepository.ChangeSet changes,String description) {}
    public record Prepared(State state,List<FrozenRepository> frozen) {
        public Prepared { frozen=List.copyOf(frozen); }
        /** Diff reads the exact byte snapshot shown in this scope, including initial-commit empty-tree baselines. */
        public GitRepository.ChangedFile file(String root,String head,String path){
            return frozen.stream().filter(r->r.changes().root().equals(root)&&r.changes().target().equals(head))
                .flatMap(r->r.changes().files().stream()).filter(f->f.path().equals(path)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("范围已刷新，请重新选择文件。"));
        }
        public Prepared include(String root,String path,boolean included){
            List<Repository> repos=state.repositories().stream().map(r->new Repository(r.root(),r.branch(),r.head(),r.baseline(),r.description(),
                r.files().stream().map(f->f.root().equals(root)&&f.path().equals(path)?f.include(included):f).toList(),r.error())).toList();
            return new Prepared(new State(state.baseline(),repos,state.preparing(),state.stale()),frozen);
        }
        public Prepared includeAll(boolean included){
            return includeAll(included,null);
        }
        /** null keeps the legacy all-repository behavior; an empty selection changes no files. */
        public Prepared includeAll(boolean included,Set<String> roots){
            Set<String> selected=roots==null?null:Set.copyOf(roots);
            List<Repository> repos=state.repositories().stream().map(r->new Repository(r.root(),r.branch(),r.head(),r.baseline(),r.description(),
                r.files().stream().map(f->selected==null||selected.contains(r.root())?f.include(included):f).toList(),r.error())).toList();
            return new Prepared(new State(state.baseline(),repos,state.preparing(),state.stale()),frozen);
        }
        /** Only selected, rule-matched files enter AI payloads AND the persisted report reference. */
        public List<Task> tasks(){
            return tasks(null);
        }
        public List<Task> tasks(Set<String> roots){
            Set<String> selected=roots==null?null:Set.copyOf(roots);
            List<Task> result=new ArrayList<>();
            for(FrozenRepository repository:frozen){
                if(selected!=null&&!selected.contains(repository.changes().root()))continue;
                Set<String> paths=new HashSet<>();
                state.repositories().stream().filter(r->r.root().equals(repository.changes().root())).flatMap(r->r.files().stream())
                    .filter(f->f.included()&&f.selectable()).forEach(f->paths.add(f.path()));
                List<GitRepository.ChangedFile> files=repository.changes().files().stream().filter(f->paths.contains(f.path())).toList();
                if(files.isEmpty())continue;
                GitRepository.ChangeSet c=repository.changes();
                long excluded=c.files().stream().filter(f->!paths.contains(f.path())).count();
                String description=repository.description()+"；用户纳入 "+files.size()+" 个文件，排除 "+excluded+" 个文件"
                    +"；纳入路径：["+String.join("，",files.stream().map(GitRepository.ChangedFile::path).toList())+"]"
                    +"；排除路径：["+String.join("，",c.files().stream().filter(f->!paths.contains(f.path())).map(GitRepository.ChangedFile::path).toList())+"]";
                result.add(new Task(repository.rules(),new GitRepository.ChangeSet(c.root(),c.branch(),c.base(),c.target(),files,c.limitations()),description));
            }
            return List.copyOf(result);
        }
        public List<String> preparationErrors(Set<String> roots){
            Set<String> selected=roots==null?null:Set.copyOf(roots);
            return state.repositories().stream().filter(repository->selected==null||selected.contains(repository.root()))
                .filter(repository->repository.error()!=null&&!repository.error().isBlank())
                .map(repository->repository.root()+"："+repository.error()).toList();
        }
    }

    public static Prepared collect(List<Path> roots,Path projectBase,String rulesPath,String baseline,BooleanSupplier canceled){
        List<Repository> visible=new ArrayList<>();List<FrozenRepository> frozen=new ArrayList<>();Set<Path> seen=new HashSet<>();
        for(Path directory:roots){
            if(canceled.getAsBoolean())throw new CancellationException("范围收集已取消");
            String root=directory.toAbsolutePath().normalize().toString(),branch="",head="",base="",description="单次提交";
            GitRepository.ChangeSet changes=null;
            try{
                GitRepository git=new GitRepository(directory,canceled);
                if(!seen.add(git.root().toAbsolutePath().normalize()))continue;
                root=git.root().toString();branch=git.branch();head=git.head();
                if(baseline!=null&&!baseline.isBlank()){
                    if(baseline.equals("@{upstream}")){base=git.upstream();description="本地上游引用到当前提交的净变更（未自动更新远端引用）";}
                    else{base=git.mergeBase(baseline,head);description="相对所选公共分支共同祖先的净变更";}
                }
                GitRepository.ChangeSet collected=git.collect(head,base.isBlank()?null:base);
                changes=new GitRepository.ChangeSet(root,branch,collected.base(),head,collected.files(),collected.limitations());
                base=changes.base();
                if(!head.equals(git.head())||!branch.equals(git.branch()))throw new IOException("收集期间 HEAD 或分支发生变化，请刷新范围");
                Rules.Snapshot rules=Rules.load(resolveRulesPath(git.root(),projectBase,rulesPath));
                List<File> files=new ArrayList<>();
                for(var file:changes.files()){
                    List<String> ids=rules.rules().stream().filter(r->r.appliesTo(file.path())).map(Rules.Rule::id).toList();
                    String unavailable=ids.isEmpty()?"没有匹配规则":file.before().length()+file.after().length()+file.diff().length()>ReviewRunner.MAX_SOURCE_CHARACTERS?"文件超过单次请求上下文上限，无法纳入":"";
                    files.add(new File(root,file.path(),file.status(),ids,unavailable.isEmpty(),unavailable));
                }
                visible.add(new Repository(root,branch,head,base,description+limitations(changes),files,""));
                frozen.add(new FrozenRepository(rules,changes,description));
            }catch(CancellationException e){throw e;
            }catch(Exception e){
                String error=e.getMessage()==null?"无法准备该仓库":e.getMessage();List<File> files=new ArrayList<>();
                if(changes!=null)for(var file:changes.files())files.add(new File(root,file.path(),file.status(),List.of(),false,error));
                visible.add(new Repository(root,branch,head,base,description,files,error));
            }
        }
        return new Prepared(new State(baseline==null?"":baseline,visible,false,false),frozen);
    }
    private static String limitations(GitRepository.ChangeSet changes){return changes.limitations().isEmpty()?"":"；"+String.join("；",changes.limitations());}
    public static Path resolveRulesPath(Path root,Path projectBase,String configured){
        if(configured==null||configured.isBlank())throw new IllegalArgumentException("请在设置中选择规则文件或创建默认规则模板");
        Path path=Path.of(configured);
        if(path.isAbsolute())return path.normalize();
        Path local=root.resolve(path).normalize();
        if(Files.isRegularFile(local)||projectBase==null)return local;
        Path fallback=projectBase.resolve(path).normalize();
        return Files.isRegularFile(fallback)?fallback:local;
    }
    /** Validate the selected roots together before sending any source to the AI. */
    public static void validateCurrent(List<Task> tasks,BooleanSupplier canceled)throws IOException{
        for(Task task:tasks){
            GitRepository git=new GitRepository(Path.of(task.changes().root()),canceled);
            if(!task.changes().target().equals(git.head())||!task.changes().branch().equals(git.branch()))
                throw new IOException("仓库 HEAD 或分支已变化，请刷新审查范围："+task.changes().root());
        }
    }
    /** Nested Git roots belong to the deepest selected repository, never its parent. */
    public static List<Path> participatingRoots(List<Path> roots,Collection<Path> selectedPaths){
        LinkedHashSet<Path> selected=new LinkedHashSet<>();
        List<Path> normalized=roots.stream().map(p->p.toAbsolutePath().normalize()).toList();
        for(Path file:selectedPaths){
            Path absolute=file.toAbsolutePath().normalize();
            normalized.stream().filter(absolute::startsWith).max(Comparator.comparingInt(Path::getNameCount)).ifPresent(selected::add);
        }
        return List.copyOf(selected);
    }
}
