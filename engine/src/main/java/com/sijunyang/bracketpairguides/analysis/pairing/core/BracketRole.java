package com.sijunyang.bracketpairguides.analysis.pairing.core;

/** How a classified bracket token changes a pairing session. */
public enum BracketRole {
    OPEN,
    CLOSE,
    /** Tries to close first and opens only when no compatible opener exists. */
    TOGGLE
}
