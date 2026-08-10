package com.sijunyang.bracketpairguides.compatibility

import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

internal class UnsupportedIdeWarning(
    private val channel: (Project, WarningText) -> Unit,
) {
    private val appeared = AtomicBoolean()

    fun present(project: Project, unsupported: IdeCompatibility.Unsupported) {
        if (!appeared.compareAndSet(false, true)) return

        channel(
            project,
            WarningText(
                title = "Unsupported IDE",
                message =
                    "Bracket Pair Guides is not supported in this IDE because it " +
                        "does not provide the ${unsupported.missingExtensionPoint} " +
                        "extension point.",
            ),
        )
    }

    data class WarningText(
        val title: String,
        val message: String,
    )
}
