package dev.silentreview.idea;

import dev.silentreview.core.ReportPresentation;
import dev.silentreview.core.Reports;
import dev.silentreview.core.Review;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Two levels only: a frozen review task and its original indexed findings. */
final class ReportTaskTree extends JPanel {
    record Selection(Review.Report report,int findingIndex) {}
    private record Task(Review.Report report) {}
    private record Issue(Review.Report report,ReportPresentation.IndexedFinding item) {}
    private final DefaultMutableTreeNode root=new DefaultMutableTreeNode("检查任务");
    private final DefaultTreeModel model=new DefaultTreeModel(root);
    private final JTree tree=new JTree(model){@Override public boolean getScrollableTracksViewportWidth(){return true;}};
    private final JScrollPane scroll=new JScrollPane(tree);
    private final Consumer<Selection> changed,open;
    private List<Review.Report> reports=List.of();
    private Map<String,Reports.Resolution> resolutions=Map.of();
    private Map<String,Map<Integer,Reports.FindingResolution>> findingStates=Map.of();
    private String severity;
    private JPopupMenu reportMenu;
    private boolean updating,initialized;

    ReportTaskTree(Consumer<Selection> changed,Consumer<Selection> open){
        super(new BorderLayout());this.changed=changed;this.open=open;
        setMinimumSize(new Dimension(0,0));scroll.setMinimumSize(new Dimension(0,0));
        tree.setRootVisible(false);tree.setShowsRootHandles(true);tree.setRowHeight(0);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.setCellRenderer(new DefaultTreeCellRenderer(){
            @Override public Component getTreeCellRendererComponent(JTree owner,Object value,boolean selected,boolean expanded,boolean leaf,int row,boolean focus){
                super.getTreeCellRendererComponent(owner,value,selected,expanded,leaf,row,focus);
                // Let the IDE paint the tree background and its normal selection.
                // Some themes otherwise give each HTML label a separate gray block.
                setBackgroundNonSelectionColor(null);setBackground(owner.getBackground());setBorderSelectionColor(null);setOpaque(false);
                Object item=((DefaultMutableTreeNode)value).getUserObject();
                if(item instanceof Task task){
                    Review.Report report=task.report();int total=report.result().findings().size(),confirmed=confirmedCount(report);
                    String state=handlingLabel(Reports.resolution(report.id(),resolutions).decision());
                    String progress=total==0?"无问题条目":confirmed==total?"已确认 "+confirmed+"/"+total:"待确认 "+(total-confirmed)+" · 已确认 "+confirmed+"/"+total;
                    setText("<html><b>"+escape(fit(owner,rootName(report.root())+" · "+shortRevision(report.target()),true))+"</b><br>"+
                        escape(fit(owner,state+" · "+Reports.label(report.result().status()),false))+"<br>"+escape(fit(owner,progress,false))+"</html>");
                    setToolTipText("仓库："+report.root()+" · "+report.branch()+" · "+state+" · "+Reports.label(report.result().status())+" · "+progress+" · "+ReportDetailsPanel.displayTime(report.createdAt()));
                    setIcon(UIManager.getIcon(expanded?"Tree.openIcon":"Tree.closedIcon"));
                }else if(item instanceof Issue issue){
                    Review.Finding finding=issue.item().finding();boolean approved=confirmed(issue.report(),issue.item().index());
                    String state=approved?"✓ 已确认":"待确认";
                    setText("<html>"+escape(fit(owner,state+" · "+ReportPresentation.severityLabel(finding.severity())+" · "+finding.ruleId(),false))+
                        "<br>"+escape(fit(owner,fileName(finding.filePath())+":"+finding.startLine()+" · "+ReportPresentation.firstLine(finding.explanation(),56),false))+"</html>");
                    setToolTipText("文件："+finding.filePath()+":"+finding.startLine()+" · "+finding.explanation());
                    setIcon(UIManager.getIcon("Tree.leafIcon"));
                }
                setBorder(BorderFactory.createEmptyBorder(4,3,4,3));return this;
            }
        });
        tree.addTreeSelectionListener(e->{if(!updating)changed.accept(selection());});
        tree.addComponentListener(new ComponentAdapter(){@Override public void componentResized(ComponentEvent event){tree.treeDidChange();}});
        tree.addMouseListener(new MouseAdapter(){
            @Override public void mousePressed(MouseEvent event){showReportMenu(event);}
            @Override public void mouseReleased(MouseEvent event){showReportMenu(event);}
            @Override public void mouseClicked(MouseEvent event){
                if(event.getClickCount()==2&&SwingUtilities.isLeftMouseButton(event)&&tree.getPathForLocation(event.getX(),event.getY())!=null){
                    Selection current=selection();if(current!=null&&current.findingIndex()>=0)open.accept(current);
                }
            }
        });
        add(scroll,BorderLayout.CENTER);
        JLabel hint=new JLabel("选择问题查看详情；双击打开项目源码");hint.setBorder(BorderFactory.createEmptyBorder(5,3,3,3));
        add(hint,BorderLayout.SOUTH);
    }

    void setReportMenu(JPopupMenu menu){reportMenu=menu;}
    private void showReportMenu(MouseEvent event){
        if(!event.isPopupTrigger()||reportMenu==null)return;
        TreePath path=tree.getPathForLocation(event.getX(),event.getY());if(path!=null)tree.setSelectionPath(path);
        reportMenu.show(tree,event.getX(),event.getY());
    }
    static String handlingLabel(Reports.Decision decision){return decision==null||decision==Reports.Decision.UNREVIEWED?"待处理":"已确认";}

    void showReports(List<Review.Report> next,Map<String,Reports.Resolution> nextResolutions,
                     Map<String,Map<Integer,Reports.FindingResolution>> nextStates,String nextSeverity,String preferredReport){
        if(preferredReport!=null){boolean exists=false;for(var report:next)if(report.id().equals(preferredReport)){exists=true;break;}if(!exists)preferredReport=null;}
        if(initialized&&preferredReport==null&&reports.equals(next)&&resolutions.equals(nextResolutions)&&findingStates.equals(nextStates)&&Objects.equals(severity,nextSeverity))return;
        Selection previous=selection();Point position=scroll.getViewport().getViewPosition();Set<String> expanded=new HashSet<>();
        for(int i=0;i<root.getChildCount();i++){
            var node=(DefaultMutableTreeNode)root.getChildAt(i);
            if(tree.isExpanded(new TreePath(node.getPath())))expanded.add(((Task)node.getUserObject()).report().id());
        }
        reports=List.copyOf(next);resolutions=nextResolutions;findingStates=nextStates;severity=nextSeverity;initialized=true;
        updating=true;root.removeAllChildren();DefaultMutableTreeNode chosen=null,fallback=null;
        for(Review.Report report:reports){
            DefaultMutableTreeNode task=new DefaultMutableTreeNode(new Task(report));root.add(task);
            if(fallback==null)fallback=task;
            boolean preferred=report.id().equals(preferredReport),same=previous!=null&&previous.report().id().equals(report.id());
            if(preferred||(same&&previous.findingIndex()<0&&preferredReport==null))chosen=task;
            for(var group:ReportPresentation.groups(report))for(var item:group.findings()){
                if(severity!=null&&!severity.equals(item.finding().severity()))continue;
                DefaultMutableTreeNode issue=new DefaultMutableTreeNode(new Issue(report,item));task.add(issue);
                if(preferred&&chosen==task)chosen=issue;
                if(same&&item.index()==previous.findingIndex()&&preferredReport==null)chosen=issue;
            }
            if(same&&chosen==null)chosen=task.getChildCount()>0?(DefaultMutableTreeNode)task.getChildAt(0):task;
        }
        if(chosen==null){chosen=fallback;if(chosen!=null&&chosen.getChildCount()>0)chosen=(DefaultMutableTreeNode)chosen.getChildAt(0);}
        model.reload();
        for(int i=0;i<root.getChildCount();i++){
            var node=(DefaultMutableTreeNode)root.getChildAt(i);String id=((Task)node.getUserObject()).report().id();
            if(expanded.contains(id)||id.equals(preferredReport))tree.expandPath(new TreePath(node.getPath()));
        }
        if(chosen!=null){TreePath path=new TreePath(chosen.getPath());tree.setSelectionPath(path);
            if(preferredReport!=null||previous==null)tree.scrollPathToVisible(path);else scroll.getViewport().setViewPosition(position);}
        updating=false;changed.accept(selection());
    }

    Selection selection(){
        Object selected=tree.getLastSelectedPathComponent();if(!(selected instanceof DefaultMutableTreeNode node))return null;
        Object value=node.getUserObject();
        if(value instanceof Task task)return new Selection(task.report(),-1);
        if(value instanceof Issue issue)return new Selection(issue.report(),issue.item().index());
        return null;
    }
    private boolean confirmed(Review.Report report,int index){
        var state=Reports.findingResolution(report,index,findingStates.getOrDefault(report.id(),Map.of()));return state!=null&&state.confirmed();
    }
    private int confirmedCount(Review.Report report){int count=0;for(int i=0;i<report.result().findings().size();i++)if(confirmed(report,i))count++;return count;}
    private static String shortRevision(String value){return value==null?"":value.substring(0,Math.min(8,value.length()));}
    private static String rootName(String value){String name=fileName(value);return name.isEmpty()?value:name;}
    private static String fileName(String value){int slash=Math.max(value.lastIndexOf('/'),value.lastIndexOf('\\'));return value.substring(slash+1);}
    private static String escape(String value){return value==null?"":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String fit(JTree tree,String value,boolean bold){
        int available=tree.getVisibleRect().width;if(available<=0)available=300;
        available=Math.max(70,available-75);Font font=bold?tree.getFont().deriveFont(Font.BOLD):tree.getFont();FontMetrics metrics=tree.getFontMetrics(font);
        if(metrics.stringWidth(value)<=available)return value;
        int end=value.length();while(end>0&&metrics.stringWidth(value.substring(0,end)+"…")>available)end=value.offsetByCodePoints(end,-1);
        return value.substring(0,end)+"…";
    }
}
