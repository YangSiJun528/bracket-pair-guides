package com.sijunyang.bracketpairguides.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DaemonRestartBridgeTest {
    @Test
    fun `uses legacy no-argument restart when reason overload is absent`() {
        val method = DaemonRestartBridge.resolveRestartMethod(LegacyAnalyzer::class.java)

        assertEquals(0, method.parameterCount)
    }

    @Test
    fun `prefers current reason overload when both forms exist`() {
        val method = DaemonRestartBridge.resolveRestartMethod(CurrentAnalyzer::class.java)

        assertEquals(1, method.parameterCount)
        assertEquals(Any::class.java, method.parameterTypes.single())
    }

    @Test
    fun `falls back when legacy subclass inherits unsupported modern default`() {
        val method = DaemonRestartBridge.resolveRestartMethod(
            LegacySubclass::class.java,
            unsupportedModernOwner = ModernApiBase::class.java,
        )

        assertEquals(0, method.parameterCount)
        assertEquals(ModernApiBase::class.java, method.declaringClass)
    }

    private class LegacyAnalyzer {
        @Suppress("unused")
        fun restart() = Unit
    }

    private class CurrentAnalyzer {
        @Suppress("unused")
        fun restart() = Unit

        @Suppress("unused")
        fun restart(reason: Any) = reason
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
