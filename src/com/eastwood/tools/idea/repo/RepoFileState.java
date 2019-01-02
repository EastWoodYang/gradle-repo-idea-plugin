package com.eastwood.tools.idea.repo;

import com.android.tools.idea.gradle.project.sync.GradleSyncListener;
import com.android.tools.idea.gradle.project.sync.GradleSyncState;
import com.android.tools.idea.gradle.variant.view.BuildVariantView;
import com.intellij.concurrency.JobLauncher;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.ui.AppUIUtil;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

public class RepoFileState {

    @NotNull
    private final Project myProject;
    @NotNull
    private final Object myLock = new Object();
    @NotNull
    private final Set<VirtualFile> myChangedFiles = new HashSet();
    @NotNull
    private final Map<VirtualFile, Integer> myFileHashes = new HashMap();

    private boolean isSync;

    @NotNull
    private final SyncListener mySyncListener = new SyncListener();
    @NotNull
    private final FileEditorManagerListener myFileEditorListener;

    public static RepoFileState getInstance(@NotNull Project project) {
        return ServiceManager.getService(project, RepoFileState.class);
    }

    private RepoFileState(@NotNull Project project) {
        this.myProject = project;
        final RepoFileChangeListener fileChangeListener = new RepoFileChangeListener(this);
        PsiManager.getInstance(RepoFileState.this.myProject).addPsiTreeChangeListener(fileChangeListener);
        this.myFileEditorListener = new FileEditorManagerListener() {
            public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                if (event.getNewFile() != null) {
                    VirtualFile virtualFile = event.getNewFile();
                    if (!RepoFileState.this.isRepoFile(virtualFile)) {
                        PsiManager.getInstance(RepoFileState.this.myProject).removePsiTreeChangeListener(fileChangeListener);
                    } else {
                        PsiManager.getInstance(RepoFileState.this.myProject).addPsiTreeChangeListener(fileChangeListener);
                    }
                }
            }
        };
        this.myProject.getMessageBus().connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this.myFileEditorListener);
        GradleSyncState.subscribe(this.myProject, this.mySyncListener);

        if (this.myProject.isInitialized()) {
            this.updateFileHashes();
        } else if (!this.myProject.isDefault()) {
            StartupManager.getInstance(this.myProject).registerPostStartupActivity(new Runnable() {
                @Override
                public void run() {
                    RepoFileState.this.updateFileHashes();
                }
            });
        }

    }

    private void removeChangedFiles() {
        synchronized (this.myLock) {
            this.myChangedFiles.clear();
        }
    }

    private void addChangedFile(@NotNull VirtualFile file) {
        synchronized (this.myLock) {
            this.myChangedFiles.add(file);
        }
    }

    private void putHashForFile(@NotNull Map<VirtualFile, Integer> map, @NotNull VirtualFile repoFile) {
        Integer hash = this.computeHash(repoFile);
        if (hash != null) {
            map.put(repoFile, hash);
        }
    }

    private void storeHashesForFiles(@NotNull Map<VirtualFile, Integer> files) {
        synchronized (this.myLock) {
            this.myFileHashes.clear();
            this.myFileHashes.putAll(files);
        }
    }

    @Nullable
    private Integer getStoredHashForFile(@NotNull VirtualFile file) {
        synchronized (this.myLock) {
            return (Integer) this.myFileHashes.get(file);
        }
    }

    private boolean containsChangedFile(@NotNull VirtualFile file) {
        synchronized (this.myLock) {
            return this.myChangedFiles.contains(file);
        }
    }

    @Nullable
    private Integer computeHash(@NotNull VirtualFile file) {
        PsiFile psiFile = PsiManager.getInstance(this.myProject).findFile(file);
        return psiFile != null && psiFile.isValid() ? psiFile.getText().hashCode() : null;
    }

    private boolean areHashesEqual(@NotNull VirtualFile file) {
        Integer oldHash = this.getStoredHashForFile(file);
        return oldHash != null && oldHash.equals(this.computeHash(file));
    }

    private boolean checkHashesOfChangedFiles() {
        synchronized (this.myLock) {
            return this.filterHashes(this.myChangedFiles);
        }
    }

    private boolean filterHashes(@NotNull Collection<VirtualFile> files) {
        boolean status = true;
        Set<VirtualFile> toRemove = new HashSet();
        Iterator var4 = files.iterator();

        while (var4.hasNext()) {
            VirtualFile file = (VirtualFile) var4.next();
            if (!this.areHashesEqual(file)) {
                status = false;
            } else {
                toRemove.add(file);
            }
        }

        files.removeAll(toRemove);
        return status;
    }

    private void updateFileHashes() {
        Map<VirtualFile, Integer> fileHashes = new HashMap();
        List<Module> modules = com.google.common.collect.Lists.newArrayList(ModuleManager.getInstance(this.myProject).getModules());
        JobLauncher jobLauncher = JobLauncher.getInstance();
        jobLauncher.invokeConcurrentlyUnderProgress(modules, null, true, (module) -> {
            VirtualFile repoFile = getRepoFile(module);
            if (repoFile != null) {
                File path = VfsUtilCore.virtualToIoFile(repoFile);
                if (path.isFile()) {
                    this.putHashForFile(fileHashes, repoFile);
                }
            }
            return true;
        });
        this.storeHashesForFiles(fileHashes);
    }

    @Nullable
    private VirtualFile getRepoFile(@NotNull Module module) {
        File moduleFilePath = new File(module.getModuleFilePath());
        File parentFile = moduleFilePath.getParentFile();
        if (parentFile != null) {
            File repoFile = new File(parentFile, "repo.xml");
            return VfsUtil.findFileByIoFile(repoFile, true);
        } else {
            return null;
        }
    }

    public boolean areRepoFilesModified() {
        return ApplicationManager.getApplication().runReadAction(new Computable<Boolean>() {
            @Override
            public Boolean compute() {
                return !RepoFileState.this.checkHashesOfChangedFiles();
            }
        });
    }

    public boolean isRepoFile(@NotNull VirtualFile file) {
        String filename = file.getName();
        return filename.equals("repo.xml") || filename.equals("repo-local.xml");
    }

    public boolean isSync() {
        return isSync;
    }

    public void notifyUser() {
        AppUIUtil.invokeLaterIfProjectAlive(myProject, new Runnable() {
            @Override
            public void run() {
                EditorNotifications notifications = EditorNotifications.getInstance(myProject);
                VirtualFile[] files = FileEditorManager.getInstance(myProject).getOpenFiles();
                for (VirtualFile file : files) {
                    notifications.updateNotifications(file);
                }

                BuildVariantView.getInstance(myProject).updateContents();
            }
        });
    }

    private static class RepoFileChangeListener extends PsiTreeChangeAdapter {
        @NotNull
        private final RepoFileState myRepoFileState;

        private RepoFileChangeListener(@NotNull RepoFileState repoFileState) {
            this.myRepoFileState = repoFileState;
        }

        public void childAdded(@NotNull PsiTreeChangeEvent event) {
            if (event == null) {
                return;
            }

            this.processEvent(event, event.getChild());

        }

        public void childRemoved(@NotNull PsiTreeChangeEvent event) {
            if (event == null) {
                return;
            }

            this.processEvent(event, event.getChild());
        }

        public void childReplaced(@NotNull PsiTreeChangeEvent event) {
            if (event == null) {
                return;
            }

            this.processEvent(event, event.getNewChild(), event.getOldChild());
        }

        public void childMoved(@NotNull PsiTreeChangeEvent event) {
            if (event == null) {
                return;
            }

            this.processEvent(event, event.getChild());
        }

        public void childrenChanged(@NotNull PsiTreeChangeEvent event) {
            if (event == null) {
                return;
            }

            this.processEvent(event, event.getOldChild(), event.getNewChild());
        }

        private void processEvent(@NotNull PsiTreeChangeEvent event, @NotNull PsiElement... elements) {
            PsiFile psiFile = event.getFile();
            if (psiFile != null) {
                if (this.myRepoFileState.isRepoFile(psiFile.getVirtualFile())) {
                    if (!this.myRepoFileState.containsChangedFile(psiFile.getVirtualFile())) {
                        if (this.myRepoFileState.myProject.isInitialized() && PsiManager.getInstance(this.myRepoFileState.myProject).isInProject(psiFile)) {
                            boolean foundChange = false;
                            PsiElement[] var6 = elements;
                            int var7 = elements.length;

                            for (int var8 = 0; var8 < var7; ++var8) {
                                PsiElement element = var6[var8];
                                if (element != null && !(element instanceof PsiWhiteSpace)) {
                                    foundChange = true;
                                    break;
                                }
                            }

                            if (foundChange) {
                                this.myRepoFileState.addChangedFile(psiFile.getVirtualFile());
                                this.myRepoFileState.notifyUser();
                            }
                        }
                    }
                }
            }
        }
    }

    private class SyncListener implements GradleSyncListener {

        private SyncListener() {
        }

        public void syncStarted(@NotNull Project project, boolean skipped, boolean sourceGenerationRequested) {
            isSync = true;
        }

        @Override
        public void syncSucceeded(@NotNull Project project) {
            isSync = false;
            this.maybeProcessSyncStarted(project);
        }

        @Override
        public void syncSkipped(@NotNull Project project) {
            isSync = false;
        }

        @Override
        public void syncFailed(@NotNull Project project, @NotNull String errorMessage) {
            isSync = false;
        }

        private void maybeProcessSyncStarted(@NotNull Project project) {
            if (project.isInitialized() || !project.equals(RepoFileState.this.myProject)) {
                if (ApplicationManager.getApplication().isReadAccessAllowed()) {
                    RepoFileState.this.updateFileHashes();
                    RepoFileState.this.removeChangedFiles();
                } else {
                    ApplicationManager.getApplication().runReadAction(() -> {
                        RepoFileState.this.updateFileHashes();
                        RepoFileState.this.removeChangedFiles();
                    });
                }

            }
        }

    }

}
