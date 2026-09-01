package com.sijunyang.bracketpairguides.editor.highlighting

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.sijunyang.bracketpairguides.analysis.BraceMatcherAvailability
import com.sijunyang.bracketpairguides.editor.EditorGuideSessions
import java.util.function.Function
import javax.swing.JComponent

/** Warns when an analyzed file exposes no compatible IntelliJ brace matcher. */
internal class UnsupportedBackendNotificationProvider private constructor(
    private val productCode: () -> String,
    private val openUrl: (String) -> Unit,
) : EditorNotificationProvider,
    DumbAware {
    // IntelliJ instantiates this constructor from the plugin.xml extension declaration.
    @Suppress("unused")
    constructor() : this(
        productCode = ::currentProductCode,
        openUrl = BrowserUtil::browse,
    )

    internal constructor(
        productCode: String,
        openUrl: (String) -> Unit,
    ) : this(
        productCode = { productCode },
        openUrl = openUrl,
    )

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?> {
        if (isHidden(project, file)) return EMPTY_NOTIFICATION
        return Function { fileEditor ->
            val editor = (fileEditor as? TextEditor)?.editor
            if (editor == null) null else notificationPanel(project, file, editor)
        }
    }

    internal fun notificationPanel(project: Project, file: VirtualFile, editor: Editor): EditorNotificationPanel? {
        if (isHidden(project, file)) return null
        if (EditorGuideSessions.get(editor)?.matcherAvailability !=
            BraceMatcherAvailability.UNAVAILABLE
        ) {
            return null
        }

        val isReSharper = isReSharperProduct(productCode())
        return EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
            text(if (isReSharper) RESHARPER_MESSAGE else MESSAGE)
            createActionLabel(
                if (isReSharper) SUPPORT_ACTION_TEXT else DOCUMENTATION_ACTION_TEXT,
            ) {
                openUrl(if (isReSharper) SUPPORT_REQUEST_URL else LANGUAGE_SUPPORT_URL)
            }
            setCloseAction {
                PropertiesComponent.getInstance(project).setValue(
                    hiddenProperty(file),
                    true,
                )
                EditorNotifications.getInstance(project).updateNotifications(file)
            }
        }
    }

    private fun isHidden(project: Project, file: VirtualFile): Boolean =
        PropertiesComponent.getInstance(project).getBoolean(hiddenProperty(file), false)

    companion object {
        private val EMPTY_NOTIFICATION = Function<FileEditor, JComponent?> { null }

        internal const val SUPPORT_REQUEST_URL =
            "https://github.com/YangSiJun528/bracket-pair-guides/issues/19"
        internal const val LANGUAGE_SUPPORT_URL =
            "https://github.com/YangSiJun528/bracket-pair-guides/blob/main/docs/reference_language_support.md"
        internal const val HIDDEN_PROPERTY_PREFIX =
            "bracket.pair.guides.unsupported.backend.notification.hidden."
        internal const val MESSAGE =
            "Bracket Pair Guides cannot support this file because its language backend " +
                "provides no compatible IntelliJ brace matcher."
        internal const val RESHARPER_MESSAGE =
            "$MESSAGE Add a thumbs-up reaction to the Rider/CLion ReSharper backend " +
                "support request if you need this integration."
        internal const val SUPPORT_ACTION_TEXT = "View support request"
        internal const val DOCUMENTATION_ACTION_TEXT = "View language support"

        fun update(editor: Editor) {
            val project = editor.project?.takeUnless(Project::isDisposed) ?: return
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
            EditorNotifications.getInstance(project).updateNotifications(file)
        }

        internal fun hiddenProperty(file: VirtualFile): String = HIDDEN_PROPERTY_PREFIX + file.fileType.name

        private fun currentProductCode(): String = ApplicationInfo.getInstance().build.productCode

        private fun isReSharperProduct(productCode: String): Boolean = productCode == "RD" || productCode == "CL"
    }
}
