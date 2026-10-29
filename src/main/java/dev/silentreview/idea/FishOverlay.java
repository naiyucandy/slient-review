package dev.silentreview.idea;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import dev.silentreview.core.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.*;

/** Native Swing overlay: no Python process, no always-on-top desktop window. */
final class FishOverlay implements Disposable {
    private final Project project;
    private final ReviewService service;
    private final Fish component=new Fish();
    private JLayeredPane parent;
    private boolean disposed;
    private int attempts;
    private int previousParentWidth,previousParentHeight;
    private final javax.swing.Timer attachTimer;
    private final ComponentAdapter resizeListener=new ComponentAdapter(){@Override public void componentResized(ComponentEvent e){place(false);}};
    FishOverlay(Project project,ReviewService service){
        this.project=project;this.service=service;
        attachTimer=new javax.swing.Timer(500,e->refresh());
        service.listen(view->{component.view=view;component.setToolTipText(view.message());component.repaint();},service);
    }
    void refresh(){
        if(disposed||project.isDisposed())return;
        if(parent==null){
            JFrame frame=WindowManager.getInstance().getFrame(project);
            if(frame==null){if(++attempts<30)attachTimer.start();return;}
            attachTimer.stop();parent=frame.getLayeredPane();parent.add(component,JLayeredPane.POPUP_LAYER);parent.addComponentListener(resizeListener);place(true);
        }
        component.setVisible(ReviewSettings.get(project).data().fishVisible);component.updateTimer();parent.repaint();
    }
    private void place(boolean initial){
        if(parent==null)return;ReviewSettings.Data data=ReviewSettings.get(project).data();
        int width=parent.getWidth(),height=parent.getHeight();
        int x=initial?data.fishX:component.getX(),y=initial?data.fishY:component.getY();
        // Only the initial saved coordinates use -1 as "place at the bottom/right".
        if(initial){if(x<0)x=Math.max(0,width-144);if(y<0)y=Math.max(0,height-210);}
        else{
            x=OverlayPlacement.preserveFarEdge(x,previousParentWidth,width,144);
            y=OverlayPlacement.preserveFarEdge(y,previousParentHeight,height,210);
        }
        var position=OverlayPlacement.fit(x,y,width,height,144,210,16);
        component.setBounds(position.x(),position.y(),144,210);
        previousParentWidth=width;previousParentHeight=height;
    }
    @Override public void dispose(){
        disposed=true;attachTimer.stop();component.pauseAnimation();
        SwingUtilities.invokeLater(()->{if(parent!=null){parent.removeComponentListener(resizeListener);parent.remove(component);parent.repaint();}});
    }
    private final class Fish extends JComponent {
        private final Map<String,BufferedImage> sheets=new HashMap<>();
        private ReviewService.View view;
        private final SpriteAnimationTimeline timeline=new SpriteAnimationTimeline();
        private Point press,origin;
        private boolean dragged;
        private final javax.swing.Timer timer=new javax.swing.Timer(30,e->repaint());
        Fish(){
            setOpaque(false);
            for(String state:java.util.List.of("idle","wave","happy","sleep","blink")){
                try(var in=FishOverlay.class.getResourceAsStream("/pet/whale_"+state+".png")){if(in!=null)sheets.put(state,ImageIO.read(in));}
                catch(Exception ignored){}
            }
            MouseAdapter mouse=new MouseAdapter(){
                @Override public void mousePressed(MouseEvent e){
                    if(e.isPopupTrigger()||SwingUtilities.isRightMouseButton(e)){menu(e);return;}
                    press=e.getLocationOnScreen();origin=getLocation();dragged=false;
                }
                @Override public void mouseDragged(MouseEvent e){
                    if(press==null||parent==null)return;Point point=e.getLocationOnScreen();int dx=point.x-press.x,dy=point.y-press.y;
                    if(Math.abs(dx)+Math.abs(dy)>5)dragged=true;
                    if(dragged){setLocation(origin.x+dx,origin.y+dy);place(false);}
                }
                @Override public void mouseReleased(MouseEvent e){
                    if(e.isPopupTrigger()){menu(e);return;}
                    if(press==null)return;
                    if(dragged){ReviewSettings.Data d=ReviewSettings.get(project).data();d.fishX=getX();d.fishY=getY();}
                    else service.toggleReports();press=null;
                }
            };
            addMouseListener(mouse);addMouseMotionListener(mouse);
            addHierarchyListener(e->{if((e.getChangeFlags()&HierarchyEvent.SHOWING_CHANGED)!=0)updateTimer();});
        }
        void updateTimer(){
            if(!disposed&&isShowing()){
                timeline.resume();
                if(!timer.isRunning())timer.start();
            }else pauseAnimation();
        }
        private void pauseAnimation(){timer.stop();timeline.pause();}
        private void menu(MouseEvent e){
            JPopupMenu popup=new JPopupMenu();
            item(popup,"查看报告",service::showReports);item(popup,"检查当前提交",()->service.manual(null));item(popup,"取消检查",service::cancel);
            boolean enabled=ReviewSettings.get(project).data().automatic;
            item(popup,enabled?"关闭自动审查":"开启自动审查",()->{ReviewSettings.get(project).data().automatic=!enabled;service.settingsChanged();});
            item(popup,"设置",()->ShowSettingsUtil.getInstance().showSettingsDialog(project,ReviewConfigurable.class));
            popup.show(this,e.getX(),e.getY());
        }
        private void item(JPopupMenu menu,String title,Runnable run){JMenuItem item=new JMenuItem(title);item.addActionListener(e->run.run());menu.add(item);}
        @Override protected void paintComponent(Graphics graphics){
            super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();
            try{
                String animation="idle",label="陪你写代码";
                if(view!=null){
                    label=Reports.label(view.status());
                    animation=switch(view.status()){
                        case RUNNING,PREPARING,QUEUED->"wave";
                        case COMPLETED->service.latestBatchFindingCount()==0?"happy":"blink";
                        case FAILED,CANCELED->"sleep";case PARTIAL->"blink";default->"idle";
                    };
                }
                if(!ReviewSettings.get(project).data().automatic&&(view==null||view.status()==Review.Status.NOT_APPLICABLE))label="自动审查未开启";
                g.setColor(new Color(30,45,65,215));g.fillRoundRect(3,2,138,25,10,10);
                g.setColor(Color.WHITE);g.setFont(getFont()==null?new Font(Font.DIALOG,Font.PLAIN,11):getFont().deriveFont(11f));
                FontMetrics metrics=g.getFontMetrics();g.drawString(label,Math.max(6,(144-metrics.stringWidth(label))/2),19);
                BufferedImage sheet=sheets.getOrDefault(animation,sheets.get("idle"));
                if(sheet!=null){
                    int count=Math.max(1,sheet.getWidth()/144),frame=timeline.frameIndex()%count;
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g.drawImage(sheet,0,30,144,206,frame*144,0,(frame+1)*144,176,null);
                }else{g.setColor(new Color(67,139,220));g.fillOval(15,50,114,100);g.setColor(Color.WHITE);g.drawString("Silent Review",30,100);}
            }finally{g.dispose();}
        }
    }
}
