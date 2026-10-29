package dev.silentreview.idea;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.*;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.content.ContentFactory;
import dev.silentreview.core.*;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

public final class ReviewToolWindowFactory implements ToolWindowFactory,DumbAware {
    @Override public void createToolWindowContent(@NotNull Project project,@NotNull ToolWindow window){
        Panel panel=new Panel(project);
        var content=ContentFactory.getInstance().createContent(panel,"提交审查",false);
        content.setDisposer(panel);window.getContentManager().addContent(content);
    }
    private static final class Panel extends JPanel implements Disposable {
        private final Project project;
        private final ReviewService service;
        private final JLabel status=new JLabel("等待检查");
        private final DefaultListModel<Review.Report> model=new DefaultListModel<>();
        private final JList<Review.Report> history=new JList<>(model);
        private final JEditorPane body=new JEditorPane("text/html","");
        private final JComboBox<String> decisionFilter=new JComboBox<>(new String[]{"全部处理状态","待处理","已确认"});
        private final JComboBox<String> severityFilter=new JComboBox<>(new String[]{"全部严重级别","error","warning","info"});
        private final JTabbedPane tabs=new JTabbedPane();
        private final JLabel scopeCounts=new JLabel("尚未准备范围。先刷新范围，再选择文件并开始审查。");
        private final JTextArea scopeInfo=new JTextArea(4,30);
        private final ScopeTable scopeModel=new ScopeTable();
        private final JTable scopeTable=new JTable(scopeModel);
        private final List<JButton> scopeActions=new ArrayList<>();
        private final Set<String> selectedRoots=new LinkedHashSet<>();
        private final List<String> knownRoots=new ArrayList<>();
        private final Set<String> selectedReportRoots=new LinkedHashSet<>();
        private final List<String> knownReportRoots=new ArrayList<>();
        private boolean scopeSelectionInitialized,reportSelectionInitialized;
        private JButton start,stop,process,notProcess,repoButton,reportRootButton;
        private ReviewService.View view;
        private boolean refreshing;
        private volatile boolean disposed;
        Panel(Project project){
            super(new BorderLayout(8,8));this.project=project;service=ReviewService.get(project);view=service.view();
            JPanel toolbar=new JPanel(new BorderLayout());
            JPanel primary=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
            scopeActions.add(button(primary,"准备当前提交",()->service.manual(null)));
            scopeActions.add(button(primary,"刷新范围",()->service.prepareScope(view.scope().baseline())));
            repoButton=button(primary,"仓库：全部仓库 ▾",this::showScopeRepoMenu);
            repoButton.setToolTipText("选择参与审查的仓库，可多选或全选");
            scopeActions.add(repoButton);
            toolbar.add(primary,BorderLayout.WEST);
            JPanel settings=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,4));settings.add(settingsButton());
            toolbar.add(settings,BorderLayout.EAST);
            JPanel top=new JPanel(new BorderLayout());top.add(toolbar,BorderLayout.NORTH);top.add(status,BorderLayout.SOUTH);add(top,BorderLayout.NORTH);
            tabs.addTab("审查范围",scopePanel());tabs.addTab("报告与处理",reportPanel());add(tabs,BorderLayout.CENTER);
            service.listen(this::refresh,this);service.startFish();
        }
        private JButton settingsButton(){
            JButton gear=new JButton(AllIcons.General.GearPlain);
            gear.setToolTipText("设置");gear.setFocusable(false);gear.setMargin(new Insets(2,4,2,4));
            gear.setContentAreaFilled(false);gear.setBorderPainted(false);
            gear.getAccessibleContext().setAccessibleName("设置");
            gear.addActionListener(e->ShowSettingsUtil.getInstance().showSettingsDialog(project,ReviewConfigurable.class));
            return gear;
        }
        private JPanel scopePanel(){
            JPanel panel=new JPanel(new BorderLayout(5,5));
            JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
            stop=button(actions,"停止审查",service::cancel);
            stop.setToolTipText("停止正在进行的审查或范围准备");
            JButton includeAll=button(actions,"全部纳入",()->service.setAllIncluded(true,Set.copyOf(selectedRoots)));
            includeAll.setToolTipText("仅纳入已选仓库的文件");
            JButton excludeAll=button(actions,"全部排除",()->service.setAllIncluded(false,Set.copyOf(selectedRoots)));
            excludeAll.setToolTipText("仅排除已选仓库的文件");
            scopeActions.add(includeAll);scopeActions.add(excludeAll);
            start=button(actions,"开始审查",()->service.startPreparedReview(Set.copyOf(selectedRoots)));
            start.setToolTipText("审查所选仓库中已纳入的文件");
            JPanel heading=new JPanel(new BorderLayout());heading.add(actions,BorderLayout.NORTH);heading.add(scopeCounts,BorderLayout.CENTER);
            JLabel hint=new JLabel("<html>勾选仅控制插件审查范围。<br>IDE 提交窗口的 included 数量不代表本插件的审查数量。</html>");
            heading.add(hint,BorderLayout.SOUTH);panel.add(heading,BorderLayout.NORTH);
            scopeTable.setAutoCreateRowSorter(true);scopeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            scopeTable.getColumnModel().getColumn(0).setMaxWidth(64);
            scopeTable.getColumnModel().getColumn(0).setCellRenderer((table,value,selected,focus,row,column)->{
                JCheckBox checkbox=new JCheckBox();checkbox.setHorizontalAlignment(SwingConstants.CENTER);checkbox.setOpaque(true);
                checkbox.setSelected(Boolean.TRUE.equals(value));checkbox.setEnabled(scopeModel.isCellEditable(table.convertRowIndexToModel(row),0));
                checkbox.setBackground(selected?table.getSelectionBackground():table.getBackground());
                checkbox.setToolTipText(scopeModel.rows.get(table.convertRowIndexToModel(row)).unavailableReason());return checkbox;
            });
            scopeTable.setRowHeight(Math.max(22,scopeTable.getRowHeight()));
            panel.add(new JScrollPane(scopeTable),BorderLayout.CENTER);
            scopeInfo.setEditable(false);scopeInfo.setLineWrap(true);scopeInfo.setWrapStyleWord(true);
            panel.add(new JScrollPane(scopeInfo),BorderLayout.SOUTH);return panel;
        }
        private JPanel reportPanel(){
            JPanel panel=new JPanel(new BorderLayout(5,5));
            JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,5,5));
            JPanel filters=new JPanel(new GridLayout(0,1,5,5));
            reportRootButton=button(filters,"全部仓库 ▾",this::showReportRepoMenu);
            reportRootButton.setHorizontalAlignment(SwingConstants.LEFT);
            reportRootButton.setToolTipText("选择要显示报告的仓库，可多选或全选");
            filters.add(decisionFilter);filters.add(severityFilter);
            decisionFilter.addActionListener(e->applyFilters());severityFilter.addActionListener(e->applyFilters());
            process=button(actions,"处理",()->acknowledge(Reports.Decision.CONFIRMED));
            process.setToolTipText("标记为已确认");
            notProcess=button(actions,"不处理",()->acknowledge(Reports.Decision.PENDING));
            notProcess.setToolTipText("恢复为待处理");
            JPanel heading=new JPanel(new BorderLayout(5,5));heading.add(filters,BorderLayout.NORTH);heading.add(actions,BorderLayout.CENTER);panel.add(heading,BorderLayout.NORTH);
            history.setCellRenderer(new DefaultListCellRenderer(){
                @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                    JLabel label=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);
                    if(value instanceof Review.Report r){
                        label.setText(rootName(r.root())+" · "+shortRevision(r.target())+" · "+Reports.label(Reports.resolution(r.id(),view.resolutions()).decision())+" · "+Reports.label(r.result().status())+" · "+r.result().findings().size()+" 项");
                        label.setToolTipText(r.root()+" @ "+r.target());
                    }return label;
                }
            });
            history.setPreferredSize(new Dimension(270,300));history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            history.addListSelectionListener(e->{if(!e.getValueIsAdjusting()&&!refreshing)render();});
            body.setEditable(false);body.addHyperlinkListener(e->{
                if(e.getEventType()==HyperlinkEvent.EventType.ACTIVATED&&e.getDescription()!=null){
                    String link=e.getDescription();
                    try{
                        if(link.startsWith("finding:"))locate(Integer.parseInt(link.substring(8)));
                        else if(link.startsWith("snapshot:"))snapshot(Integer.parseInt(link.substring(9)));
                    }catch(NumberFormatException ignored){}
                }
            });
            JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(history),new JScrollPane(body));split.setResizeWeight(0.28);panel.add(split,BorderLayout.CENTER);
            return panel;
        }
        private static JButton button(JPanel parent,String title,Runnable action){
            JButton b=new JButton(title);b.addActionListener(e->action.run());
            b.setMargin(new Insets(2,9,2,9));b.setFocusable(false);parent.add(b);return b;
        }
        private void refresh(ReviewService.View next){
            if(disposed)return;
            if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(()->refresh(next));return;}
            String newestBefore=view.reports().isEmpty()?null:view.reports().get(0).id();view=next;
            status.setText(Reports.label(next.status())+"："+next.message());status.setToolTipText(next.message());
            scopeActions.forEach(b->b.setEnabled(!next.busy()&&!next.scope().preparing()));
            stop.setEnabled(next.busy());
            updateScopeRoots(next.scope());
            updateScope();
            updateReportRoots(next.reports());
            updateReportRootButton();
            applyFilters();
            String newest=next.reports().isEmpty()?null:next.reports().get(0).id();
            if(newest!=null&&!Objects.equals(newest,newestBefore)){tabs.setSelectedIndex(1);if(model.size()>0)history.setSelectedIndex(0);}
            else if(next.scope().preparing())tabs.setSelectedIndex(0);
        }
        private void updateScopeRoots(WorkspaceScope.State scope){
            if(scope.preparing()&&scope.repositories().isEmpty())return;
            List<String> roots=scope.repositories().stream().map(WorkspaceScope.Repository::root).sorted().toList();
            boolean wasAll=scopeSelectionInitialized&&selectedRoots.containsAll(knownRoots);
            knownRoots.clear();knownRoots.addAll(roots);
            selectedRoots.retainAll(roots);
            if(!scopeSelectionInitialized||wasAll)selectedRoots.addAll(roots);
            scopeSelectionInitialized=true;
        }
        private void updateReportRoots(List<Review.Report> reports){
            List<String> roots=reports.stream().map(Review.Report::root).distinct().sorted().toList();
            boolean wasAll=reportSelectionInitialized&&selectedReportRoots.containsAll(knownReportRoots);
            knownReportRoots.clear();knownReportRoots.addAll(roots);
            selectedReportRoots.retainAll(roots);
            if(!reportSelectionInitialized||wasAll)selectedReportRoots.addAll(roots);
            reportSelectionInitialized=true;
        }
        private void updateScope(){
            WorkspaceScope.State scope=view.scope();
            List<WorkspaceScope.Repository> repositories=scope.repositories().stream()
                .filter(r->selectedRoots.contains(r.root())).toList();
            int files=repositories.stream().mapToInt(r->r.files().size()).sum();
            int included=(int)repositories.stream().flatMap(r->r.files().stream()).filter(WorkspaceScope.File::included).count();
            int eligible=(int)repositories.stream().flatMap(r->r.files().stream()).filter(WorkspaceScope.File::selectable).count();
            int failed=(int)repositories.stream().filter(r->r.error()!=null&&!r.error().isBlank()).count();
            String state=scope.preparing()?" · 准备中":scope.stale()?" · 范围已过期，请刷新":"";
            if(scope.preparing()&&scope.repositories().isEmpty()){
                scopeCounts.setText("<html>正在逐仓库收集范围…</html>");
            }else if(repositories.isEmpty()){
                scopeCounts.setText("<html>"+(scope.repositories().isEmpty()?"尚无仓库；请先准备当前提交。":"未选择仓库；请通过上方仓库选择器选择要审查的仓库。")+state+"</html>");
            }else{
                scopeCounts.setText("<html>已选 "+repositories.size()+" / "+scope.repositories().size()+" 个仓库 / "+files+" 个变更文件；<br>已纳入 "+included+"，已排除 "+(files-included)+"，可选 "+eligible+"，失败仓库 "+failed+state+"</html>");
            }
            scopeModel.rows=repositories.stream().flatMap(r->r.files().stream()).toList();scopeModel.fireTableDataChanged();
            StringBuilder info=new StringBuilder("审查使用此处冻结的提交；不含尚未提交的编辑。\n");
            view.discoveryNotes().forEach(note->info.append(note).append('\n'));
            view.discoveryLimitations().forEach(warning->info.append("发现提示：").append(warning).append('\n'));
            if(repositories.isEmpty())info.append("未选择仓库。\n");
            for(var repo:repositories)info.append(repo.root()).append("\n  ").append(repo.branch()).append(" · HEAD ").append(repo.head()).append(" · 基线 ").append(repo.baseline()).append(" · ").append(repo.description()).append(repo.error()==null||repo.error().isBlank()?"":" · 失败："+repo.error()).append('\n');
            scopeInfo.setText(info.toString());scopeInfo.setCaretPosition(0);
            boolean hasIncluded=repositories.stream().flatMap(r->r.files().stream()).anyMatch(f->f.included()&&f.selectable());
            start.setEnabled(!view.busy()&&scope.ready()&&hasIncluded);
            repoButton.setEnabled(!view.busy()&&!scope.preparing()&&!scope.repositories().isEmpty());
            updateRepoButton(repoButton,selectedRoots,knownRoots,"仓库：");
        }
        private void updateReportRootButton(){
            updateRepoButton(reportRootButton,selectedReportRoots,knownReportRoots,"");
            reportRootButton.setEnabled(!knownReportRoots.isEmpty());
        }
        private static void updateRepoButton(JButton button,Set<String> selected,List<String> all,String prefix){
            String text;
            if(all.isEmpty())text=prefix+"全部仓库";
            else if(selected.isEmpty())text=prefix+"未选择仓库";
            else if(selected.containsAll(all))text=prefix+"全部仓库";
            else text=prefix+"已选 "+selected.size()+" 个仓库";
            button.setText(text+" ▾");
        }
        private void showScopeRepoMenu(){
            showRepoMenu(repoButton,List.copyOf(knownRoots),selectedRoots,this::scopeRootsChanged);
        }
        private void showReportRepoMenu(){
            showRepoMenu(reportRootButton,List.copyOf(knownReportRoots),selectedReportRoots,this::reportRootsChanged);
        }
        private void showRepoMenu(JButton button,List<String> roots,Set<String> selected,Runnable changed){
            JPopupMenu menu=new JPopupMenu();
            JMenuItem all=new JMenuItem("全选");
            all.setEnabled(!roots.isEmpty());
            all.addActionListener(e->{selected.clear();selected.addAll(roots);changed.run();menu.setVisible(false);});
            menu.add(all);
            JMenuItem none=new JMenuItem("全不选");
            none.setEnabled(!roots.isEmpty());
            none.addActionListener(e->{selected.clear();changed.run();menu.setVisible(false);});
            menu.add(none);
            menu.addSeparator();
            if(roots.isEmpty()){
                JMenuItem empty=new JMenuItem("暂无仓库");empty.setEnabled(false);menu.add(empty);
            }else{
                for(String root:roots){
                    String name=rootName(root);
                    boolean duplicate=roots.stream().filter(other->rootName(other).equals(name)).count()>1;
                    JCheckBoxMenuItem item=new JCheckBoxMenuItem(duplicate?name+" — "+root:name,selected.contains(root));
                    item.setToolTipText(root);
                    item.addActionListener(e->{
                        if(item.isSelected())selected.add(root);else selected.remove(root);
                        changed.run();
                    });
                    menu.add(item);
                }
            }
            menu.show(button,0,button.getHeight());
        }
        private void scopeRootsChanged(){
            updateScope();
        }
        private void reportRootsChanged(){
            updateReportRootButton();
            applyFilters();
        }
        private void applyFilters(){
            if(refreshing)return;
            Review.Report selected=history.getSelectedValue();String id=selected==null?null:selected.id();
            Reports.Decision decision=decisionFilter.getSelectedIndex()<=0?null:Reports.Decision.values()[decisionFilter.getSelectedIndex()-1];
            Set<String> roots=selectedReportRoots.containsAll(knownReportRoots)?null:Set.copyOf(selectedReportRoots);
            String severity=selectedSeverity();
            refreshing=true;model.clear();ReportFilters.select(view.reports(),view.resolutions(),roots,decision,severity).forEach(model::addElement);
            int chosen=0;for(int i=0;i<model.size();i++)if(model.get(i).id().equals(id))chosen=i;
            if(!model.isEmpty())history.setSelectedIndex(chosen);refreshing=false;render();
        }
        private String selectedSeverity(){return severityFilter.getSelectedIndex()<=0?null:(String)severityFilter.getSelectedItem();}
        private void render(){
            Review.Report r=history.getSelectedValue();
            if(r==null){process.setEnabled(false);notProcess.setEnabled(false);body.setText("<html><body><h2>静默代码检测</h2><p>在设置中创建默认规则模板并配置 AI 接口，然后准备范围、纳入文件并开始审查。</p><p>报告会按仓库独立保存。已确认报告仍可通过处理状态筛选查看。</p><p>当前筛选下没有报告。</p></body></html>");return;}
            Reports.Resolution resolution=Reports.resolution(r.id(),view.resolutions());
            process.setEnabled(!view.busy()&&resolution.decision()!=Reports.Decision.CONFIRMED);
            notProcess.setEnabled(!view.busy()&&resolution.decision()!=Reports.Decision.PENDING);
            StringBuilder html=new StringBuilder("<html><body style='font-family:sans-serif;margin:16px'><h2>提交分析报告</h2>");
            html.append("<h3>仓库：").append(escape(r.root())).append("</h3>");
            paragraph(html,"AI 执行状态",Reports.label(r.result().status()));paragraph(html,"人工处理状态",Reports.label(resolution.decision()));
            html.append("<p><b>点击“处理”标记为已确认，点击“不处理”恢复为待处理。人工处理仅记录进度，不改变 AI 执行结果、覆盖状态或未完成项。</b></p>");
            if(r.result().status()!=Review.Status.COMPLETED)html.append("<p><b>本报告未获得完整成功的 AI 检查结果，请结合限制与覆盖记录处理。</b></p>");
            paragraph(html,"时间",r.createdAt());paragraph(html,"分支",r.branch());paragraph(html,"范围",r.scope());paragraph(html,"基线",r.base());paragraph(html,"提交",r.target());
            paragraph(html,"规则集",r.ruleSetName()+" / "+r.ruleSetVersion());paragraph(html,"指纹",r.fingerprint());paragraph(html,"模型",r.model());
            html.append("<h3>人工处理历史</h3>");
            if(resolution.history().isEmpty())html.append("<p>尚未处理。使用上方“处理/不处理”按钮记录处理结果。</p>");
            for(Reports.AuditEvent event:resolution.history())paragraph(html,event.at()+" · "+Reports.label(event.decision())+" · "+event.actor(),event.note().isBlank()?"无备注":event.note());
            if(r.result().status()==Review.Status.COMPLETED&&r.result().findings().isEmpty())html.append("<p>本次检查范围内未发现问题。</p>");
            String severity=selectedSeverity();
            for(int i=0;i<r.result().findings().size();i++){
                Review.Finding f=r.result().findings().get(i);if(severity!=null&&!severity.equals(f.severity()))continue;
                html.append("<h3>").append(escape(f.ruleId()+" · "+f.severity()+" · "+f.kind())).append("</h3><p>")
                    .append(escape(rootName(r.root())+" / "+f.filePath()+":"+f.startLine())).append("<br><a href='finding:").append(i).append("'>打开项目源码</a> · <a href='snapshot:").append(i).append("'>查看提交快照（")
                    .append(escape(shortRevision(f.side().equals("before")?r.base():r.target()))).append("）</a></p><pre>").append(escape(f.evidence())).append("</pre>");
                paragraph(html,"原因",f.explanation());paragraph(html,"建议",f.suggestion());paragraph(html,"与变更的关系",f.changeRelation());
                if(r.ruleDefinitions()!=null)r.ruleDefinitions().stream().filter(rule->f.ruleId().equals(rule.get("id").getAsString())).findFirst().ifPresent(rule->{
                    paragraph(html,"规则要求",rule.get("requirement").getAsString());
                    if(rule.has("source")&&rule.getAsJsonObject("source").has("excerpt"))paragraph(html,"规范依据",rule.getAsJsonObject("source").get("excerpt").getAsString());
                });
            }
            html.append("<h3>检查覆盖</h3>");
            for(Review.Coverage c:r.result().coverage())paragraph(html,c.filePath()+" / "+c.ruleId(),c.status()+" "+c.reason());
            if(!r.result().limitations().isEmpty()){html.append("<h3>限制与未完成项</h3>");r.result().limitations().forEach(l->html.append("<p>").append(escape(l)).append("</p>"));}
            html.append("</body></html>");body.setText(html.toString());body.setCaretPosition(0);
        }
        private void acknowledge(Reports.Decision decision){
            Review.Report r=history.getSelectedValue();if(r==null||view.busy())return;
            process.setEnabled(false);notProcess.setEnabled(false);
            service.acknowledge(r.id(),decision,"");
        }
        private static void paragraph(StringBuilder out,String label,String value){out.append("<p><b>").append(escape(label)).append("：</b>").append(escape(value).replace("\n","<br>")).append("</p>");}
        private static String escape(String s){return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
        private static String shortRevision(String revision){return revision==null?"无":revision.substring(0,Math.min(8,revision.length()));}
        private static String rootName(String root){if(root==null)return "未知仓库";try{return Path.of(root).getFileName().toString();}catch(RuntimeException e){return root;}}
        private void locate(int index){
            if(disposed)return;
            Review.Report r=history.getSelectedValue();if(r==null||index<0||index>=r.result().findings().size())return;
            Review.Finding f=r.result().findings().get(index);
            ApplicationManager.getApplication().executeOnPooledThread(()->{
                try{
                    if(disposed||project.isDisposed())return;
                    Path root=Path.of(r.root()).toAbsolutePath().normalize();
                    var registeredRoots=ReadAction.compute(()->git4idea.repo.GitRepositoryManager.getInstance(project).getRepositories().stream()
                        .map(repo->Path.of(repo.getRoot().getPath())).toList());
                    List<Path> projectDirectories=ReadAction.compute(()->{
                        List<Path> directories=new ArrayList<>();
                        if(project.getBasePath()!=null)directories.add(Path.of(project.getBasePath()));
                        for(var contentRoot:ProjectRootManager.getInstance(project).getContentRoots())directories.add(Path.of(contentRoot.getPath()));
                        return List.copyOf(directories);
                    });
                    if(!FindingNavigation.inProject(root,registeredRoots,projectDirectories))
                        throw new IllegalStateException("报告仓库不属于当前项目："+root+"。仅允许已登记的 Git 根，或项目根/内容根内自带 .git 的仓库；可选择查看提交快照。");
                    FindingNavigation.Target target=FindingNavigation.currentFile(root,f.filePath(),r.previousPaths());
                    var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.path());
                    if(file==null)throw new IllegalStateException("IDE 未找到当前源码文件："+target.path()+"。可选择查看提交快照。");
                    ApplicationManager.getApplication().invokeLater(()->{
                        if(disposed||project.isDisposed())return;
                        try{
                            if(!file.isValid()||file.isDirectory())throw new IllegalStateException("源码文件已删除或失效，可选择查看提交快照。");
                            var document=FileDocumentManager.getInstance().getDocument(file);
                            if(document==null)throw new IllegalStateException("文件不是可编辑的文本源码，可选择查看提交快照。");
                            FindingNavigation.Line line=FindingNavigation.currentLine(document.getText(),f.evidence(),f.startLine());
                            new OpenFileDescriptor(project,file,line.zeroBasedLine(),0).navigate(true);
                            String note=(target.renamedFallback()?"按同一仓库内的重命名路径打开。":"")+line.message();
                            if(line.match()==FindingNavigation.Match.APPROXIMATE)Messages.showWarningDialog(project,note,"源码已变化，定位仅供参考");
                            else if(!note.isBlank()){status.setText(note);status.setToolTipText(note);}
                        }catch(RuntimeException error){showError("无法打开项目源码",error);}
                    });
                }catch(Exception e){showError("无法打开项目源码",e);}
            });
        }
        private void snapshot(int index){
            if(disposed)return;
            Review.Report r=history.getSelectedValue();if(r==null||index<0||index>=r.result().findings().size())return;
            Review.Finding f=r.result().findings().get(index);
            ApplicationManager.getApplication().executeOnPooledThread(()->{
                try{
                    GitRepository git=new GitRepository(Path.of(r.root()),()->disposed||project.isDisposed());
                    String path=f.side().equals("before")&&r.previousPaths()!=null?r.previousPaths().getOrDefault(f.filePath(),f.filePath()):f.filePath();
                    String revision=f.side().equals("before")?r.base():r.target();String source=git.run("show",revision+":"+path);
                    ApplicationManager.getApplication().invokeLater(()->{if(disposed||project.isDisposed())return;
                        LightVirtualFile file=new LightVirtualFile(Path.of(path).getFileName()+"@"+shortRevision(revision)+" ["+rootName(r.root())+"]",PlainTextFileType.INSTANCE,source);file.setWritable(false);
                        new OpenFileDescriptor(project,file,f.startLine()-1,0).navigate(true);
                    });
                }catch(Exception e){showError("无法打开历史代码",e);}
            });
        }
        private void showError(String message,Exception error){SwingUtilities.invokeLater(()->{if(!disposed&&!project.isDisposed())Messages.showErrorDialog(project,message+"："+error.getMessage(),"静默代码检测");});}
        private final class ScopeTable extends AbstractTableModel {
            private List<WorkspaceScope.File> rows=List.of();
            private final String[] columns={"纳入","仓库","冻结提交","文件","匹配规则 / 排除原因"};
            @Override public int getRowCount(){return rows.size();}
            @Override public int getColumnCount(){return columns.length;}
            @Override public String getColumnName(int column){return columns[column];}
            @Override public Class<?> getColumnClass(int column){return column==0?Boolean.class:String.class;}
            @Override public boolean isCellEditable(int row,int column){return column==0&&!view.busy()&&!view.scope().preparing()&&!view.scope().stale()&&rows.get(row).selectable();}
            @Override public Object getValueAt(int row,int column){
                WorkspaceScope.File f=rows.get(row);
                return switch(column){
                    case 0->f.included();case 1->f.root();
                    case 2->view.scope().repositories().stream().filter(r->r.root().equals(f.root())).map(r->shortRevision(r.head())).findFirst().orElse("");
                    case 3->f.changeType()+" "+f.path();
                    default->!f.selectable()?f.unavailableReason():String.join(", ",f.ruleIds())+(f.included()?" · 已纳入":" · 手动排除");
                };
            }
            @Override public void setValueAt(Object value,int row,int column){if(isCellEditable(row,column))service.setIncluded(rows.get(row).root(),rows.get(row).path(),Boolean.TRUE.equals(value));}
        }
        @Override public void dispose(){disposed=true;}
    }
}
