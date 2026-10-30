package dev.silentreview.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.*;
import dev.silentreview.core.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

public final class ReviewService implements Disposable {
    public record View(Review.Status status,String message,List<Review.Report> reports,
                       Map<String,Reports.Resolution> resolutions,Map<String,Map<Integer,Reports.FindingResolution>> findingResolutions,
                       WorkspaceScope.State scope,boolean busy,
                       List<String> discoveryNotes,List<String> discoveryLimitations) {}
    private record Config(String rulesPath,String endpoint,String model,boolean consent) {}
    private final Project project;
    private final ExecutorService queue=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"silent-review");t.setDaemon(true);return t;});
    private final List<Consumer<View>> listeners=new CopyOnWriteArrayList<>();
    private final List<Review.Report> history=new CopyOnWriteArrayList<>();
    private final Map<String,Reports.Resolution> resolutions=new ConcurrentHashMap<>();
    private volatile Map<String,Map<Integer,Reports.FindingResolution>> findingResolutions=Map.of();
    private final Set<String> automaticKeys=ConcurrentHashMap.newKeySet();
    private final Object scopeLock=new Object();
    private final Reports store;
    private volatile Review.Status status=Review.Status.NOT_APPLICABLE;
    private volatile String message="先准备审查范围，确认纳入文件后开始检查。";
    private volatile AtomicBoolean cancellation;
    private volatile String activeEndpoint;
    private volatile int latestBatchFindings;
    private volatile boolean disposed,busy;
    private int pendingActions;
    private volatile WorkspaceScope.Prepared prepared=new WorkspaceScope.Prepared(WorkspaceScope.State.empty(),List.of());
    private volatile String preparedRulesPath;
    private volatile List<String> preparedDiscoveryNotes=List.of(),preparedDiscoveryLimitations=List.of();
    private FishOverlay fish;
    public ReviewService(Project project) {
        this.project=project;
        store=new Reports(Path.of(PathManager.getSystemPath(),"silent-review",project.getLocationHash()));
        queue.submit(()->{
            try{history.addAll(store.load());}catch(Exception e){message="历史报告读取失败："+safeMessage(e);}
            try{resolutions.putAll(store.loadResolutions());}catch(Exception e){message="报告确认历史读取失败："+safeMessage(e);}
            try{findingResolutions=store.loadFindingResolutions();}catch(Exception e){message="逐问题确认历史读取失败："+safeMessage(e);}
            publish();
        });
    }
    public static ReviewService get(Project project){return project.getService(ReviewService.class);}
    public View view(){return new View(status,message,List.copyOf(history),Map.copyOf(resolutions),findingResolutions,prepared.state(),busy,preparedDiscoveryNotes,preparedDiscoveryLimitations);}
    public int latestBatchFindingCount(){return latestBatchFindings;}
    public GitRepository.ChangedFile scopeFile(String root,String head,String path){return prepared.file(root,head,path);}
    public void listen(Consumer<View> listener,Disposable parent) {
        listeners.add(listener);Disposer.register(parent,()->listeners.remove(listener));listener.accept(view());
    }
    private void update(Review.Status value,String text){status=value;message=text;publish();}
    private void publish(){
        if(disposed)return;View view=view();
        SwingUtilities.invokeLater(()->{if(!disposed&&!project.isDisposed())listeners.forEach(l->l.accept(view));});
    }
    public void startFish(){
        if(disposed||project.isDisposed())return;
        if(fish==null)fish=new FishOverlay(project,this);
        fish.refresh();
    }
    public void settingsChanged(){
        ReviewSettings.Data current=ReviewSettings.get(project).data();
        if(activeEndpoint!=null&&(!current.allowExternal||!Objects.equals(activeEndpoint,current.endpoint)))cancel();
        synchronized(scopeLock){
            if(preparedRulesPath!=null&&!Objects.equals(preparedRulesPath,current.rulesPath))
                prepared=new WorkspaceScope.Prepared(prepared.state().markStale(),prepared.frozen());
        }
        ApplicationManager.getApplication().invokeLater(this::startFish,ModalityState.current());publish();
    }
    public void showReports(){
        ApplicationManager.getApplication().invokeLater(()->{if(project.isDisposed())return;ToolWindow w=ToolWindowManager.getInstance(project).getToolWindow("Silent Review");if(w!=null)w.activate(null);});
    }
    public void toggleReports(){
        ApplicationManager.getApplication().invokeLater(()->{
            if(disposed||project.isDisposed())return;
            ToolWindow window=ToolWindowManager.getInstance(project).getToolWindow("Silent Review");
            if(window!=null){if(window.isVisible())window.hide(null);else window.activate(null);}
        });
    }
    /** Menu and overlay review actions expose the file selection before starting any AI request. */
    public void manual(String baseline){showReports();prepareScope(baseline);}
    private Config config(){ReviewSettings.Data s=ReviewSettings.get(project).data();return new Config(s.rulesPath,s.endpoint,s.model,s.allowExternal);}
    private Path projectBase(){return ReadAction.compute(()->project.getBasePath()==null?null:Path.of(project.getBasePath()));}
    private GitRoots.Discovery repositoryRoots(){
        if(ApplicationManager.getApplication().isDispatchThread())
            throw new IllegalStateException("Git 仓库发现必须在后台线程执行。");
        List<Path> registered=ReadAction.compute(()->git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories().stream()
            .map(r->Path.of(r.getRoot().getPath())).sorted().toList());
        List<Path> hints=new ArrayList<>(ReadAction.compute(()->Arrays.stream(ProjectRootManager.getInstance(project).getContentRoots())
            .filter(root->root.isInLocalFileSystem()).map(root->Path.of(root.getPath())).toList()));
        Path base=projectBase();if(base!=null)hints.add(base);
        AtomicBoolean activeCancellation=cancellation;
        return GitRoots.discover(registered,hints,()->disposed||Thread.currentThread().isInterrupted()||(activeCancellation!=null&&activeCancellation.get()));
    }
    private AtomicBoolean begin(){
        synchronized(scopeLock){if(disposed||busy)return null;pendingActions++;busy=true;cancellation=new AtomicBoolean();return cancellation;}
    }
    private void finish(){synchronized(scopeLock){pendingActions--;busy=pendingActions>0;cancellation=null;activeEndpoint=null;}publish();}
    public void prepareScope(String baseline){
        AtomicBoolean canceled=begin();if(canceled==null)return;
        Config settings=config();
        prepared=new WorkspaceScope.Prepared(new WorkspaceScope.State(baseline==null?"":baseline,List.of(),true,false),List.of());
        update(Review.Status.PREPARING,"正在逐仓库收集 HEAD、基线、变更文件与匹配规则…");
        queue.submit(()->{
            try{
                GitRoots.Discovery discovery=repositoryRoots();
                WorkspaceScope.Prepared collected=WorkspaceScope.collect(discovery.roots(),projectBase(),settings.rulesPath(),baseline,()->disposed||canceled.get());
                synchronized(scopeLock){prepared=collected;preparedRulesPath=settings.rulesPath();preparedDiscoveryNotes=discovery.notes();preparedDiscoveryLimitations=discovery.limitations();}
                WorkspaceScope.State scope=collected.state();
                String summary="范围已准备："+scope.repositories().size()+" 个仓库，"+scope.includedCount()+" / "+scope.fileCount()+" 个文件已纳入。可勾选文件后点击“开始审查”。";
                if(scope.failedRepositoryCount()>0)summary+=" "+scope.failedRepositoryCount()+" 个仓库准备失败，详情见范围列表；其他仓库可继续。";
                summary+="\n"+String.join("\n",discovery.notes());
                if(!discovery.limitations().isEmpty())summary+="\n仓库发现未完整：\n"+String.join("\n",discovery.limitations());
                update(scope.failedRepositoryCount()>0||!discovery.limitations().isEmpty()?Review.Status.PARTIAL:Review.Status.QUEUED,summary);
            }catch(CancellationException e){
                prepared=new WorkspaceScope.Prepared(WorkspaceScope.State.empty(),List.of());update(Review.Status.CANCELED,"范围准备已取消。");
            }catch(Exception e){
                prepared=new WorkspaceScope.Prepared(WorkspaceScope.State.empty(),List.of());update(Review.Status.FAILED,safeMessage(e));
            }finally{finish();}
        });
    }
    public void setIncluded(String root,String path,boolean included){
        synchronized(scopeLock){if(busy)return;prepared=prepared.include(root,path,included);}publish();
    }
    public void setAllIncluded(boolean included){
        synchronized(scopeLock){if(busy)return;prepared=prepared.includeAll(included);}publish();
    }
    public void setAllIncluded(boolean included,Set<String> roots){
        Set<String> selected=roots==null?Set.of():Set.copyOf(roots);
        synchronized(scopeLock){if(busy)return;prepared=prepared.includeAll(included,selected);}publish();
    }
    public void startPreparedReview(){
        startPreparedReviewInternal(null);
    }
    public void startPreparedReview(Set<String> roots){
        startPreparedReviewInternal(roots==null?Set.of():Set.copyOf(roots));
    }
    private void startPreparedReviewInternal(Set<String> roots){
        final WorkspaceScope.Prepared selected;final AtomicBoolean canceled;
        synchronized(scopeLock){
            if(busy||disposed)return;
            if(roots!=null&&roots.isEmpty()){
                update(Review.Status.NOT_APPLICABLE,"请先选择要审查的仓库。");return;
            }
            selected=prepared;
            if(!selected.state().ready()){
                update(Review.Status.NOT_APPLICABLE,selected.state().stale()?"范围已失效，请重新准备范围。":"请先准备范围并至少纳入一个匹配规则的文件。");return;
            }
            canceled=begin();
        }
        Config settings=config();
        if(!Objects.equals(preparedRulesPath,settings.rulesPath())){
            synchronized(scopeLock){prepared=new WorkspaceScope.Prepared(selected.state().markStale(),selected.frozen());}
            update(Review.Status.FAILED,"规则路径已变化，请重新准备审查范围。");finish();return;
        }
        activeEndpoint=settings.endpoint();
        update(Review.Status.PREPARING,"核对冻结的仓库快照并开始审查…");
        queue.submit(()->{
            try{
                List<WorkspaceScope.Task> tasks=selected.tasks(roots);
                if(tasks.isEmpty()){
                    update(Review.Status.NOT_APPLICABLE,"所选仓库没有已纳入且匹配规则的文件，请选择仓库并纳入文件。");return;
                }
                try{WorkspaceScope.validateCurrent(tasks,()->disposed||canceled.get());}
                catch(java.io.IOException changed){
                    synchronized(scopeLock){prepared=new WorkspaceScope.Prepared(selected.state().markStale(),selected.frozen());}
                    throw changed;
                }
                List<String> errors=selected.preparationErrors(roots);
                runBatch(withDiscoveryContext(tasks),settings,false,errors,canceled,preparedDiscoveryLimitations);
            }catch(CancellationException e){update(Review.Status.CANCELED,"检查已取消，不能作为已完成结果。");}
            catch(Exception e){update(Review.Status.FAILED,safeMessage(e));}
            finally{finish();}
        });
    }
    private List<WorkspaceScope.Task> withDiscoveryContext(List<WorkspaceScope.Task> tasks){
        List<String> warnings=preparedDiscoveryLimitations,notes=preparedDiscoveryNotes;
        return tasks.stream().map(task->{
            return new WorkspaceScope.Task(task.rules(),task.changes(),task.description()
                +(notes.isEmpty()?"":"\n"+String.join("\n",notes))
                +(warnings.isEmpty()?"":"\n额外仓库发现未完整（不改变当前仓库的 AI 结果）："+String.join("；",warnings)));
        }).toList();
    }
    public void committed(Path root,String previous,String expectedBranch){committed(List.of(new WorkspaceScope.CommitSnapshot(root,previous,expectedBranch)));}
    /** All actual participating commit roots are captured before the commit and processed independently. */
    public void committed(List<WorkspaceScope.CommitSnapshot> snapshots){
        if(!ReviewSettings.get(project).data().automatic||disposed||snapshots.isEmpty())return;
        List<WorkspaceScope.CommitSnapshot> captured=List.copyOf(snapshots);Config settings=config();
        AtomicBoolean canceled=new AtomicBoolean();
        synchronized(scopeLock){if(disposed)return;pendingActions++;busy=true;}
        publish();
        queue.submit(()->{
            cancellation=canceled;activeEndpoint=settings.endpoint();
            try{
                if(disposed||project.isDisposed()||!ReviewSettings.get(project).data().automatic)return;
                List<WorkspaceScope.Task> tasks=new ArrayList<>();List<String> errors=new ArrayList<>();
                for(var snapshot:captured){
                    if(canceled.get()||disposed)throw new CancellationException();
                    try{
                        GitRepository git=new GitRepository(snapshot.root(),()->disposed||canceled.get());
                        if(!Objects.equals(snapshot.branch(),git.branch()))throw new IllegalArgumentException("提交后分支已切换，请手动准备该仓库范围");
                        String head=git.head();
                        if(Objects.equals(snapshot.previous(),head))continue;
                        List<String> commits;
                        if(snapshot.previous()==null)commits=List.of(head);
                        else{
                            String list=git.run("rev-list","--reverse","--ancestry-path",snapshot.previous()+".."+head).strip();
                            if(list.isEmpty())throw new IllegalArgumentException("无法确定新增提交（可能发生 amend 或历史重写），请手动准备范围");
                            commits=Arrays.stream(list.split("\\R")).filter(c->c.matches("[0-9a-f]{40,64}")).toList();
                        }
                        Rules.Snapshot rules=Rules.load(WorkspaceScope.resolveRulesPath(git.root(),projectBase(),settings.rulesPath()));
                        for(String commit:commits){
                            GitRepository.ChangeSet c=git.collect(commit,null);
                            tasks.add(new WorkspaceScope.Task(rules,new GitRepository.ChangeSet(c.root(),snapshot.branch(),c.base(),c.target(),c.files(),c.limitations()),"提交后自动审查：单次提交"));
                        }
                    }catch(CancellationException e){throw e;
                    }catch(Exception e){errors.add(snapshot.root()+"："+safeMessage(e));update(Review.Status.PARTIAL,"仓库准备失败，继续其他仓库："+snapshot.root()+"；"+safeMessage(e));}
                }
                runBatch(tasks,settings,true,errors,canceled,List.of());
            }catch(CancellationException e){update(Review.Status.CANCELED,"自动检查已取消。");}
            catch(Exception e){update(Review.Status.FAILED,safeMessage(e));}
            finally{finish();}
        });
    }
    private void runBatch(List<WorkspaceScope.Task> tasks,Config settings,boolean automatic,List<String> preparationErrors,AtomicBoolean canceled,List<String> discoveryWarnings)throws Exception{
        List<Review.Result> results=new ArrayList<>();List<String> errors=new ArrayList<>(preparationErrors);int skipped=0;
        latestBatchFindings=0;
        for(int i=0;i<tasks.size();i++){
            if(canceled.get()||disposed)throw new CancellationException();
            if(automatic&&!ReviewSettings.get(project).data().automatic)throw new CancellationException();
            WorkspaceScope.Task task=tasks.get(i);String key=task.changes().root()+"|"+task.changes().target()+"|"+task.changes().base()+"|"+task.rules().fingerprint()+"|"+Review.INSTRUCTION_VERSION+"|"+settings.model()+"|"+settings.endpoint();
            if(automatic&&!automaticKeys.add(key)){skipped++;continue;}
            try{
                Review.Result result=runTask(task,settings,canceled,"["+(i+1)+" / "+tasks.size()+"] ");
                results.add(result);
                if(result.status()==Review.Status.FAILED){
                    if(automatic)automaticKeys.remove(key);
                    // Retain the repository qualification in the final batch message as well as its report.
                    errors.add(task.changes().root()+"："+String.join("；",result.limitations()));
                }
                latestBatchFindings+=result.findings().size();
            }catch(CancellationException e){throw e;
            }catch(Exception e){
                errors.add(task.changes().root()+"：报告保存失败；"+safeMessage(e));
                results.add(new Review.Result(List.of(),List.of(),List.of(safeMessage(e)),Review.Status.FAILED));
                if(automatic)automaticKeys.remove(key);
                update(Review.Status.PARTIAL,"该仓库报告保存失败，继续其他仓库："+task.changes().root()+"；"+safeMessage(e));
            }
        }
        ReviewBatch.Summary summary=ReviewBatch.summarize(results,preparationErrors.size());latestBatchFindings=summary.findings();
        Review.Status batchStatus=summary.status();
        if(!discoveryWarnings.isEmpty()&&(batchStatus==Review.Status.COMPLETED||batchStatus==Review.Status.NOT_APPLICABLE))batchStatus=Review.Status.PARTIAL;
        update(batchStatus,"批次结束："+summary.completed()+" 项审查完成，"+summary.failed()+" 个仓库或提交失败，"+summary.partial()+" 项部分完成，"
            +summary.notApplicable()+" 项无适用检查，"+summary.findings()+" 项发现"+(skipped>0?"；跳过 "+skipped+" 项已检查提交":"")
            +"。报告按仓库分别保存。"+(errors.isEmpty()?"":"\n失败明细：\n"+String.join("\n",errors))
            +(discoveryWarnings.isEmpty()?"":"\n额外仓库发现尚有未扫描范围：\n"+String.join("\n",discoveryWarnings)));
    }
    private Review.Result runTask(WorkspaceScope.Task task,Config settings,AtomicBoolean canceled,String prefix)throws Exception{
        GitRepository.ChangeSet changes=task.changes();Rules.Snapshot rules=task.rules();
        Review.Request request=Review.request(rules,changes);
        try{
            if(!settings.consent()||!ReviewSettings.get(project).data().allowExternal)throw new IllegalArgumentException("请在设置中允许向指定 AI 接口发送相关代码与规则。");
            update(Review.Status.PREPARING,prefix+"仓库 "+changes.root()+" · "+changes.target().substring(0,10));
            String instructions;
            try(var in=getClass().getResourceAsStream("/review/review-skill.md")){
                if(in==null)throw new IllegalStateException("缺少审查指令资源");instructions=new String(in.readAllBytes(),StandardCharsets.UTF_8);
            }
            String[] credential={null};
            ReviewRunner.Outcome outcome=new ReviewRunner().run(rules,changes,batch->{
                ReviewSettings.Data current=ReviewSettings.get(project).data();
                if(!current.allowExternal||!Objects.equals(settings.endpoint(),current.endpoint))throw new CancellationException("AI 连接或授权配置已变化");
                if(credential[0]==null)credential[0]=ReviewSettings.get(project).key(settings.endpoint());
                return new AiClient().review(new AiClient.Config(settings.endpoint(),settings.model(),credential[0]),instructions,batch,()->disposed||canceled.get());
            },()->disposed||canceled.get(),text->update(Review.Status.RUNNING,prefix+Path.of(changes.root()).getFileName()+" · "+text));
            if(canceled.get()||disposed)throw new CancellationException();
            Review.Report report=Review.report(outcome.reference(),task.description(),outcome.model(),outcome.usage(),outcome.result());
            save(report);return outcome.result();
        }catch(CancellationException e){
            if(!disposed)try{save(Review.report(request,task.description(),settings.model(),null,new Review.Result(List.of(),List.of(),List.of("检查已取消，未完成的范围不可作为已检查依据"),Review.Status.CANCELED)));}
            catch(java.io.IOException saveFailure){e.addSuppressed(saveFailure);}
            throw e;
        }catch(Exception e){
            String error=safeMessage(e);
            Review.Result failure=new Review.Result(List.of(),request.requiredChecks().stream().map(c->new Review.Coverage(c.filePath(),c.ruleId(),"failed",error)).toList(),List.of(error),Review.Status.FAILED);
            save(Review.report(request,task.description(),settings.model(),null,failure));return failure;
        }
    }
    private void save(Review.Report report)throws java.io.IOException{
        store.save(report);history.add(0,report);while(history.size()>100)history.remove(history.size()-1);reloadHandling();publish();
    }
    private void reloadHandling()throws java.io.IOException{
        Map<String,Reports.Resolution> saved=store.loadResolutions();
        Map<String,Map<Integer,Reports.FindingResolution>> findings=store.loadFindingResolutions();
        resolutions.clear();resolutions.putAll(saved);findingResolutions=findings;
    }
    public CompletableFuture<Reports.FindingResolution> acknowledgeFinding(String reportId,int findingIndex,boolean confirmed){
        CompletableFuture<Reports.FindingResolution> completion=new CompletableFuture<>();
        try{queue.submit(()->{
            try{
                Reports.FindingResolution saved=store.acknowledgeFinding(reportId,findingIndex,confirmed,
                    confirmed?"用户确认此问题已解决。":"用户撤销此问题的已解决确认。",System.getProperty("user.name","IDE 用户"));
                reloadHandling();publish();completion.complete(saved);
            }catch(Exception failure){update(Review.Status.FAILED,"问题确认保存失败："+safeMessage(failure));completion.completeExceptionally(failure);}
        });}catch(RejectedExecutionException closed){completion.completeExceptionally(closed);}
        return completion;
    }
    public void acknowledge(String reportId,Reports.Decision decision,String note){
        queue.submit(()->{try{Reports.Resolution resolution=store.acknowledge(reportId,decision,note,System.getProperty("user.name","IDE 用户"));resolutions.put(reportId,resolution);publish();}
            catch(Exception e){update(Review.Status.FAILED,"报告确认保存失败："+safeMessage(e));}});
    }
    public void cancel(){AtomicBoolean active=cancellation;if(active!=null)active.set(true);}
    public void clearHistory(){queue.submit(()->{try{store.clear();history.clear();resolutions.clear();findingResolutions=Map.of();publish();}catch(Exception e){update(Review.Status.FAILED,safeMessage(e));}});}
    private static String safeMessage(Exception e){return e.getMessage()==null?"检查失败，请重试。":e.getMessage();}
    @Override public void dispose(){disposed=true;cancel();queue.shutdownNow();listeners.clear();if(fish!=null)fish.dispose();}
}
