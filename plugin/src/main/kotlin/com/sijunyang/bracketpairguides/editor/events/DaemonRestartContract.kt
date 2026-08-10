package com.sijunyang.bracketpairguides.editor.events

import java.lang.reflect.Method

/** Runtime method contract shared by the current and legacy daemon APIs. */
internal class DaemonRestartContract(
    private val publicApiOwner: Class<*>,
) {
    fun methodFor(type: Class<*>): Method {
        val modernMethod = type.methods.firstOrNull { method ->
            method.name == "restart" &&
                method.parameterTypes.contentEquals(arrayOf(Any::class.java))
        }
        if (modernMethod != null && modernMethod.declaringClass != publicApiOwner) {
            return invocationMethod(type, modernMethod)
        }

        val legacyMethod = type.methods.firstOrNull { method ->
            method.name == "restart" && method.parameterCount == 0
        }
        val selectedMethod = legacyMethod ?: modernMethod
            ?: throw NoSuchMethodException("No daemon restart method on ${type.name}")
        return invocationMethod(type, selectedMethod)
    }

    private fun invocationMethod(
        type: Class<*>,
        selectedMethod: Method,
    ): Method =
        if (publicApiOwner.isAssignableFrom(type)) {
            publicApiOwner.getMethod(selectedMethod.name, *selectedMethod.parameterTypes)
        } else {
            selectedMethod
        }
}
