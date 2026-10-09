package com.sijunyang.bracketpairguides.core.internal;

/** Deterministic compatibility rules for normalized bracket-token types. */
@FunctionalInterface
interface PairingRules<T> {
    boolean isPair(T openToken, T closeToken);
}
