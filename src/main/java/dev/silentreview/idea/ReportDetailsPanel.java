package dev.silentreview.idea;

import com.google.gson.JsonObject;
import dev.silentreview.core.ReportPresentation;
import dev.silentreview.core.Reports;
import dev.silentreview.core.Review;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;

/** One continuous report page. AI text is plain text; only our code spans can navigate. */
final class ReportDetailsPanel extends JPanel {
    private static final String CODE_LINK="silent-review.findingIndex";
    private static final String OPEN_CODE="silent-review.openCode";
    private final JLabel headline=new JLabel("选择一份报告查看结果");
    private final JLabel counts=new JLabel(" ");
    private final JLabel notice=new JLabel(" ");
    private final JPanel summary=new JPanel(new GridLayout(0,1,0,5));
    private final JTextPane detail=textPane();
    private final JScrollPane page=new JScrollPane(detail);
    private final JPanel actions=new JPanel(new BorderLayout(5,0));
    private final IntConsumer openProject;
    private final BiConsumer<Integer,Boolean> approveFinding;
    private final JButton approve=new JButton("Approve · 确认已解决");
    private final JLabel approvalStatus=new JLabel("选择问题后可确认解决状态");
    private Map<Integer,Reports.FindingResolution> findingStates=Map.of();
    private boolean busy,approvalPending,initialized;
    private Review.Report report;
    private Reports.Resolution resolution;
    private int selectedFinding=-1;

    ReportDetailsPanel(IntConsumer openProject,BiConsumer<Integer,Boolean> approveFinding){
        super(new BorderLayout(0,8));this.openProject=openProject;this.approveFinding=approveFinding;
        setBorder(BorderFactory.createEmptyBorder(8,8,8,8));setMinimumSize(new Dimension(0,0));
        headline.setFont(headline.getFont().deriveFont(Font.BOLD,headline.getFont().getSize2D()+2));
        summary.add(headline);summary.add(counts);summary.add(notice);add(summary,BorderLayout.NORTH);
        page.setMinimumSize(new Dimension(0,0));page.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(page,BorderLayout.CENTER);
        actions.setBorder(BorderFactory.createEmptyBorder(8,0,0,0));
        actions.add(approvalStatus,BorderLayout.CENTER);actions.add(approve,BorderLayout.EAST);add(actions,BorderLayout.SOUTH);
        approve.addActionListener(e->{
            if(report==null||selectedFinding<0||busy||approvalPending)return;
            var state=Reports.findingResolution(report,selectedFinding,findingStates);
            boolean confirmed=state!=null&&state.confirmed();approvalPending=true;refreshApproval();
            approveFinding.accept(selectedFinding,!confirmed);
        });
        installCodeNavigation();
        showReport(null,null,Map.of(),false);
    }

    void showReport(Review.Report next,Reports.Resolution nextResolution,Map<Integer,Reports.FindingResolution> nextStates,boolean working){
        boolean changed=!initialized||!Objects.equals(report,next);
        boolean stateChanged=!findingStates.equals(nextStates),resolutionChanged=!Objects.equals(resolution,nextResolution);
        report=next;resolution=nextResolution;findingStates=Map.copyOf(nextStates);busy=working;initialized=true;
        // One save can be in flight for this view. Keep the guard while users
        // switch tasks/findings; only its completion may unlock another save.
        if(changed)selectedFinding=-1;
        if(changed||stateChanged||resolutionChanged)renderPage(changed);
        refreshSummary();refreshApproval();
    }

    void selectFinding(int index){
        int value=report!=null&&index>=0&&index<findings().size()?index:-1;
        if(selectedFinding==value)return;
        selectedFinding=value;renderPage(true);refreshSummary();refreshApproval();
    }

    void approvalFinished(){approvalPending=false;refreshApproval();}

    private void refreshApproval(){
        boolean selected=report!=null&&selectedFinding>=0;
        var state=selected?Reports.findingResolution(report,selectedFinding,findingStates):null;
        boolean confirmed=state!=null&&state.confirmed();actions.setVisible(selected);
        approve.setEnabled(selected&&!busy&&!approvalPending);
        approve.setText(approvalPending?"正在保存…":confirmed?"撤销确认":"Approve · 确认已解决");
        approvalStatus.setText(!selected?" ":confirmed?"已确认 · "+displayTime(state.updatedAt()):"待确认");
        approvalStatus.setToolTipText(state==null?"人工确认解决状态，不改变 AI 的检查结果。":state.actor()+" · "+state.note());
    }

    private void refreshSummary(){
        summary.setVisible(selectedFinding<0);
        if(report==null){
            headline.setText("当前筛选下没有报告");counts.setText("先准备范围，再选择文件并开始审查。");notice.setText("选择左侧检查任务查看完整报告，选择问题查看简要详情。");
            notice.setForeground(UIManager.getColor("Label.foreground"));return;
        }
        var result=ReportPresentation.summarize(report);
        headline.setText(Reports.label(report.result().status())+" · "+result.findings()+" 项问题");
        headline.setToolTipText(ReportPresentation.conclusion(report));
        counts.setText("错误 "+result.errors()+"   警告 "+result.warnings()+"   提示 "+result.infos()+
            (result.otherSeverities()>0?"   其他 "+result.otherSeverities():"")+"   ·   "+result.files()+" 个文件");
        notice.setText("问题已确认 "+Reports.confirmedFindingCount(report,findingStates)+"/"+result.findings()+
            "   ·   已检查 "+result.reviewedChecks()+" 项"+(result.incompleteChecks()>0?"   ·   待补查 "+result.incompleteChecks()+" 项":""));
        notice.setToolTipText(ReportPresentation.conclusion(report));
        notice.setForeground(result.needsConfirmation()?warningColor():UIManager.getColor("Label.foreground"));
    }

    private void renderPage(boolean startAtTop){
        int caret=detail.getCaretPosition(),scroll=page.getVerticalScrollBar().getValue();
        StyledDocument doc=new DefaultStyledDocument();
        if(report==null)section(doc,"查看报告","选择左侧检查任务查看完整报告，或选择具体问题查看规则、问题代码和问题描述。",false);
        else if(selectedFinding>=0)appendFinding(doc,selectedFinding,false);
        else appendTask(doc);
        detail.setDocument(doc);
        detail.setCaretPosition(startAtTop?0:Math.min(caret,doc.getLength()));
        page.getVerticalScrollBar().setValue(startAtTop?0:scroll);
        if(!startAtTop)SwingUtilities.invokeLater(()->{if(detail.getStyledDocument()==doc)page.getVerticalScrollBar().setValue(scroll);});
    }

    private void appendTask(StyledDocument doc){
        section(doc,"检查结论",ReportPresentation.conclusion(report),false);
        if(!findings().isEmpty()){
            for(var group:ReportPresentation.groups(report))for(var item:group.findings()){
                var state=Reports.findingResolution(report,item.index(),findingStates);
                heading(doc,"问题 "+(item.index()+1)+" · "+(state!=null&&state.confirmed()?"已确认解决":"待确认"));
                appendFinding(doc,item.index(),true);
            }
        }
        List<Review.Coverage> checks=report.result().coverage()==null?List.of():report.result().coverage();
        heading(doc,"检查覆盖");
        if(checks.isEmpty())body(doc,"本次报告没有检查覆盖记录。",false);
        else{
            body(doc,"已检查表示该项规则执行完成，不表示文件没有问题。",false);
            checks.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((Review.Coverage row)->"reviewed".equals(row.status())?1:0)
                    .thenComparing(row->text(row.filePath())).thenComparing(row->text(row.ruleId())))
                .forEach(row->{
                    String reason=text(row.reason());
                    if(reason.isBlank())reason="reviewed".equals(row.status())?"该项规则已执行。":"这项检查尚未完成，请补充条件后重新检查。";
                    body(doc,ReportPresentation.coverageLabel(row.status())+" · "+text(row.ruleId())+" · "+text(row.filePath())+"\n"+reason,false);
                });
        }
        List<String> limits=report.result().limitations()==null?List.of():report.result().limitations();
        if(!limits.isEmpty())section(doc,"检查限制与待补查",String.join("\n",limits),false);
        appendMetadata(doc);
    }

    private void appendFinding(StyledDocument doc,int index,boolean complete){
        Review.Finding finding=findings().get(index);
        section(doc,"规则",ruleText(finding),false);
        heading(doc,"问题代码");
        appendLink(doc,text(finding.filePath())+":"+finding.startLine()+(finding.endLine()>finding.startLine()?"–"+finding.endLine():""),index,false);
        append(doc,"\n",style(false));
        if(text(finding.evidence()).isBlank())body(doc,"报告未提供代码片段，请点击文件位置查看项目源码。",false);
        else{appendLink(doc,finding.evidence(),index,true);append(doc,"\n\n",style(false));}
        String description=text(finding.explanation());
        if(!text(finding.suggestion()).isBlank())description+="\n\n建议："+finding.suggestion();
        if(complete&&!text(finding.changeRelation()).isBlank())description+="\n\n与本次变更的关系："+finding.changeRelation();
        section(doc,"问题描述",description,false);
    }

    private String ruleText(Review.Finding finding){
        StringBuilder value=new StringBuilder(text(finding.ruleId()));
        value.append(" · ").append(ReportPresentation.severityLabel(finding.severity()));
        value.append(" · ").append(ReportPresentation.kindLabel(finding.kind()));
        if(report.ruleDefinitions()!=null)for(JsonObject rule:report.ruleDefinitions()){
            if(rule==null||!finding.ruleId().equals(property(rule,"id")))continue;
            String requirement=property(rule,"requirement"),excerpt="";
            if(rule.has("source")&&rule.get("source").isJsonObject())excerpt=property(rule.getAsJsonObject("source"),"excerpt");
            if(!requirement.isBlank())value.append("\n").append(requirement);
            if(!excerpt.isBlank()&&!excerpt.equals(requirement))value.append("\n规范依据：").append(excerpt);
            break;
        }
        return value.toString();
    }

    private void appendMetadata(StyledDocument doc){
        section(doc,"报告信息","时间："+displayTime(report.createdAt())+"\n仓库："+text(report.root())+"\n分支："+text(report.branch())+
            "\nAI 执行："+Reports.label(report.result().status())+"\n人工处理："+(resolution==null?"待处理":ReportTaskTree.handlingLabel(resolution.decision())),false);
        section(doc,"审查范围",report.scope(),false);
        section(doc,"提交与规则","基线："+text(report.base())+"\n目标提交："+text(report.target())+"\n规则集："+text(report.ruleSetName())+" / "+text(report.ruleSetVersion())+
            "\n规则指纹："+text(report.fingerprint())+"\n审查指令："+text(report.instructionVersion())+"\n模型："+text(report.model())+"\n报告 ID："+text(report.id()),false);
        if(report.usage()!=null)section(doc,"接口用量",report.usage().toString(),true);
        heading(doc,"确认记录");
        body(doc,"人工确认只记录问题处理进度，不会改变 AI 执行结果或检查覆盖。",false);
        boolean recorded=false;
        if(resolution!=null)for(var event:resolution.history()){
            recorded=true;
            body(doc,displayTime(event.at())+" · "+Reports.label(event.decision())+" · "+text(event.actor())+
                (text(event.note()).isBlank()?"":"\n"+event.note()),false);
        }
        for(int index=0;index<findings().size();index++){
            var state=Reports.findingResolution(report,index,findingStates);if(state==null)continue;
            for(var event:state.history()){
                recorded=true;
                body(doc,"问题 "+(index+1)+" · "+displayTime(event.at())+" · "+(event.confirmed()?"已确认解决":"撤销确认")+" · "+text(event.actor())+
                    (text(event.note()).isBlank()?"":"\n"+event.note()),false);
            }
        }
        if(!recorded)body(doc,"尚无确认记录。",false);
    }

    private List<Review.Finding> findings(){return report==null||report.result().findings()==null?List.of():report.result().findings();}

    private void installCodeNavigation(){
        detail.getAccessibleContext().setAccessibleDescription("文件位置和问题代码可点击打开当前项目源码，也可将光标移至代码链接后按 Enter。");
        MouseAdapter navigation=new MouseAdapter(){
            private Point pressed;
            private boolean dragged;
            @Override public void mousePressed(MouseEvent e){pressed=SwingUtilities.isLeftMouseButton(e)?e.getPoint():null;dragged=false;}
            @Override public void mouseDragged(MouseEvent e){if(pressed!=null&&pressed.distance(e.getPoint())>3)dragged=true;}
            @Override public void mouseClicked(MouseEvent e){
                if(SwingUtilities.isLeftMouseButton(e)&&e.getClickCount()==1&&!dragged){int offset=codeOffsetAt(e.getPoint());if(offset>=0)openCodeAt(offset);}
            }
            @Override public void mouseMoved(MouseEvent e){
                boolean link=codeOffsetAt(e.getPoint())>=0;
                detail.setCursor(Cursor.getPredefinedCursor(link?Cursor.HAND_CURSOR:Cursor.TEXT_CURSOR));
                detail.setToolTipText(link?"点击定位当前项目源码；源码变化后按代码证据重新定位。也可将光标移至此处后按 Enter。":null);
            }
            @Override public void mouseExited(MouseEvent e){detail.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));detail.setToolTipText(null);}
        };
        detail.addMouseListener(navigation);detail.addMouseMotionListener(navigation);
        detail.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),OPEN_CODE);
        detail.getActionMap().put(OPEN_CODE,new AbstractAction(){@Override public void actionPerformed(ActionEvent e){openCodeAt(detail.getCaretPosition());}});
    }

    /** The only link target is a validated index into the currently displayed report. */
    boolean openCodeAt(int offset){
        int index=codeIndexAt(offset);
        if(report==null||index<0||index>=findings().size())return false;
        openProject.accept(index);return true;
    }

    private int codeIndexAt(int offset){
        StyledDocument doc=detail.getStyledDocument();
        if(offset<0||offset>=doc.getLength())return -1;
        Object target=doc.getCharacterElement(offset).getAttributes().getAttribute(CODE_LINK);
        return target instanceof Integer index?index:-1;
    }

    private int codeOffsetAt(Point point){
        // Swing returns the nearest caret, which can be after the character
        // under the pointer. Check both adjacent glyphs so their right halves
        // remain clickable, without treating trailing whitespace as a link.
        int caret=detail.viewToModel2D(point);
        for(int offset:new int[]{caret,caret-1}){
            if(codeIndexAt(offset)<0)continue;
            try{
                String character=detail.getDocument().getText(offset,1);if("\n".equals(character)||"\r".equals(character))continue;
                Rectangle2D current=detail.modelToView2D(offset),next=detail.modelToView2D(offset+1);
                if(current==null)continue;
                double width=next!=null&&Math.abs(next.getY()-current.getY())<1?next.getX()-current.getX():0;
                if(width<=0){
                    StyledDocument doc=detail.getStyledDocument();
                    Font font=doc.getFont(doc.getCharacterElement(offset).getAttributes());
                    width=detail.getFontMetrics(font).stringWidth(character);
                }
                if(point.getY()>=current.getY()&&point.getY()<current.getMaxY()&&point.getX()>=current.getX()&&point.getX()<current.getX()+width)return offset;
            }catch(BadLocationException invalid){return -1;}
        }
        return -1;
    }

    private static JTextPane textPane(){
        JTextPane pane=new JTextPane(){@Override public boolean getScrollableTracksViewportWidth(){return true;}};
        pane.setEditable(false);pane.setFont(labelFont());
        pane.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));pane.setMinimumSize(new Dimension(0,0));return pane;
    }
    private static void section(StyledDocument doc,String title,String value,boolean code){heading(doc,title);body(doc,value,code);}
    private static void heading(StyledDocument doc,String title){
        SimpleAttributeSet attributes=style(false);StyleConstants.setBold(attributes,true);
        append(doc,text(title)+"\n",attributes);
    }
    private static void body(StyledDocument doc,String value,boolean code){append(doc,text(value)+"\n\n",style(code));}
    private static void appendLink(StyledDocument doc,String value,int index,boolean code){
        SimpleAttributeSet attributes=style(code);attributes.addAttribute(CODE_LINK,index);
        Color color=UIManager.getColor("Link.activeForeground");if(color==null)color=UIManager.getColor("Link.foreground");
        StyleConstants.setForeground(attributes,color==null?new Color(dark()?0x91BFFF:0x275DAD):color);
        StyleConstants.setUnderline(attributes,true);append(doc,text(value),attributes);
    }
    private static SimpleAttributeSet style(boolean code){
        SimpleAttributeSet attributes=new SimpleAttributeSet();Font font=labelFont();
        StyleConstants.setFontFamily(attributes,code?Font.MONOSPACED:font.getFamily());StyleConstants.setFontSize(attributes,font.getSize());return attributes;
    }
    private static void append(StyledDocument doc,String value,AttributeSet attributes){
        try{doc.insertString(doc.getLength(),value,attributes);}
        catch(BadLocationException impossible){throw new IllegalStateException(impossible);}
    }
    private static String property(JsonObject value,String key){
        return value.has(key)&&value.get(key).isJsonPrimitive()?value.get(key).getAsString():"";
    }
    private static String text(String value){return value==null?"":value;}
    private static Font labelFont(){Font font=UIManager.getFont("Label.font");return font==null?new Font(Font.SANS_SERIF,Font.PLAIN,12):font;}
    private static boolean dark(){Color background=UIManager.getColor("Panel.background");return background!=null&&background.getRed()+background.getGreen()+background.getBlue()<384;}
    private static Color warningColor(){return new Color(dark()?0xEDC16B:0x8A5A00);}
    static String displayTime(String value){
        try{return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value));}
        catch(RuntimeException invalid){return text(value);}
    }
}
