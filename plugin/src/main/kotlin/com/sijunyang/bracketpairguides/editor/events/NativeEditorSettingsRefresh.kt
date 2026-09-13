package com.sijunyang.bracketpairguides.editor.events

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.project.ProjectManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/** Refreshes native editor settings and reapplies caret-bound brace highlighting. */
internal object NativeEditorSettingsRefresh {
    fun request() {
        check(ApplicationManager.getApplication().isDispatchThread) {
            "Native editor settings must be refreshed on the EDT"
        }
        EditorFactory.getInstance().refreshAllEditors()
        UISettings.getInstance().fireUISettingsChanged()
        for (project in ProjectManager.getInstance().openProjects) {
            DaemonCodeAnalyzer.getInstance(project).settingsChanged()
        }
        NativeBraceHighlightingRefresh.request()
    }
}

/**
 * IntelliJ exposes no public API for refreshing brace highlighting at a stationary caret.
 * Re-emitting the existing caret position uses the platform's normal highlighting path without
 * changing the caret, selection, or scroll position.
 */
internal object NativeBraceHighlightingRefresh {
    private val log = Logger.getInstance(NativeBraceHighlightingRefresh::class.java)
    private val contract = CaretEventRefreshContract()
    private val warned = AtomicBoolean()

    fun request() {
        for (editor in EditorFactory.getInstance().allEditors) {
            val project = editor.project
            if (
                !editor.isDisposed &&
                project != null &&
                !project.isDisposed &&
                editor.contentComponent.isShowing
            ) {
                retrigger(editor)
            }
        }
    }

    internal fun retrigger(editor: Editor): Boolean {
        if (editor.isDisposed) return false
        val caretModel = editor.caretModel
        val caret = caretModel.primaryCaret
        if (!caret.isValid) return false
        val method = contract.methodFor(caretModel.javaClass)
        if (method == null) {
            warnOnce("IntelliJ does not expose the expected caret refresh hook")
            return false
        }
        val position = caret.logicalPosition
        try {
            method.invoke(caretModel, CaretEvent(caret, position, position))
        } catch (error: InvocationTargetException) {
            when (val cause = error.targetException) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("Could not refresh native brace highlighting", cause)
            }
        } catch (error: ReflectiveOperationException) {
            warnOnce("Could not access IntelliJ's caret refresh hook", error)
            return false
        } catch (error: IllegalArgumentException) {
            warnOnce("IntelliJ's caret refresh hook has an incompatible contract", error)
            return false
        }
        return true
    }

    private fun warnOnce(message: String, error: Throwable? = null) {
        if (!warned.compareAndSet(false, true)) return
        if (error == null) {
            log.warn(message)
        } else {
            log.warn(message, error)
        }
    }
}

/** Resolves the stable package-private hook without linking against editor implementation classes. */
internal class CaretEventRefreshContract {
    fun methodFor(type: Class<*>): Method? {
        var current: Class<*>? = type
        while (current != null) {
            val method =
                current.declaredMethods.firstOrNull { candidate ->
                    candidate.name == METHOD_NAME &&
                        candidate.returnType == Void.TYPE &&
                        candidate.parameterTypes.contentEquals(PARAMETER_TYPES)
                }
            if (method != null) {
                return try {
                    method.takeIf(Method::trySetAccessible)
                } catch (_: SecurityException) {
                    null
                }
            }
            current = current.superclass
        }
        return null
    }

    private companion object {
        const val METHOD_NAME = "fireCaretPositionChanged"
        val PARAMETER_TYPES = arrayOf(CaretEvent::class.java)
    }
}
