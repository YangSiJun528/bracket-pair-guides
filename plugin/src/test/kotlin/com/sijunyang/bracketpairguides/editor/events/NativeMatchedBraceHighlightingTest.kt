package com.sijunyang.bracketpairguides.editor.events

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.xmlb.XmlSerializer
import com.sijunyang.bracketpairguides.preferences.BracketGuidePreferences
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import java.lang.reflect.Proxy

class NativeMatchedBraceHighlightingTest {
    @Test
    fun `default preferences suppress and later restore a preexisting true value`() {
        val fixture = fixture(initialNativeValue = true)

        fixture.controller.apply(BracketGuidePreferences())
        assertThat(fixture.nativeSetting.enabled).isFalse()
        assertThat(fixture.controller.state.restoreValue).isTrue()

        fixture.controller.apply(
            BracketGuidePreferences(disableNativeMatchedBraceHighlighting = false),
        )
        assertThat(fixture.nativeSetting.enabled).isTrue()
        assertThat(fixture.controller.state.restoreValue).isNull()
    }

    @Test
    fun `release preserves a native setting that was already false`() {
        val fixture = fixture(initialNativeValue = false)

        fixture.controller.apply(BracketGuidePreferences())
        fixture.controller.apply(
            BracketGuidePreferences(disableNativeMatchedBraceHighlighting = false),
        )

        assertThat(fixture.nativeSetting.enabled).isFalse()
        assertThat(fixture.controller.state.restoreValue).isNull()
    }

    @Test
    fun `disabling the plugin releases the owned native setting`() {
        val fixture = fixture(initialNativeValue = true)
        fixture.controller.apply(BracketGuidePreferences())

        fixture.controller.apply(BracketGuidePreferences(enabled = false))

        assertThat(fixture.nativeSetting.enabled).isTrue()
        assertThat(fixture.controller.state.restoreValue).isNull()
    }

    @Test
    fun `dynamic disposal and application exit restore the native setting`() {
        val dynamicUnload = fixture(initialNativeValue = true)
        dynamicUnload.controller.apply(BracketGuidePreferences())

        dynamicUnload.controller.dispose()

        assertThat(dynamicUnload.nativeSetting.enabled).isTrue()
        assertThat(dynamicUnload.controller.state.restoreValue).isNull()

        val applicationExit = fixture(initialNativeValue = true)
        applicationExit.controller.apply(BracketGuidePreferences())
        applicationExit.controller.appWillBeClosed(false)

        applicationExit.controller.dispose()

        assertThat(applicationExit.nativeSetting.enabled).isTrue()
        assertThat(applicationExit.controller.state.restoreValue).isNull()
        assertThat(applicationExit.persistedSnapshots).containsExactly(
            PersistedSnapshot(nativeEnabled = true, restoreValue = null),
        )
    }

    @Test
    fun `external native enablement returns a corrected plugin preference`() {
        val fixture = fixture(initialNativeValue = true)
        fixture.controller.apply(BracketGuidePreferences())
        fixture.nativeSetting.enabled = true

        val reconciled = fixture.controller.apply(BracketGuidePreferences())

        assertThat(fixture.nativeSetting.enabled).isTrue()
        assertThat(fixture.controller.state.restoreValue).isNull()
        assertThat(reconciled.disableNativeMatchedBraceHighlighting).isFalse()
        assertThat(fixture.externalOverrides).isEmpty()
        assertThat(fixture.persistedSnapshots).isEmpty()
    }

    @Test
    fun `application exit records external native enablement before releasing ownership`() {
        val fixture = fixture(initialNativeValue = true)
        fixture.controller.apply(BracketGuidePreferences())
        fixture.nativeSetting.enabled = true

        fixture.controller.appWillBeClosed(false)

        assertThat(fixture.nativeSetting.enabled).isTrue()
        assertThat(fixture.controller.state.restoreValue).isNull()
        assertThat(fixture.externalOverrides).hasSize(1)
        assertThat(fixture.persistedSnapshots).containsExactly(
            PersistedSnapshot(nativeEnabled = true, restoreValue = null),
        )
    }

    @Test
    fun `application exit persists an original false value and skips saving without ownership`() {
        val ownedFalse = fixture(initialNativeValue = false)
        ownedFalse.controller.apply(BracketGuidePreferences())

        ownedFalse.controller.appWillBeClosed(false)

        assertThat(ownedFalse.persistedSnapshots).containsExactly(
            PersistedSnapshot(nativeEnabled = false, restoreValue = null),
        )

        val unowned = fixture(initialNativeValue = true)
        unowned.controller.appWillBeClosed(false)

        assertThat(unowned.persistedSnapshots).isEmpty()
    }

    @Test
    fun `dynamic unload persists restored ownership only for this plugin`() {
        val fixture = fixture(initialNativeValue = true)
        fixture.controller.apply(BracketGuidePreferences())

        fixture.pluginListener.beforePluginUnload(
            pluginDescriptor("unrelated.plugin"),
            false,
        )
        assertThat(fixture.nativeSetting.enabled).isFalse()
        assertThat(fixture.persistedSnapshots).isEmpty()

        fixture.pluginListener.beforePluginUnload(
            pluginDescriptor("com.sijunyang.bracketpairguides"),
            true,
        )

        assertThat(fixture.nativeSetting.enabled).isTrue()
        assertThat(fixture.controller.state.restoreValue).isNull()
        assertThat(fixture.persistedSnapshots).containsExactly(
            PersistedSnapshot(nativeEnabled = true, restoreValue = null),
        )
        assertThat(
            fixture.pluginListener.javaClass.declaredMethods
                .map { it.name },
        ).doesNotContain("checkUnloadPlugin")
    }

    @Test
    fun `ownership survives state serialization without confusing false with absent`() {
        val source = fixture(initialNativeValue = false)
        source.controller.apply(BracketGuidePreferences())
        val serialized = XmlSerializer.serialize(source.controller.state)
        val restored = fixture(initialNativeValue = false)

        restored.controller.loadState(
            XmlSerializer.deserialize(
                serialized,
                NativeMatchedBraceHighlighting.OwnershipState::class.java,
            ),
        )

        assertThat(restored.controller.state.restoreValue).isFalse()
    }

    private fun fixture(initialNativeValue: Boolean): Fixture {
        val nativeSetting = FakeNativeSetting(initialNativeValue)
        val externalOverrides = mutableListOf<Unit>()
        val persistedSnapshots = mutableListOf<PersistedSnapshot>()
        lateinit var pluginListener: DynamicPluginListener
        lateinit var controller: NativeMatchedBraceHighlighting
        controller =
            NativeMatchedBraceHighlighting(
                nativeSetting = nativeSetting,
                onExternalOverride = { externalOverrides += Unit },
                mayMutate = { true },
                persistSettings = {
                    persistedSnapshots +=
                        PersistedSnapshot(
                            nativeEnabled = nativeSetting.enabled,
                            restoreValue = controller.state.restoreValue,
                        )
                },
                subscribeToLifecycle = { _, listener, _ -> pluginListener = listener },
            )
        return Fixture(
            controller,
            pluginListener,
            nativeSetting,
            externalOverrides,
            persistedSnapshots,
        )
    }

    private data class Fixture(
        val controller: NativeMatchedBraceHighlighting,
        val pluginListener: DynamicPluginListener,
        val nativeSetting: FakeNativeSetting,
        val externalOverrides: List<Unit>,
        val persistedSnapshots: List<PersistedSnapshot>,
    )

    private data class PersistedSnapshot(val nativeEnabled: Boolean, val restoreValue: Boolean?)

    private class FakeNativeSetting(override var enabled: Boolean) : NativeMatchedBraceSetting

    private fun pluginDescriptor(id: String): IdeaPluginDescriptor = Proxy.newProxyInstance(
        IdeaPluginDescriptor::class.java.classLoader,
        arrayOf(IdeaPluginDescriptor::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getPluginId" -> PluginId.getId(id)
            "toString" -> id
            else -> error("Unexpected descriptor call: ${method.name}")
        }
    } as IdeaPluginDescriptor
}
