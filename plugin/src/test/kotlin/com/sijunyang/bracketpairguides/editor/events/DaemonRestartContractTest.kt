package com.sijunyang.bracketpairguides.editor.events

import org.junit.Assert.assertEquals
import org.junit.Test

class DaemonRestartContractTest {
    @Test
    fun `uses legacy no-argument restart when reason overload is absent`() {
        val method = contractFor(LegacyDaemonApi::class.java)
            .methodFor(LegacyDaemonApi::class.java)

        assertEquals(0, method.parameterCount)
    }

    @Test
    fun `prefers current reason overload when both forms exist`() {
        val method = contractFor(CurrentApiBase::class.java)
            .methodFor(CurrentDaemonApi::class.java)

        assertEquals(1, method.parameterCount)
        assertEquals(Any::class.java, method.parameterTypes.single())
    }

    @Test
    fun `falls back when legacy subclass inherits unsupported modern default`() {
        val method = contractFor(ModernApiBase::class.java)
            .methodFor(LegacySubclass::class.java)

        assertEquals(0, method.parameterCount)
        assertEquals(ModernApiBase::class.java, method.declaringClass)
    }

    private fun contractFor(publicApiOwner: Class<*>): DaemonRestartContract =
        DaemonRestartContract(publicApiOwner)

    private class LegacyDaemonApi {
        @Suppress("unused")
        fun restart() = Unit
    }

    private open class CurrentApiBase {
        @Suppress("unused")
        fun restart() = Unit

        @Suppress("unused")
        open fun restart(reason: Any) = reason
    }

    private class CurrentDaemonApi : CurrentApiBase() {
        @Suppress("unused")
        override fun restart(reason: Any) = reason
    }

    private open class ModernApiBase {
        @Suppress("unused")
        open fun restart() = Unit

        @Suppress("unused")
        fun restart(reason: Any): Nothing = throw AbstractMethodError(reason.toString())
    }

    private class LegacySubclass : ModernApiBase() {
        @Suppress("unused")
        override fun restart() = Unit
    }
}
