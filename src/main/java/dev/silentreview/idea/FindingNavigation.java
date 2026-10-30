package dev.silentreview.idea;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Navigation policy without IDE dependencies; report paths always belong to their recorded root. */
public final class FindingNavigation {
    private FindingNavigation() {}
    public enum Match { ORIGINAL, MOVED, APPROXIMATE }
    public record Target(Path path,boolean renamedFallback) {}
    public record Line(int zeroBasedLine,Match match,String message) {}
    /** End offsets exclude the last line separator; line numbers are zero based and inclusive. */
    public record Selection(int zeroBasedStartLine,int zeroBasedEndLine,int startOffset,int endOffset) {}

    /** Also accepts unregistered Git roots discovered inside a project content directory after restart. */
    public static boolean inProject(Path reportRoot,Collection<Path> registeredRoots,Collection<Path> projectDirectories)throws IOException{
        Path root=reportRoot.toRealPath();
        for(Path registered:registeredRoots)try{if(registered.toRealPath().equals(root))return true;}catch(IOException ignored){}
        if(!gitMarker(root))return false;
        for(Path directory:projectDirectories)try{
            Path hint=directory.toRealPath();
            if(root.startsWith(hint))return true;
            // A project opened at a repository subdirectory belongs only to its nearest Git ancestor.
            // Inspect ancestor markers without scanning any sibling trees.
            for(Path ancestor=hint;ancestor!=null;ancestor=ancestor.getParent())if(gitMarker(ancestor)){
                if(root.equals(ancestor))return true;
                break;
            }
        }catch(IOException ignored){}
        return false;
    }
    private static boolean gitMarker(Path root){
        Path marker=root.resolve(".git");
        return !Files.isSymbolicLink(marker)&&(Files.isDirectory(marker,LinkOption.NOFOLLOW_LINKS)||Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS));
    }

    public static Target currentFile(Path reportRoot,String filePath,Map<String,String> previousPaths) throws IOException {
        Path root=reportRoot.toAbsolutePath().normalize();
        if(!Files.isDirectory(root))throw new FileNotFoundException("报告仓库目录不存在："+root+"。可选择查看提交快照。");
        Path realRoot=root.toRealPath();
        Path direct=inside(root,filePath);
        if(regularInside(realRoot,direct))return new Target(direct,false);
        LinkedHashSet<String> alternatives=new LinkedHashSet<>();
        if(previousPaths!=null){
            String old=previousPaths.get(filePath);if(old!=null&&!old.equals(filePath))alternatives.add(old);
            // Old-side findings can also carry the old path; the rename map is new path -> old path.
            previousPaths.forEach((current,previous)->{if(filePath.equals(previous)&&!filePath.equals(current))alternatives.add(current);});
        }
        for(String alternative:alternatives){Path candidate=inside(root,alternative);if(regularInside(realRoot,candidate))return new Target(candidate,true);}
        throw new FileNotFoundException("当前仓库中找不到源码文件（可能已删除或再次重命名）："+direct+"。未搜索其他仓库，可选择查看提交快照。");
    }

    private static Path inside(Path root,String relative){
        if(relative==null||relative.isBlank())throw new IllegalArgumentException("报告文件路径为空");
        String path=relative.replace('\\','/');
        if(path.startsWith("/")||path.indexOf(':')>=0)throw new IllegalArgumentException("报告路径必须位于记录的仓库内："+relative);
        Path result=root.resolve(path).normalize();
        if(!result.startsWith(root)||result.equals(root))throw new IllegalArgumentException("报告路径超出记录的仓库："+relative);
        return result;
    }
    private static boolean regularInside(Path realRoot,Path file)throws IOException{
        if(!Files.isRegularFile(file))return false;
        if(!file.toRealPath().startsWith(realRoot))throw new IllegalArgumentException("源码路径通过链接指向仓库外，已停止定位："+file);
        return true;
    }

    /** Receives the IDE's current Document text, including unsaved edits and its decoded charset. */
    public static Line currentLine(String document,String evidence,int reportStartLine){
        List<String> current=lines(document==null?"":document);
        int original=(int)Math.max(0,Math.min((long)reportStartLine-1,current.size()-1));
        List<String> expected=lines(evidence==null?"":evidence.strip());
        if(evidence==null||evidence.isBlank())return new Line(original,Match.APPROXIMATE,"报告没有可核对的代码证据，已打开原报告行号附近，仅作定位参考。");
        if(reportStartLine>=1&&matches(current,reportStartLine-1,expected))return new Line(reportStartLine-1,Match.ORIGINAL,"");
        int found=-1,count=0;
        for(int line=0;line+expected.size()<=current.size();line++)if(matches(current,line,expected)){found=line;count++;}
        if(count==1)return new Line(found,Match.MOVED,"代码行号已变化，已根据当前文档中唯一的报告证据定位到第 "+(found+1)+" 行。");
        return new Line(original,Match.APPROXIMATE,(count>1?"当前源码有多处相同证据，无法唯一定位。":"当前源码的报告证据已修改或删除。")+"已打开原报告行号附近第 "+(original+1)+" 行，仅作定位参考。");
    }
    /** Only a verified location can retain the report's multiline span; stale locations select one line. */
    public static Selection selection(String document,Line location,String evidence,int reportStartLine,int reportEndLine){
        String text=document==null?"":document;
        List<Integer> starts=new ArrayList<>(),ends=new ArrayList<>();starts.add(0);
        for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n'||text.charAt(i)=='\r'){
            ends.add(i);
            if(text.charAt(i)=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;
            starts.add(i+1);
        }
        ends.add(text.length());
        int start=Math.max(0,Math.min(location.zeroBasedLine(),starts.size()-1));
        long reportSpan=Math.max(1,(long)reportEndLine-reportStartLine+1);
        long span=location.match()==Match.APPROXIMATE?1:Math.min(reportSpan,lines(evidence==null?"":evidence.strip()).size());
        int end=(int)Math.min(starts.size()-1,(long)start+span-1);
        return new Selection(start,end,starts.get(start),ends.get(end));
    }
    private static boolean matches(List<String> current,int start,List<String> expected){
        if(start<0||start>current.size()-expected.size())return false;
        return String.join("\n",current.subList(start,start+expected.size())).strip().equals(String.join("\n",expected).strip());
    }
    private static List<String> lines(String value){return List.of(value.replace("\r\n","\n").replace('\r','\n').split("\n",-1));}
}
