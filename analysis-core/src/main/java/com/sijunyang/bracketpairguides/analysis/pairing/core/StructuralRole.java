package com.sijunyang.bracketpairguides.analysis.pairing.core;

/** Structural-scope coverage of a classified bracket token. */
public enum StructuralRole {
    NONE(false, false),
    OPEN(true, false),
    CLOSE(false, true),
    OPEN_AND_CLOSE(true, true);

    private final boolean opens;
    private final boolean closes;

    StructuralRole(boolean opens, boolean closes) {
        this.opens = opens;
        this.closes = closes;
    }

    public boolean opens() {
        return opens;
    }

    public boolean closes() {
        return closes;
    }

    public static StructuralRole of(boolean opens, boolean closes) {
        if (opens) {
            return closes ? OPEN_AND_CLOSE : OPEN;
        }
        return closes ? CLOSE : NONE;
    }
}
