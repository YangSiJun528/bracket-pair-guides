package com.sijunyang.bracketpairguides.editor

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.ProjectManager
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Cross-version access to the IntelliJ daemon refresh operation. */
internal object DaemonRefresh {
    private const val REASON = "Bracket Pair Guides settings changed"
    private val contract = DaemonRestartContract(DaemonCodeAnalyzer::class.java)

    private val signatures = object : ClassValue<Method>() {
        override fun computeValue(type: Class<*>): Method = contract.methodFor(type)
    }

    fun request() {
        for (project in ProjectManager.getInstance().openProjects) {
            invoke(DaemonCodeAnalyzer.getInstance(project))
        }
    }

    private fun invoke(daemon: DaemonCodeAnalyzer) {
        val method = signatures.get(daemon.javaClass)
        try {
            if (method.parameterCount == 0) {
                method.invoke(daemon)
            } else {
                method.invoke(daemon, REASON)
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

}
