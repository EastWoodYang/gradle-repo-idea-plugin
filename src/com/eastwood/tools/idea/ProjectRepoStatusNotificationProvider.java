package com.eastwood.tools.idea;

import com.android.tools.idea.gradle.project.sync.GradleSyncInvoker;
import com.android.tools.idea.gradle.project.sync.GradleSyncListener;
import com.android.tools.idea.gradle.project.sync.GradleSyncState;
import com.google.wireless.android.sdk.stats.GradleSyncStats;
import com.intellij.openapi.externalSystem.task.TaskCallback;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ProjectRepoStatusNotificationProvider extends EditorNotifications.Provider<EditorNotificationPanel> {


    public static final Key<EditorNotificationPanel> KEY = Key.create("repo.status");

    private boolean disable;
    private boolean ignore;
    private boolean running;

    @NotNull
    private final Project myProject;
    private RepoFileState repoFileState;

    public ProjectRepoStatusNotificationProvider(@NotNull Project project) {
        myProject = project;
        repoFileState = RepoFileState.getInstance(myProject);
        GradleSyncState.subscribe(this.myProject, new GradleSyncListener.Adapter() {

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
        if (repoFileState.isSync() || running) {
            return null;
        }
        EditorNotificationPanel oldPanel = fileEditor.getUserData(this.getKey());
        if (oldPanel != null) {
            if (repoFileState.areRepoFilesModified()) {
                if (oldPanel instanceof StaleRepoBindNotificationPanel) {
                    return new StaleRepoSyncNotificationPanel();
                } else {
                    return oldPanel;
                }
            }
        }
        if (repoFileState.areRepoFilesModified()) {
            return new StaleRepoSyncNotificationPanel();
        }

        if (disable || ignore) {
            return null;
        }

        if (repoFileState.hasUnBoundModules()) {
            if (oldPanel instanceof StaleRepoBindNotificationPanel) {
                return oldPanel;
            } else {
                return new StaleRepoBindNotificationPanel(repoFileState.getUnBoundModules());
            }
        }
        return null;
    }

    private class StaleRepoBindNotificationPanel extends EditorNotificationPanel {

        Map<Module, List<RepoModule>> unBoundModules;

        StaleRepoBindNotificationPanel(Map<Module, List<RepoModule>> unBoundModules) {
            this.unBoundModules = unBoundModules;

            setText("Some module is unbound remote origin repository, need to bind remote before push.");

            createActionLabel("Bind Now", new Runnable() {
                @Override
                public void run() {
                    running = true;
                    repoFileState.notifyUser();
                    String defaultTaskName = "bindRemoteRepository";
                    List<String> tasks = new ArrayList<>();
                    Set<Module> keySet = unBoundModules.keySet();
                    for (Module module : keySet) {
                        List<RepoModule> repoModules = unBoundModules.get(module);
                        for (RepoModule repoModule : repoModules) {
                            if (repoModule.isModule) {
                                if (!tasks.contains(defaultTaskName)) {
                                    tasks.add(defaultTaskName);
                                }
                            } else {
                                String taskName = "bind" + firstUpperCase(repoModule.name) + "RemoteRepository";
                                if (!tasks.contains(taskName)) {
                                    tasks.add(taskName);
                                }
                            }
                        }
                    }
                    if (tasks.size() == 0) {
                        running = false;
                        return;
                    }
                    GradleTaskExecutor.executeTask(myProject, String.join(" ", tasks), new TaskCallback() {
                        @Override
                        public void onSuccess() {
                            repoFileState.updateBindStateOfModule();
                            running = false;
                        }

                        @Override
                        public void onFailure() {
                            repoFileState.updateBindStateOfModule();
                            running = false;
                        }
                    });
                }
            });

            createActionLabel("Not Now", new Runnable() {
                @Override
                public void run() {
                    ignore = true;
                    repoFileState.notifyUser();
                }
            });

            createActionLabel("Disable", new Runnable() {
                @Override
                public void run() {
                    disable = true;
                    repoFileState.notifyUser();
                }
            });
        }
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


    private String firstUpperCase(String value) {
        char[] cs = value.toCharArray();
        cs[0] -= (cs[0] > 96 && cs[0] < 123) ? 32 : 0;
        return String.valueOf(cs);
    }

}
