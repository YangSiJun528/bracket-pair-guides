package com.sijunyang.bracketpairguides.analysis.pairing.core;

/** Deterministic compatibility rules for normalized bracket-token types. */
@FunctionalInterface
public interface PairingRules<T> {
    boolean isPair(T openToken, T closeToken);
}
