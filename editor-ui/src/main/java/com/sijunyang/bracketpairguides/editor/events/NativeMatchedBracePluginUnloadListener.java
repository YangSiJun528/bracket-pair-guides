package com.sijunyang.bracketpairguides.editor.events;

import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import java.util.Objects;
import java.util.function.Consumer;
import org.jetbrains.annotations.NotNull;

/**
 * Java adapter that implements only the lifecycle callback used by the plugin.
 *
 * <p>Kotlin generates compatibility bridges for every default method on {@link
 * DynamicPluginListener}, including methods deprecated by newer IDEs. Keeping this adapter in Java
 * avoids emitting those unused bridges.
 */
final class NativeMatchedBracePluginUnloadListener implements DynamicPluginListener {
    private final Consumer<IdeaPluginDescriptor> beforeUnload;

    NativeMatchedBracePluginUnloadListener(Consumer<IdeaPluginDescriptor> beforeUnload) {
        this.beforeUnload = Objects.requireNonNull(beforeUnload, "beforeUnload");
    }

    @Override
    public void beforePluginUnload(
            @NotNull IdeaPluginDescriptor pluginDescriptor, boolean isUpdate) {
        beforeUnload.accept(pluginDescriptor);
    }
}
