package com.sijunyang.bracketpairguides.compatibility

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class IdeCompatibilityStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project): Unit {
        val extensionArea = ApplicationManager.getApplication().extensionArea
        val compatibility = IdeCompatibility.from(extensionArea::hasExtensionPoint)
        if (compatibility is IdeCompatibility.Unsupported) {
            compatibilityWarning.present(project, compatibility)
        }
    }

    private companion object {
        private const val NOTIFICATION_GROUP_ID = "Bracket Pair Guides errors"

        private val compatibilityWarning = UnsupportedIdeWarning { project, content ->
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(
                    content.title,
                    content.message,
                    NotificationType.ERROR,
                )
                .notify(project)
        }
    }
}
