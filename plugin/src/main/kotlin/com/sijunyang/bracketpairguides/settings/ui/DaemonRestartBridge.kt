package com.sijunyang.bracketpairguides.settings.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Bridges the daemon restart API across the supported 2024.1–2026.2 range.
 *
 * The reason overload was added after 2024.1, while the no-argument overload is
 * deprecated in current IDEs. Reflection keeps both calls out of the plugin's
 * static linkage and prefers the current API whenever it is available.
 */
internal object DaemonRestartBridge {
    private const val RESTART_REASON = "Bracket Pair Guides settings changed"

    private val restartMethods = object : ClassValue<Method>() {
        override fun computeValue(type: Class<*>): Method = resolveRestartMethod(type)
    }

    fun restart(analyzer: DaemonCodeAnalyzer) {
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

    internal fun resolveRestartMethod(
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
