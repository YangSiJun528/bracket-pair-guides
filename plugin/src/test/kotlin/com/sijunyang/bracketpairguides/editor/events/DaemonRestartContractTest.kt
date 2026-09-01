package com.sijunyang.bracketpairguides.editor.events

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DaemonRestartContractTest {
    @Test
    fun `uses legacy no-argument restart when reason overload is absent`() {
        val method =
            contractFor(LegacyDaemonApi::class.java)
                .methodFor(LegacyDaemonApi::class.java)

        assertThat(method.parameterCount).isEqualTo(0)
    }

    @Test
    fun `prefers current reason overload when both forms exist`() {
        val method =
            contractFor(CurrentApiBase::class.java)
                .methodFor(CurrentDaemonApi::class.java)

        assertThat(method.parameterCount).isEqualTo(1)
        assertThat(method.parameterTypes.single()).isEqualTo(Any::class.java)
    }

    @Test
    fun `falls back when legacy subclass inherits unsupported modern default`() {
        val method =
            contractFor(ModernApiBase::class.java)
                .methodFor(LegacySubclass::class.java)

        assertThat(method.parameterCount).isEqualTo(0)
        assertThat(method.declaringClass).isEqualTo(ModernApiBase::class.java)
    }

    private fun contractFor(publicApiOwner: Class<*>): DaemonRestartContract = DaemonRestartContract(publicApiOwner)

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
