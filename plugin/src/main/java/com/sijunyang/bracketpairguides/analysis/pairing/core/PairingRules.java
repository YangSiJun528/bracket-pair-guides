package com.sijunyang.bracketpairguides.analysis.pairing.core;

/** Deterministic compatibility rules for normalized bracket-token types. */
public interface PairingRules<T> {
    boolean isPair(T openToken, T closeToken);

    boolean isStructuralPair(T openToken, T closeToken);
}
