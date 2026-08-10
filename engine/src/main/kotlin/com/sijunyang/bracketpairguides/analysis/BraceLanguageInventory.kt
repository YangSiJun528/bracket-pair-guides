package com.sijunyang.bracketpairguides.analysis

import org.jetbrains.annotations.ApiStatus

/** Application contract for brace-matcher families installed in the IDE. */
@ApiStatus.Internal
public interface BraceLanguageInventory {
    public fun families(): List<BraceLanguageFamily>
}
