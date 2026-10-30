package dev.silentreview.core;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Bounded, rule-declared or text-linked context read only from the frozen target commit. */
public final class RuleContext {
    private RuleContext() {}
    public static final int DEFAULT_BUDGET=40_000,MAX_FILES_PER_PATTERN=16,MAX_FILE_CHARACTERS=100_000;
    private static final Set<String> EXTENSIONS=Set.of("json","yaml","yml","xml","txt","properties","conf","config","ini","md","sql","pck","prc","fnc","java","kt","kts","js","ts","tsx","jsx","vue","css","scss","html","ftl","jsp","toml","gradle","groovy","sh","bat","cmd","ps1","csv");
    private static final Pattern FILE_TOKEN=Pattern.compile("(?<![A-Za-z0-9_.])([A-Za-z0-9_{}*./$@+\\-]+\\.[A-Za-z0-9]{1,12})(?![A-Za-z0-9_]|\\.[A-Za-z0-9_])");
    public record Entry(String ruleId,String filePath,String requestedPath,String path,String status,String content,String reason,List<String> candidates) {
        public Entry { candidates=List.copyOf(candidates); }
    }
    public record Snapshot(List<Entry> entries,List<String> limitations,Set<Review.Check> incompleteChecks,int characters) {
        public Snapshot { entries=List.copyOf(entries);limitations=List.copyOf(limitations);incompleteChecks=Set.copyOf(incompleteChecks); }
        public static Snapshot empty(){return new Snapshot(List.of(),List.of(),Set.of(),0);}
    }
    private record Link(String path,boolean explicit) {}
    private record TreeFile(String path,String mode,String type) {}
    private record Resolved(List<TreeFile> files,String missingPath,String status,String reason,List<String> candidates) {}

    public static Snapshot capture(Rules.Snapshot rules,GitRepository.ChangeSet changes,BooleanSupplier canceled){return capture(rules,changes,canceled,DEFAULT_BUDGET);}
    public static Snapshot capture(Rules.Snapshot rules,GitRepository.ChangeSet changes,BooleanSupplier canceled,int budget){
        List<Entry> entries=new ArrayList<>();List<String> limitations=new ArrayList<>();Set<Review.Check> incomplete=new LinkedHashSet<>();
        Map<String,String> contentCache=new HashMap<>(),contentErrors=new HashMap<>();Set<String> emitted=new HashSet<>();int characters=0;
        FrozenTree tree=new FrozenTree(changes,canceled);
        for(var file:changes.files())for(var rule:rules.rules()){
            checkCanceled(canceled);Optional<Map<String,String>> binding=rule.bindings(file.path());if(binding.isEmpty())continue;
            for(Link link:links(rule,file.path())){
                checkCanceled(canceled);String requested=link.path();var substituted=Rules.substitute(requested,binding.get());
                if(substituted.isEmpty()){
                    add(entries,limitations,incomplete,new Entry(rule.id(),file.path(),requested,"","ambiguous","","关联路径存在未绑定占位符，请在 appliesTo.include 中声明同名占位符",List.of()));continue;
                }
                String expected=substituted.get();
                try{
                    Rules.glob(expected);
                    Resolved resolved=resolve(tree,rule,file.path(),binding.get(),expected,link.explicit());
                    if(!resolved.status().equals("present")){
                        add(entries,limitations,incomplete,new Entry(rule.id(),file.path(),requested,resolved.missingPath(),resolved.status(),"",resolved.reason(),resolved.candidates()));continue;
                    }
                    for(TreeFile candidate:resolved.files()){
                        String path=candidate.path();
                        if(!candidate.type().equals("blob")||!candidate.mode().startsWith("100")){
                            add(entries,limitations,incomplete,new Entry(rule.id(),file.path(),requested,path,"unavailable","","关联对象不是普通 Git 文件（不跟随符号链接或子模块）",List.of(path)));continue;
                        }
                        if(!contentCache.containsKey(path)&&!contentErrors.containsKey(path))try{
                            String content=tree.read(path);
                            if(content.indexOf(0)>=0||content.length()>MAX_FILE_CHARACTERS)throw new IOException("关联文件是二进制内容或超过 "+MAX_FILE_CHARACTERS+" 字符限制");
                            contentCache.put(path,Review.numbered(content));
                        }catch(CancellationException e){throw e;
                        }catch(Exception e){contentErrors.put(path,message(e));}
                        if(contentErrors.containsKey(path)){
                            add(entries,limitations,incomplete,new Entry(rule.id(),file.path(),requested,path,"unavailable","",contentErrors.get(path),List.of(path)));continue;
                        }
                        String content=contentCache.get(path);boolean shared=emitted.contains(path);
                        if(!shared&&characters+content.length()>Math.max(0,budget)){
                            add(entries,limitations,incomplete,new Entry(rule.id(),file.path(),requested,path,"unavailable","","关联上下文预算不足；没有截断后冒充完整内容",List.of(path)));continue;
                        }
                        if(!shared){characters+=content.length();emitted.add(path);}
                        add(entries,limitations,incomplete,new Entry(rule.id(),file.path(),requested,path,"present",shared?"":content,
                            shared?"共享冻结上下文；编号内容见相同 path 的首条 present 记录":"读取自冻结目标提交 "+changes.target(),List.of(path)));
                    }
                }catch(CancellationException e){throw e;
                }catch(Exception e){add(entries,limitations,incomplete,new Entry(rule.id(),file.path(),requested,expected,"unavailable","",message(e),List.of()));}
            }
        }
        return new Snapshot(entries,limitations,incomplete,characters);
    }
    private static void add(List<Entry> entries,List<String> limitations,Set<Review.Check> incomplete,Entry entry){
        entries.add(entry);
        if(entry.status().equals("unavailable")||entry.status().equals("ambiguous")){
            incomplete.add(new Review.Check(entry.filePath(),entry.ruleId()));limitations.add(entry.filePath()+" / "+entry.ruleId()+" / "+entry.requestedPath()+"："+entry.reason());
        }
    }
    private static List<Link> links(Rules.Rule rule,String changedPath){
        if(!rule.contextFiles().isEmpty())return rule.contextFiles().stream().distinct().map(path->new Link(path,true)).toList();
        LinkedHashSet<String> paths=new LinkedHashSet<>();extract(rule.requirement(),paths);
        if(rule.definition().has("source")&&rule.definition().get("source").isJsonObject())for(var value:rule.definition().getAsJsonObject("source").entrySet()){
            JsonElement element=value.getValue();if(element.isJsonPrimitive()&&element.getAsJsonPrimitive().isString())extract(element.getAsString(),paths);
        }
        String basename=changedPath.substring(changedPath.lastIndexOf('/')+1);
        paths.removeIf(path->path.equals(changedPath)||path.equals(basename));
        // An explicit full link is more precise than the same filename mentioned again in an excerpt.
        Set<String> fullNames=new HashSet<>();paths.stream().filter(path->path.contains("/")).forEach(path->fullNames.add(path.substring(path.lastIndexOf('/')+1)));
        paths.removeIf(path->!path.contains("/")&&fullNames.contains(path));
        return paths.stream().map(path->new Link(path,false)).toList();
    }
    private static void extract(String text,Set<String> paths){
        var matcher=FILE_TOKEN.matcher(text);
        while(matcher.find()){
            String path=matcher.group(1);String extension=path.substring(path.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
            if(!EXTENSIONS.contains(extension))continue;
            // URI schemes and absolute paths are not repository context declarations.
            if(path.startsWith("/")||(matcher.start()>0&&text.charAt(matcher.start()-1)==':'))continue;
            while(path.startsWith("./"))path=path.substring(2);paths.add(path);
        }
    }
    private static Resolved resolve(FrozenTree tree,Rules.Rule rule,String changedPath,Map<String,String> bindings,String path,boolean explicit)throws IOException{
        if(path.contains("*")){
            String prefix=literalPrefix(path);
            if(prefix.isEmpty())return unresolved("unavailable",path,"关联 glob 必须限定目录前缀；不对整个仓库执行递归上下文扫描",List.of());
            List<TreeFile> matches=tree.list(prefix).stream().filter(file->Rules.glob(path).matcher(file.path()).matches()).toList();
            return bounded(matches,path,"声明的关联 glob 在冻结提交中没有匹配文件");
        }
        if(explicit)return exact(tree,path);
        String scope=scopePrefix(rule,changedPath,bindings);
        LinkedHashMap<String,TreeFile> matches=new LinkedHashMap<>();
        if(path.contains("/")){
            // Text may use a repository-relative full path or a same-module relative fragment.
            addMatches(matches,tree.exact(path));
            if(!scope.isBlank()&&!path.startsWith(scope))addMatches(matches,tree.exact(scope+path));
        }else{
            String directory=parent(changedPath);
            while(true){
                addMatches(matches,tree.exact(directory+path));
                if(directory.isEmpty()||(!bindings.isEmpty()&&directory.equals(scope)))break;
                String next=parent(stripTrailing(directory));
                if(!bindings.isEmpty()&&!next.startsWith(scope))break;
                directory=next;
            }
            if(matches.isEmpty()&&!scope.isEmpty()){
                for(TreeFile candidate:tree.list(scope))if(candidate.path().substring(candidate.path().lastIndexOf('/')+1).equals(path))matches.put(candidate.path(),candidate);
            }
        }
        if(matches.size()>1)return unresolved("ambiguous",path,"同一关联文件名或相对路径存在多个候选，需在 contextFiles 中声明精确路径",List.copyOf(matches.keySet()));
        return bounded(List.copyOf(matches.values()),path,"冻结提交的同目录/同模块范围内没有找到明确关联文件");
    }
    private static Resolved exact(FrozenTree tree,String path)throws IOException{return bounded(tree.exact(path),path,"冻结目标提交中不存在该关联路径（不读取本地未提交文件）");}
    private static void addMatches(Map<String,TreeFile> output,List<TreeFile> files){files.forEach(file->output.put(file.path(),file));}
    private static Resolved bounded(List<TreeFile> files,String path,String missing){
        if(files.isEmpty())return unresolved("missing",path,missing,List.of());
        if(files.size()>MAX_FILES_PER_PATTERN)return unresolved("ambiguous",path,"关联候选超过 "+MAX_FILES_PER_PATTERN+" 个，请收窄 contextFiles 路径",files.stream().map(TreeFile::path).limit(MAX_FILES_PER_PATTERN+1).toList());
        return new Resolved(files,"","present","",files.stream().map(TreeFile::path).toList());
    }
    private static Resolved unresolved(String status,String path,String reason,List<String> candidates){return new Resolved(List.of(),path,status,reason,candidates);}
    private static String literalPrefix(String pattern){int star=pattern.indexOf('*');return star<0?pattern:parent(pattern.substring(0,star));}
    private static String scopePrefix(Rules.Rule rule,String path,Map<String,String> bindings){
        int bindingEnd=-1;
        for(String value:bindings.values()){
            String token="/"+value+"/";int at=("/"+path).lastIndexOf(token);if(at>=0)bindingEnd=Math.max(bindingEnd,at+token.length()-1);
        }
        if(bindingEnd>=0)return path.substring(0,Math.min(bindingEnd,path.length()));
        for(String include:rule.includes())if(Rules.bind(include,path).isPresent()){
            String prefix=literalPrefix(Rules.substitute(include,bindings).orElse(include));String parent=parent(stripTrailing(prefix));
            if(!parent.isEmpty())return parent;
        }
        int slash=path.indexOf('/');return slash<0?"":path.substring(0,slash+1);
    }
    private static String parent(String path){int at=path.lastIndexOf('/');return at<0?"":path.substring(0,at+1);}
    private static String stripTrailing(String path){return path.endsWith("/")?path.substring(0,path.length()-1):path;}
    private static final class FrozenTree {
        private final GitRepository.ChangeSet changes;private final BooleanSupplier canceled;
        private final Map<String,List<TreeFile>> listings=new HashMap<>();private GitRepository git;
        FrozenTree(GitRepository.ChangeSet changes,BooleanSupplier canceled){this.changes=changes;this.canceled=canceled;}
        private GitRepository git()throws IOException{
            checkCanceled(canceled);
            if(git==null){
                GitRepository candidate=new GitRepository(Path.of(changes.root()),canceled);
                if(!candidate.resolve(changes.target()).equals(changes.target()))throw new IOException("冻结目标提交无法校验");
                git=candidate;
            }
            return git;
        }
        List<TreeFile> exact(String path)throws IOException{return list(path).stream().filter(file->file.path().equals(path)).toList();}
        List<TreeFile> list(String prefix)throws IOException{
            if(listings.containsKey(prefix))return listings.get(prefix);
            if(prefix.isEmpty())throw new IOException("未限定关联上下文目录");
            String raw=git().run("ls-tree","-r","-z","--full-tree",changes.target(),"--",prefix);List<TreeFile> result=new ArrayList<>();
            for(String entry:raw.split("\\x00")){
                if(entry.isEmpty())continue;String[] parts=entry.split("\\t",2),metadata=parts[0].split(" ");
                if(parts.length!=2||metadata.length<3)throw new IOException("无法解析冻结提交的上下文目录");
                result.add(new TreeFile(parts[1],metadata[0],metadata[1]));
                if(result.size()>5000)throw new IOException("关联目录超过 5000 个文件，请收窄上下文路径");
            }
            List<TreeFile> frozen=List.copyOf(result);listings.put(prefix,frozen);return frozen;
        }
        String read(String path)throws IOException{return git().run("show",changes.target()+":"+path);}
    }
    private static void checkCanceled(BooleanSupplier canceled){if(canceled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("上下文收集已取消");}
    private static String message(Exception error){return error.getMessage()==null?"无法读取冻结关联上下文":error.getMessage();}
}
