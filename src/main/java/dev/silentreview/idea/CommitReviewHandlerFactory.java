package dev.silentreview.idea;

import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.checkin.*;
import dev.silentreview.core.WorkspaceScope;
import git4idea.repo.GitRepositoryManager;
import java.nio.file.Path;
import java.util.*;
import org.jetbrains.annotations.NotNull;

public final class CommitReviewHandlerFactory extends CheckinHandlerFactory {
    @Override public @NotNull CheckinHandler createHandler(@NotNull CheckinProjectPanel panel,@NotNull CommitContext context){
        return new CheckinHandler(){
            private List<WorkspaceScope.CommitSnapshot> captured=List.of();
            @Override public ReturnResult beforeCheckin(){
                var repos=GitRepositoryManager.getInstance(panel.getProject()).getRepositories();
                List<Path> paths=new ArrayList<>();
                panel.getFiles().forEach(file->paths.add(file.toPath()));
                // Both paths are needed for deletions and renames across directories.
                panel.getSelectedChanges().forEach(change->{
                    if(change.getBeforeRevision()!=null)paths.add(Path.of(change.getBeforeRevision().getFile().getPath()));
                    if(change.getAfterRevision()!=null)paths.add(Path.of(change.getAfterRevision().getFile().getPath()));
                });
                List<Path> participating=WorkspaceScope.participatingRoots(repos.stream().map(r->Path.of(r.getRoot().getPath())).toList(),paths);
                captured=repos.stream().filter(repo->participating.contains(Path.of(repo.getRoot().getPath()).toAbsolutePath().normalize()))
                    .map(repo->new WorkspaceScope.CommitSnapshot(Path.of(repo.getRoot().getPath()),repo.getCurrentRevision(),
                        repo.getCurrentBranchName()==null?"HEAD":repo.getCurrentBranchName())).toList();
                return ReturnResult.COMMIT;
            }
            @Override public void checkinSuccessful(){
                if(!captured.isEmpty())ReviewService.get(panel.getProject()).committed(captured);
            }
        };
    }
}
