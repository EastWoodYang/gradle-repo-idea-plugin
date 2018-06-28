package com.eastwood.tools.idea;

import com.intellij.execution.RunManagerEx;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings;
import com.intellij.openapi.externalSystem.model.execution.ExternalTaskExecutionInfo;
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode;
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemNotificationManager;
import com.intellij.openapi.externalSystem.service.notification.NotificationCategory;
import com.intellij.openapi.externalSystem.service.notification.NotificationData;
import com.intellij.openapi.externalSystem.service.notification.NotificationSource;
import com.intellij.openapi.externalSystem.task.TaskCallback;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.execution.ParametersListUtil;
import org.gradle.cli.CommandLineArgumentException;
import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;
import org.jetbrains.plugins.gradle.service.execution.cmd.GradleCommandLineOptionsConverter;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class GradleTaskExecutor {

    public static void executeTask(Project project, String task, TaskCallback taskCallback) {
        ExternalTaskExecutionInfo taskExecutionInfo;
        try {
            taskExecutionInfo = buildTaskInfo(project.getBasePath(), task);
        } catch (CommandLineArgumentException var11) {
            NotificationData notificationData = new NotificationData("<b>Command-line arguments cannot be parsed</b>", "<i>" + task + "</i> \n" + var11.getMessage(), NotificationCategory.WARNING, NotificationSource.TASK_EXECUTION);
            notificationData.setBalloonNotification(true);
            ExternalSystemNotificationManager.getInstance(project).showNotification(GradleConstants.SYSTEM_ID, notificationData);
            return;
        }

        RunManagerEx runManager = RunManagerEx.getInstanceEx(project);
        ExternalSystemUtil.runTask(taskExecutionInfo.getSettings(), taskExecutionInfo.getExecutorId(), project, GradleConstants.SYSTEM_ID, taskCallback, ProgressExecutionMode.IN_BACKGROUND_ASYNC);
        RunnerAndConfigurationSettings configuration = ExternalSystemUtil.createExternalSystemRunnerAndConfigurationSettings(taskExecutionInfo.getSettings(), project, GradleConstants.SYSTEM_ID);
        if (configuration != null) {
            RunnerAndConfigurationSettings existingConfiguration = runManager.findConfigurationByName(configuration.getName());
            if (existingConfiguration == null) {
                runManager.setTemporaryConfiguration(configuration);
            } else {
                runManager.setSelectedConfiguration(existingConfiguration);
            }
        }
    }

    private static ExternalTaskExecutionInfo buildTaskInfo(String projectPath, String fullCommandLine) throws CommandLineArgumentException {
        CommandLineParser gradleCmdParser = new CommandLineParser();
        GradleCommandLineOptionsConverter commandLineConverter = new GradleCommandLineOptionsConverter();
        commandLineConverter.configure(gradleCmdParser);
        ParsedCommandLine parsedCommandLine = gradleCmdParser.parse(ParametersListUtil.parse(fullCommandLine, true));
        Map<String, List<String>> optionsMap = commandLineConverter.convert(parsedCommandLine, new HashMap());
        List<String> systemProperties = (List) optionsMap.remove("system-prop");
        String vmOptions = systemProperties == null ? "" : StringUtil.join(systemProperties, (entry) -> {
            return "-D" + entry;
        }, " ");
        String scriptParameters = StringUtil.join(optionsMap.entrySet(), (entry) -> {
            List<String> values = (List) entry.getValue();
            String longOptionName = (String) entry.getKey();
            return values != null && !values.isEmpty() ? StringUtil.join(values, (entry1) -> {
                return "--" + longOptionName + ' ' + entry1;
            }, " ") : "--" + longOptionName;
        }, " ");
        List<String> tasks = parsedCommandLine.getExtraArguments();
        ExternalSystemTaskExecutionSettings settings = new ExternalSystemTaskExecutionSettings();
        settings.setExternalProjectPath(projectPath);
        settings.setTaskNames(tasks);
        settings.setScriptParameters(scriptParameters);
        settings.setVmOptions(vmOptions);
        settings.setExternalSystemIdString(GradleConstants.SYSTEM_ID.toString());
        return new ExternalTaskExecutionInfo(settings, DefaultRunExecutor.EXECUTOR_ID);
    }
}
