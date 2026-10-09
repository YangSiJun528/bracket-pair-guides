package com.sijunyang.bracketpairguides.core.internal;

/** How a classified bracket token changes a pairing session. */
enum BracketRole {
    OPEN,
    CLOSE,
    /** Tries to close first and opens only when no compatible opener exists. */
    TOGGLE
}
