package dev.silentreview.idea;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.*;
import com.intellij.openapi.progress.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.silentreview.core.AiClient;
import dev.silentreview.core.Rules;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Locale;

public final class ReviewConfigurable implements Configurable {
    private final Project project;
    private JPanel panel;
    private JTextField path,endpoint,model;
    private JPasswordField key;
    private JCheckBox automatic,consent,fish;
    private JLabel keyStatus,ruleStatus;
    private Timer statusTimer;
    private final ApiKeyStorage.Requests statusRequests=new ApiKeyStorage.Requests();
    public ReviewConfigurable(Project project) { this.project=project; }
    @Override public String getDisplayName() { return "静默代码检测"; }
    @Override public JComponent createComponent() {
        panel=new JPanel(new GridBagLayout()); GridBagConstraints c=new GridBagConstraints();
        c.insets=new Insets(7,8,7,8); c.fill=GridBagConstraints.HORIZONTAL; c.anchor=GridBagConstraints.NORTHWEST;
        path=new JTextField(40); endpoint=new JTextField(40); model=new JTextField(40); key=new JPasswordField(40);
        JPanel pathRow=new JPanel(new BorderLayout(6,0));pathRow.add(path,BorderLayout.CENTER);
        JButton browse=new JButton("浏览…");browse.addActionListener(e->chooseRulesFile());pathRow.add(browse,BorderLayout.EAST);
        add(c,0,"规则集路径",pathRow);
        JPanel ruleButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));
        JButton create=new JButton("创建示例规则"),validate=new JButton("检查规则文件"),open=new JButton("打开所在文件夹");
        ruleButtons.add(create);ruleButtons.add(Box.createHorizontalStrut(8));ruleButtons.add(validate);
        ruleButtons.add(Box.createHorizontalStrut(8));ruleButtons.add(open);span(c,1,ruleButtons);
        ruleStatus=note("相对路径优先从各仓库根目录解析，找不到时回退到项目根目录；已有文件不会被覆盖。");span(c,2,ruleStatus);
        add(c,3,"AI 接口地址",endpoint);add(c,4,"模型",model);add(c,5,"API Key",key);
        keyStatus=note("正在查询当前接口的凭据状态…");span(c,6,keyStatus);
        span(c,7,note("Key 留空保留原凭据。保存成功后输入框会清空，状态确认已保存；不会显示明文。"));
        span(c,8,note("凭据按项目和接口地址分别保存；更改地址（包括尾部 /）后需重新填写。"));
        automatic=new JCheckBox("本地提交成功后自动审查");
        consent=new JCheckBox("<html>允许将相关已提交代码和规则<br>发送到上方 AI 接口</html>");fish=new JCheckBox("显示像素大肥鱼");
        span(c,9,automatic);span(c,10,consent);span(c,11,fish);
        span(c,12,note("首次使用：① 创建或选择规则并检查；② 填写接口、模型和 Key 并应用；③ 在 Silent Review 工具窗检查提交。"));
        span(c,13,note("示例规则可直接编辑；插件只审查已提交代码，不修改代码、不拦截推送。"));
        c.gridy=14;c.weighty=1;panel.add(new JPanel(),c);
        create.addActionListener(e->rulesAction(create,true,false));
        validate.addActionListener(e->rulesAction(validate,false,false));open.addActionListener(e->rulesAction(open,false,true));
        statusTimer=new Timer(250,e->queryCredentialStatus());statusTimer.setRepeats(false);
        endpoint.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){scheduleCredentialStatus();}
            public void removeUpdate(DocumentEvent e){scheduleCredentialStatus();}
            public void changedUpdate(DocumentEvent e){scheduleCredentialStatus();}
        });
        key.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){scheduleCredentialStatus();}
            public void removeUpdate(DocumentEvent e){scheduleCredentialStatus();}
            public void changedUpdate(DocumentEvent e){scheduleCredentialStatus();}
        });
        JScrollPane scroll=new JScrollPane(panel);
        scroll.setBorder(BorderFactory.createEmptyBorder());scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new Dimension(660,480));scroll.setMinimumSize(new Dimension(320,200));
        reset();return scroll;
    }
    private static JLabel note(String text){JLabel label=new JLabel();setNote(label,text);return label;}
    private static void setNote(JLabel label,String text){
        String escaped=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        label.setText("<html><body style='width: 430px'>"+escaped+"</body></html>");
    }
    private void span(GridBagConstraints c,int row,JComponent component){
        c.gridy=row;c.gridx=0;c.gridwidth=2;c.weightx=1;panel.add(component,c);
    }
    private void add(GridBagConstraints c,int row,String label,JComponent field) {
        c.gridy=row;c.gridx=0;c.gridwidth=1;c.weightx=0;panel.add(new JLabel(label),c);
        c.gridx=1;c.weightx=1;panel.add(field,c);
    }
    private void chooseRulesFile(){
        FileChooserDescriptor descriptor=FileChooserDescriptorFactory.createSingleFileDescriptor("json");
        descriptor.setTitle("选择规则集文件");
        descriptor.setDescription("选择包含审查规则定义的 JSON 文件");
        VirtualFile chosen=FileChooser.chooseFile(descriptor,project,null);
        if(chosen==null)return;
        Path selected=Path.of(chosen.getPath()).toAbsolutePath().normalize();
        path.setText(relativeToProject(selected));
        setNote(ruleStatus,"已选择规则集文件："+selected);
    }
    private String relativeToProject(Path selected){
        try{
            String base=project.getBasePath();
            if(base!=null&&!base.isBlank()){
                Path root=Path.of(base).toAbsolutePath().normalize();
                if(selected.startsWith(root))return root.relativize(selected).toString().replace('\\','/');
            }
        }catch(RuntimeException ignored){}
        return selected.toString();
    }
    private void rulesAction(JButton button,boolean create,boolean open){
        JPanel shown=panel;String configured=path.getText();
        ModalityState modality=ModalityState.current();
        button.setEnabled(false);setNote(ruleStatus,create?"正在创建示例规则…":"正在读取规则文件…");
        ApplicationManager.getApplication().executeOnPooledThread(()->{
            String message;
            try {
                Path resolved=RuleTemplate.resolve(ReadAction.compute(project::getBasePath),configured);
                if(open){
                    if(!Files.isRegularFile(resolved))throw new IllegalArgumentException("规则文件不存在："+resolved);
                    openInFileManager(resolved);
                    message="已在文件管理器中打开："+resolved.getParent();
                }else{
                    RuleTemplate.Created created=create?RuleTemplate.create(resolved):null;
                    Rules.Snapshot rules=Rules.load(resolved);
                    message=(created==null?"规则有效":created.created()?"已创建可编辑示例":"文件已存在，已保留原内容")+
                        " · "+rules.name()+" · "+rules.rules().size()+" 条 · "+resolved;
                }
            }catch(Exception failure){message=failure.getMessage()==null?"读取规则失败，请检查文件路径和 JSON 格式。":failure.getMessage();}
            String result=message;
            ApplicationManager.getApplication().invokeLater(()->{
                if(panel!=shown||project.isDisposed())return;
                setNote(ruleStatus,result);button.setEnabled(true);
            },modality);
        });
    }
    private static void openInFileManager(Path file) throws IOException {
        Path directory=file.toAbsolutePath().normalize().getParent();
        if(directory==null||!Files.isDirectory(directory))throw new IOException("找不到规则文件所在目录："+file);
        try{
            if(Desktop.isDesktopSupported()){
                Desktop desktop=Desktop.getDesktop();
                if(desktop.isSupported(Desktop.Action.OPEN)){desktop.open(directory.toFile());return;}
            }
        }catch(IOException|RuntimeException ignored){}
        String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
        ProcessBuilder builder;
        if(os.contains("win"))builder=new ProcessBuilder("explorer.exe",directory.toString());
        else if(os.contains("mac"))builder=new ProcessBuilder("open",directory.toString());
        else builder=new ProcessBuilder("xdg-open",directory.toString());
        builder.start();
    }
    private void scheduleCredentialStatus(){
        statusRequests.next();if(panel==null)return;
        statusTimer.stop();
        if(hasEnteredKey()){setNote(keyStatus,ApiKeyStorage.PENDING_LABEL);return;}
        setNote(keyStatus,"正在查询当前接口的凭据状态…");statusTimer.restart();
    }
    private void queryCredentialStatus(){
        if(panel==null)return;
        if(hasEnteredKey()){statusRequests.next();setNote(keyStatus,ApiKeyStorage.PENDING_LABEL);return;}
        JPanel shown=panel;String address=endpoint.getText().strip();long request=statusRequests.next();
        try {AiClient.endpoint(address);}catch(RuntimeException invalid){setNote(keyStatus,"请填写完整、有效的 AI 接口地址后查询凭据。");return;}
        ApplicationManager.getApplication().executeOnPooledThread(()->{
            String result;
            try {result=ReviewSettings.get(project).keyStatus(address).label();}
            catch(RuntimeException failure){result="无法读取凭据状态；请检查 IDEA 的密码存储设置。";}
            String text=result;
            SwingUtilities.invokeLater(()->{
                if(panel==shown&&!project.isDisposed()&&statusRequests.canDisplay(request,hasEnteredKey())&&endpoint.getText().strip().equals(address))setNote(keyStatus,text);
            });
        });
    }
    private boolean hasEnteredKey(){
        char[] chars=key.getPassword();try{return chars.length>0;}finally{Arrays.fill(chars,'\0');}
    }
    @Override public boolean isModified() {
        if(panel==null)return false;ReviewSettings.Data d=ReviewSettings.get(project).data();
        return !path.getText().equals(d.rulesPath)||!endpoint.getText().equals(d.endpoint)||!model.getText().equals(d.model)
            ||hasEnteredKey()||automatic.isSelected()!=d.automatic||consent.isSelected()!=d.allowExternal||fish.isSelected()!=d.fishVisible;
    }
    @Override public void apply() throws ConfigurationException {
        String address=endpoint.getText().strip(),rulesPath=path.getText().strip(),modelName=model.getText().strip();
        try {
            AiClient.endpoint(address);RuleTemplate.resolve(project.getBasePath(),rulesPath);
            if(modelName.isEmpty())throw new IllegalArgumentException("模型不能为空。");
        }catch(RuntimeException invalid){throw new ConfigurationException(invalid.getMessage());}
        ReviewSettings settings=ReviewSettings.get(project);char[] chars=key.getPassword();ApiKeyStorage.Status saved=null;
        try {ApiKeyStorage.validateReplacement(CharBuffer.wrap(chars));}
        catch(IllegalArgumentException invalid){Arrays.fill(chars,'\0');throw new ConfigurationException(invalid.getMessage());}
        try {
            if(chars.length>0){
                saved=ProgressManager.getInstance().run(new Task.WithResult<ApiKeyStorage.Status,RuntimeException>(project,"保存并验证 API Key",false){
                    @Override protected ApiKeyStorage.Status compute(@NotNull ProgressIndicator indicator){
                        return settings.saveKeyVerified(address,new String(chars));
                    }
                });
            }
        }catch(RuntimeException failure){
            setNote(keyStatus,"API Key 未确认保存成功，输入已保留。");
            throw new ConfigurationException("API Key 保存失败或无法回读确认。请检查 设置 → 外观与行为 → 系统设置 → 密码 后重试。输入和原配置均已保留。");
        }finally{Arrays.fill(chars,'\0');}
        // Publish a complete settings object only after credential storage succeeds.
        ReviewSettings.Data previous=settings.data(),updated=new ReviewSettings.Data();
        updated.rulesPath=rulesPath;updated.endpoint=address;updated.model=modelName;
        updated.automatic=automatic.isSelected();updated.allowExternal=consent.isSelected();updated.fishVisible=fish.isSelected();
        updated.fishX=previous.fishX;updated.fishY=previous.fishY;settings.loadState(updated);key.setText("");
        if(saved!=null){statusRequests.next();statusTimer.stop();setNote(keyStatus,saved.label());}else scheduleCredentialStatus();
        ReviewService.get(project).settingsChanged();
    }
    @Override public void reset() {
        if(panel==null)return;ReviewSettings.Data d=ReviewSettings.get(project).data();
        path.setText(d.rulesPath);endpoint.setText(d.endpoint);model.setText(d.model);key.setText("");
        automatic.setSelected(d.automatic);consent.setSelected(d.allowExternal);fish.setSelected(d.fishVisible);scheduleCredentialStatus();
    }
    @Override public void disposeUIResources(){statusRequests.next();if(statusTimer!=null)statusTimer.stop();panel=null;}
}
