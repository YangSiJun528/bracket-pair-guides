package com.sijunyang.bracketpairguides.analysis.intellij;

import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

/**
 * Invalidates capture consistency before plugin language/matcher services change.
 *
 * <p>Java inherits unused JVM defaults without the compatibility bridges Kotlin emits, including
 * the unload-veto method deprecated by newer IDEs. Only these stable pre-change callbacks are used.
 */
final class AnalysisPluginLifecycleListener implements DynamicPluginListener {
    private final Runnable invalidate;

    AnalysisPluginLifecycleListener(Runnable invalidate) {
        this.invalidate = Objects.requireNonNull(invalidate, "invalidate");
    }

    @Override
    public void beforePluginLoaded(@NotNull IdeaPluginDescriptor pluginDescriptor) {
        invalidate.run();
    }

    @Override
    public void beforePluginUnload(
            @NotNull IdeaPluginDescriptor pluginDescriptor, boolean isUpdate) {
        invalidate.run();
    }
}
