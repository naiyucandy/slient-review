package dev.silentreview.idea;

import com.intellij.icons.AllIcons;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.*;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.*;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.ui.content.ContentFactory;
import dev.silentreview.core.*;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
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
        private final ReportTaskTree reportTree=new ReportTaskTree(selection->render(),selection->locate(selection.findingIndex()));
        private final ReportDetailsPanel reportDetails=new ReportDetailsPanel(this::locate,this::approveFinding);
        private final JComboBox<String> decisionFilter=new JComboBox<>(new String[]{"全部处理状态","待处理","已确认"});
        private final JComboBox<String> severityFilter=new JComboBox<>(new String[]{"全部严重级别","error","warning","info"});
        private final JTabbedPane tabs=new JTabbedPane();
        private final JLabel scopeCounts=new JLabel("尚未准备范围。先刷新范围，再选择文件并开始审查。");
        private final JTextArea scopeInfo=new JTextArea(4,30);
        private final ScopeTable scopeModel=new ScopeTable();
        private final JTable scopeTable=new JTable(scopeModel);
        private final List<JButton> scopeActions=new ArrayList<>();
        private JButton start,stop;
        private ReviewService.View view;
        private volatile boolean disposed;
        Panel(Project project){
            super(new BorderLayout(8,8));this.project=project;service=ReviewService.get(project);view=service.view();
            JPanel toolbar=new JPanel(new BorderLayout());
            JPanel primary=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));
            JButton prepare=button(primary,"准备当前提交",()->service.manual(null));
            prepare.setToolTipText("自动识别当前 IDEA 项目结构下的全部 Git 仓库，准备各仓库的当前提交");
            scopeActions.add(prepare);
            JButton refresh=button(primary,"刷新范围",()->service.prepareScope(view.scope().baseline()));
            refresh.setToolTipText("重新识别当前项目的全部 Git 仓库并刷新审查范围");
            scopeActions.add(refresh);
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
            JButton includeAll=button(actions,"全部纳入",()->service.setAllIncluded(true));
            includeAll.setToolTipText("纳入当前项目全部仓库中可审查的文件");
            JButton excludeAll=button(actions,"全部排除",()->service.setAllIncluded(false));
            excludeAll.setToolTipText("排除当前项目全部仓库中的文件");
            scopeActions.add(includeAll);scopeActions.add(excludeAll);
            start=button(actions,"开始审查",service::startPreparedReview);
            start.setToolTipText("审查当前项目全部仓库中已纳入的文件");
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
            panel.add(new JScrollPane(scopeInfo),BorderLayout.SOUTH);installScopeMenu();return panel;
        }
        private JPanel reportPanel(){
            JPanel panel=new JPanel(new BorderLayout(5,5));
            JPanel filters=new JPanel(new GridLayout(0,1,5,5));
            filters.add(decisionFilter);filters.add(severityFilter);
            severityFilter.setRenderer(new DefaultListCellRenderer(){
                @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                    return super.getListCellRendererComponent(list,value instanceof String valueText&&!valueText.equals("全部严重级别")?ReportPresentation.severityLabel(valueText):value,index,selected,focus);
                }
            });
            decisionFilter.addActionListener(e->applyFilters());severityFilter.addActionListener(e->applyFilters());
            JSplitPane split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,reportTree,reportDetails);
            split.setResizeWeight(0.30);split.setDividerLocation(310);split.setContinuousLayout(true);split.setOneTouchExpandable(true);split.setBorder(null);
            panel.add(filters,BorderLayout.NORTH);panel.add(split,BorderLayout.CENTER);installReportMenu();return panel;
        }
        private Review.Report selectedReport(){var selected=reportTree.selection();return selected==null?null:selected.report();}
        private void installReportMenu(){
            JPopupMenu menu=new JPopupMenu();JMenuItem export=new JMenuItem("导出所选报告");export.addActionListener(e->export());menu.add(export);
            JMenuItem clear=new JMenuItem("清理历史报告…");clear.addActionListener(e->{if(Messages.showYesNoDialog(project,"删除本项目的本地报告和人工处理历史？","清理报告",null)==Messages.YES)service.clearHistory();});menu.add(clear);
            menu.addPopupMenuListener(new PopupMenuListener(){public void popupMenuWillBecomeVisible(PopupMenuEvent event){export.setEnabled(selectedReport()!=null);clear.setEnabled(!view.busy()&&!view.reports().isEmpty());}public void popupMenuWillBecomeInvisible(PopupMenuEvent event){}public void popupMenuCanceled(PopupMenuEvent event){}});
            reportTree.setReportMenu(menu);
        }
        private void installScopeMenu(){
            JPopupMenu menu=new JPopupMenu();JMenuItem branch=new JMenuItem("准备分支范围…");
            branch.addActionListener(e->{String reference=Messages.showInputDialog(project,"输入公共分支引用，或 @{upstream} 使用各仓库的本地上游引用。不会自动更新远端引用。","选择检查基线",null);if(reference!=null&&!reference.isBlank())service.manual(reference.strip());});menu.add(branch);
            JMenuItem diff=new JMenuItem("查看选中文件 Diff");diff.addActionListener(e->scopeDiff());menu.add(diff);
            menu.addPopupMenuListener(new PopupMenuListener(){public void popupMenuWillBecomeVisible(PopupMenuEvent event){branch.setEnabled(!view.busy());diff.setEnabled(scopeTable.getSelectedRow()>=0);}public void popupMenuWillBecomeInvisible(PopupMenuEvent event){}public void popupMenuCanceled(PopupMenuEvent event){}});
            scopeTable.addMouseListener(new MouseAdapter(){
                @Override public void mousePressed(MouseEvent event){showMenu(event);}
                @Override public void mouseReleased(MouseEvent event){showMenu(event);}
                private void showMenu(MouseEvent event){
                    if(!event.isPopupTrigger())return;
                    int row=scopeTable.rowAtPoint(event.getPoint());
                    if(row>=0)scopeTable.setRowSelectionInterval(row,row);else scopeTable.clearSelection();
                    menu.show(scopeTable,event.getX(),event.getY());
                }
            });
        }
        private static JButton button(JPanel parent,String title,Runnable action){
            JButton b=new JButton(title);b.addActionListener(e->action.run());
            b.setMargin(new Insets(2,9,2,9));b.setFocusable(false);parent.add(b);return b;
        }
        private void refresh(ReviewService.View next){
            if(disposed)return;
            if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(()->refresh(next));return;}
            String newestBefore=view.reports().isEmpty()?null:view.reports().get(0).id();view=next;
            status.setText(Reports.label(next.status())+"："+ReportPresentation.firstLine(next.message(),88));status.setToolTipText("<html>"+escape(next.message()).replace("\n","<br>")+"</html>");
            scopeActions.forEach(b->b.setEnabled(!next.busy()&&!next.scope().preparing()));
            stop.setEnabled(next.busy());
            updateScope();
            String newest=next.reports().isEmpty()?null:next.reports().get(0).id();
            String preferred=newest!=null&&!Objects.equals(newest,newestBefore)?newest:null;applyFilters(preferred);
            if(preferred!=null)tabs.setSelectedIndex(1);
            else if(next.scope().preparing())tabs.setSelectedIndex(0);
        }
        private void updateScope(){
            WorkspaceScope.State scope=view.scope();
            List<WorkspaceScope.Repository> repositories=scope.repositories();
            int files=repositories.stream().mapToInt(r->r.files().size()).sum();
            int included=(int)repositories.stream().flatMap(r->r.files().stream()).filter(WorkspaceScope.File::included).count();
            int eligible=(int)repositories.stream().flatMap(r->r.files().stream()).filter(WorkspaceScope.File::selectable).count();
            int failed=(int)repositories.stream().filter(r->r.error()!=null&&!r.error().isBlank()).count();
            String state=scope.preparing()?" · 准备中":scope.stale()?" · 范围已过期，请刷新":"";
            if(scope.preparing()&&scope.repositories().isEmpty()){
                scopeCounts.setText("<html>正在逐仓库收集范围…</html>");
            }else if(repositories.isEmpty()){
                scopeCounts.setText("<html>尚无仓库；点击“准备当前提交”自动识别当前项目的 Git 仓库。"+state+"</html>");
            }else{
                scopeCounts.setText("<html>当前项目："+repositories.size()+" 个仓库 / "+files+" 个变更文件；<br>已纳入 "+included+"，已排除 "+(files-included)+"，可选 "+eligible+"，失败仓库 "+failed+state+"</html>");
            }
            scopeModel.rows=repositories.stream().flatMap(r->r.files().stream()).toList();scopeModel.fireTableDataChanged();
            StringBuilder info=new StringBuilder("审查使用此处冻结的提交；不含尚未提交的编辑。\n");
            view.discoveryNotes().forEach(note->info.append(note).append('\n'));
            view.discoveryLimitations().forEach(warning->info.append("发现提示：").append(warning).append('\n'));
            if(repositories.isEmpty())info.append("尚未准备当前项目的仓库范围。\n");
            for(var repo:repositories)info.append(repo.root()).append("\n  ").append(repo.branch()).append(" · HEAD ").append(repo.head()).append(" · 基线 ").append(repo.baseline()).append(" · ").append(repo.description()).append(repo.error()==null||repo.error().isBlank()?"":" · 失败："+repo.error()).append('\n');
            scopeInfo.setText(info.toString());scopeInfo.setCaretPosition(0);
            boolean hasIncluded=repositories.stream().flatMap(r->r.files().stream()).anyMatch(f->f.included()&&f.selectable());
            start.setEnabled(!view.busy()&&scope.ready()&&hasIncluded);
        }
        private void applyFilters(){applyFilters(null);}
        private void applyFilters(String preferredReport){
            Reports.Decision decision=decisionFilter.getSelectedIndex()==1?Reports.Decision.UNREVIEWED:decisionFilter.getSelectedIndex()==2?Reports.Decision.CONFIRMED:null;
            String severity=selectedSeverity();
            reportTree.showReports(ReportFilters.selectForDisplay(view.reports(),view.resolutions(),null,decision,severity),view.resolutions(),view.findingResolutions(),severity,preferredReport);render();
        }
        private String selectedSeverity(){return severityFilter.getSelectedIndex()<=0?null:(String)severityFilter.getSelectedItem();}
        private void render(){
            Review.Report report=selectedReport();
            reportDetails.showReport(report,report==null?null:Reports.resolution(report.id(),view.resolutions()),
                report==null?Map.of():view.findingResolutions().getOrDefault(report.id(),Map.of()),view.busy());
            var selected=reportTree.selection();reportDetails.selectFinding(selected==null?-1:selected.findingIndex());
        }
        private void approveFinding(int findingIndex,boolean confirmed){
            Review.Report report=selectedReport();if(report==null){reportDetails.approvalFinished();return;}
            service.acknowledgeFinding(report.id(),findingIndex,confirmed).whenComplete((saved,error)->
                ApplicationManager.getApplication().invokeLater(()->{if(!disposed&&!project.isDisposed()){reportDetails.approvalFinished();render();}}));
        }
        private static String escape(String s){return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
        private static String shortRevision(String revision){return revision==null?"无":revision.substring(0,Math.min(8,revision.length()));}
        private static String rootName(String root){if(root==null)return "未知仓库";try{return Path.of(root).getFileName().toString();}catch(RuntimeException e){return root;}}
        private void export(){
            Review.Report r=selectedReport();if(r==null)return;Reports.Resolution resolution=Reports.resolution(r.id(),view.resolutions());
            JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File("silent-review-"+rootName(r.root())+"-"+shortRevision(r.target())+".md"));
            if(chooser.showSaveDialog(this)==JFileChooser.APPROVE_OPTION){
                Path output=chooser.getSelectedFile().toPath();
                Map<Integer,Reports.FindingResolution> findingStates=view.findingResolutions().getOrDefault(r.id(),Map.of());
                ApplicationManager.getApplication().executeOnPooledThread(()->{try{Files.writeString(output,Reports.markdown(r,resolution,findingStates),StandardCharsets.UTF_8);}catch(Exception e){showError("报告导出失败",e);}});
            }
        }
        private void scopeDiff(){
            if(disposed)return;
            int row=scopeTable.getSelectedRow();if(row<0)return;
            WorkspaceScope.File file=scopeModel.rows.get(scopeTable.convertRowIndexToModel(row));
            WorkspaceScope.Repository repository=view.scope().repositories().stream().filter(r->r.root().equals(file.root())).findFirst().orElse(null);if(repository==null)return;
            ApplicationManager.getApplication().executeOnPooledThread(()->{
                try{
                    if(disposed||project.isDisposed())return;
                    GitRepository.ChangedFile change=service.scopeFile(file.root(),repository.head(),file.path());
                    ApplicationManager.getApplication().invokeLater(()->{
                        if(disposed||project.isDisposed())return;
                        var factory=DiffContentFactory.getInstance();
                        DiffManager.getInstance().showDiff(project,new SimpleDiffRequest(rootName(file.root())+" / "+file.path()+"（审查提交快照，不含当前编辑）",factory.create(project,change.before(),PlainTextFileType.INSTANCE),factory.create(project,change.after(),PlainTextFileType.INSTANCE),"基线快照 "+shortRevision(repository.baseline())+" / "+change.beforePath(),"目标快照 "+shortRevision(repository.head())+" / "+change.path()));
                    });
                }catch(Exception e){showError("无法打开范围 Diff",e);}
            });
        }
        private void locate(int index){
            if(disposed)return;
            Review.Report r=selectedReport();if(r==null||index<0||index>=r.result().findings().size())return;
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
                        throw new IllegalStateException("报告仓库不属于当前项目："+root+"。请将该仓库添加至当前项目后重试。");
                    FindingNavigation.Target target=FindingNavigation.currentFile(root,f.filePath(),r.previousPaths());
                    var file=LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.path());
                    if(file==null)throw new IllegalStateException("IDE 未找到当前源码文件："+target.path()+"。请确认文件仍在当前工作区内。");
                    ApplicationManager.getApplication().invokeLater(()->{
                        if(disposed||project.isDisposed())return;
                        try{
                            if(!file.isValid()||file.isDirectory())throw new IllegalStateException("源码文件已删除或失效，请确认当前工作区中的文件路径。");
                            var document=FileDocumentManager.getInstance().getDocument(file);
                            if(document==null)throw new IllegalStateException("文件不是可编辑的文本源码，无法按文本定位问题代码。");
                            FindingNavigation.Line line=FindingNavigation.currentLine(document.getText(),f.evidence(),f.startLine());
                            var editor=FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project,file,line.zeroBasedLine(),0),true);
                            if(editor==null||editor.isDisposed()||editor.getDocument()!=document)
                                throw new IllegalStateException("未能打开对应源码文档，已停止行选择，避免定位到其他编辑器。");
                            var selection=FindingNavigation.selection(document.getText(),line,f.evidence(),f.startLine(),f.endLine());
                            editor.getCaretModel().removeSecondaryCarets();
                            editor.getCaretModel().moveToOffset(selection.startOffset());
                            editor.getSelectionModel().setSelection(selection.startOffset(),selection.endOffset());
                            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                            String note=(target.renamedFallback()?"按同一仓库内的重命名路径打开。":"")+line.message();
                            String lines=selection.zeroBasedStartLine()==selection.zeroBasedEndLine()?"第 "+(selection.zeroBasedStartLine()+1)+" 行":"第 "+(selection.zeroBasedStartLine()+1)+"–"+(selection.zeroBasedEndLine()+1)+" 行";
                            status.setText("已定位项目源码："+target.path().getFileName()+" · "+lines+(note.isBlank()?"":" · "+note));
                            status.setToolTipText(target.path()+" · "+lines+(note.isBlank()?"":" · "+note));
                            if(line.match()==FindingNavigation.Match.APPROXIMATE)Messages.showWarningDialog(project,note,"源码已变化，定位仅供参考");
                        }catch(RuntimeException error){showError("无法打开项目源码",error);}
                    });
                }catch(Exception e){showError("无法打开项目源码",e);}
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
