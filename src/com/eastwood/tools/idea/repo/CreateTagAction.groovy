package com.eastwood.tools.idea.repo

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

import static git4idea.GitUtil.HEAD

class CreateTagAction extends AnAction {

    @Override
    void actionPerformed(AnActionEvent e) {
        Project project = e.getProject()
        final String name = Messages.showInputDialog(project, "Enter the name of new tag", "Create New Tag On " + HEAD,
                Messages.getQuestionIcon(), "", new InputValidator() {
            @Override
            public boolean checkInput(String inputString) {
                return !StringUtil.isEmpty(inputString) && !StringUtil.containsWhitespaces(inputString);
            }

            @Override
            public boolean canClose(String inputString) {
                return !StringUtil.isEmpty(inputString) && !StringUtil.containsWhitespaces(inputString);
            }
        });
        if (name != null) {
            GitBrancher brancher = GitBrancher.getInstance(project);
            List<GitRepository> list = GitRepositoryManager.getInstance(project).getRepositories()
            brancher.createNewTag(name, HEAD, list, null);
        }

    }

}
