package com.sijunyang.bracketpairguides.editor

import com.sijunyang.bracketpairguides.settings.PluginOptions
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.ProjectManager
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Propagates applied plugin options to live editor sessions and the daemon. */
@ApiStatus.Internal
internal object EditorGuideSettingsApplier {
    private const val RESTART_REASON = "Bracket Pair Guides settings changed"

    private val restartMethods = object : ClassValue<Method>() {
        override fun computeValue(type: Class<*>): Method = resolveRestartMethod(type)
    }

    public fun applyChanges(previous: PluginOptions, applied: PluginOptions): Unit {
        if (previous == applied) return

        val capabilitiesChanged = previous.analysisCapabilities() !=
            applied.analysisCapabilities()
        val languagesChanged = previous.disabledLanguageIds != applied.disabledLanguageIds
        val sessionEditors = EditorFactory.getInstance().allEditors.filter { editor ->
            !editor.isDisposed && EditorGuideSession.get(editor) != null
        }
        val immediateEditor = EditorGuideEventRouter.preferredImmediateEditor(sessionEditors)

        for (editor in sessionEditors) {
            EditorGuideSession.get(editor)?.updateOptions(
                applied,
                resolveImmediately = editor === immediateEditor,
            )
        }
        if (capabilitiesChanged || languagesChanged) {
            for (project in ProjectManager.getInstance().openProjects) {
                restartDaemon(DaemonCodeAnalyzer.getInstance(project))
            }
        }
    }

    private fun restartDaemon(analyzer: DaemonCodeAnalyzer) {
        val restartMethod = restartMethods.get(analyzer.javaClass)
        try {
            if (restartMethod.parameterCount == 0) {
                restartMethod.invoke(analyzer)
            } else {
                restartMethod.invoke(analyzer, RESTART_REASON)
            }
        } catch (error: InvocationTargetException) {
            val cause = error.cause ?: error
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Could not restart code analysis", cause)
            }
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException("Could not restart code analysis", error)
        }
    }

    @TestOnly
    public fun resolveRestartMethod(
        type: Class<*>,
        unsupportedModernOwner: Class<*> = DaemonCodeAnalyzer::class.java,
    ): Method {
        val modernMethod = type.methods.firstOrNull { method ->
            method.name == "restart" &&
                method.parameterTypes.contentEquals(arrayOf(Any::class.java))
        }
        if (modernMethod != null && modernMethod.declaringClass != unsupportedModernOwner) {
            return invocationMethod(type, unsupportedModernOwner, modernMethod)
        }

        // A subclass compiled against the old abstract API may inherit the new
        // base implementation, which deliberately throws AbstractMethodError.
        // Its legacy override remains the safe call in that case.
        val legacyMethod = type.methods.firstOrNull { method ->
            method.name == "restart" && method.parameterCount == 0
        }
        val selectedMethod = legacyMethod ?: modernMethod
            ?: throw NoSuchMethodException("No daemon restart method on ${type.name}")
        return invocationMethod(type, unsupportedModernOwner, selectedMethod)
    }

    private fun invocationMethod(
        type: Class<*>,
        publicApiOwner: Class<*>,
        selectedMethod: Method,
    ): Method =
        if (publicApiOwner.isAssignableFrom(type)) {
            publicApiOwner.getMethod(selectedMethod.name, *selectedMethod.parameterTypes)
        } else {
            selectedMethod
        }
}
