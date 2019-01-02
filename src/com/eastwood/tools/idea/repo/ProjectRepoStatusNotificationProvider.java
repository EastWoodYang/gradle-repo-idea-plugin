package com.eastwood.tools.idea.repo;

import com.android.tools.idea.gradle.project.sync.GradleSyncInvoker;
import com.android.tools.idea.gradle.project.sync.GradleSyncListener;
import com.android.tools.idea.gradle.project.sync.GradleSyncState;
import com.google.wireless.android.sdk.stats.GradleSyncStats;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ProjectRepoStatusNotificationProvider extends EditorNotifications.Provider<EditorNotificationPanel> {

    public static final Key<EditorNotificationPanel> KEY = Key.create("repo.status");

    private boolean ignore;

    @NotNull
    private final Project myProject;
    private RepoFileState repoFileState;

    public ProjectRepoStatusNotificationProvider(@NotNull Project project) {
        myProject = project;
        repoFileState = RepoFileState.getInstance(myProject);
        GradleSyncState.subscribe(this.myProject, new GradleSyncListener() {

            @Override
            public void syncSucceeded(@NotNull Project project) {
                ignore = false;
            }

        });
    }

    @NotNull
    @Override
    public Key<EditorNotificationPanel> getKey() {
        return KEY;
    }

    @Nullable
    @Override
    public EditorNotificationPanel createNotificationPanel(@NotNull VirtualFile virtualFile, @NotNull FileEditor fileEditor) {
        if (repoFileState.isSync()) {
            return null;
        }
        EditorNotificationPanel oldPanel = fileEditor.getUserData(this.getKey());
        if (oldPanel != null) {
            if (repoFileState.areRepoFilesModified()) {
                return oldPanel;
            }
        }
        if (repoFileState.areRepoFilesModified()) {
            return new StaleRepoSyncNotificationPanel();
        }

        if (ignore) {
            return null;
        }

        return null;
    }

    public class StaleRepoSyncNotificationPanel extends EditorNotificationPanel {

        StaleRepoSyncNotificationPanel() {
            setText("Repo files have changed since last project sync. A project sync may be necessary for the IDE to work properly.");

            createActionLabel("Sync Now", new Runnable() {
                @Override
                public void run() {
                    GradleSyncInvoker.getInstance().requestProjectSyncAndSourceGeneration(myProject, GradleSyncStats.Trigger.TRIGGER_USER_REQUEST);
                }
            });
        }
    }

}
