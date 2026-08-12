package com.sijunyang.bracketpairguides.benchmarks.probes;

import com.sijunyang.bracketpairguides.analysis.pairing.core.BracketRole;
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable;
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingMachine;
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingRules;
import com.sijunyang.bracketpairguides.analysis.pairing.core.StructuralRole;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.openjdk.jol.info.GraphLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** One-off retained-graph probe; deliberately excluded from normal Gradle source sets. */
public final class RetainedGraphProbe {
    private static final int PAIR_COUNT = 100_000;
    private static final Function0<Unit> NEVER_CANCEL = () -> Unit.INSTANCE;
    private static final PairingRules<Token> RULES = new PairingRules<>() {
        @Override
        public boolean isPair(Token openToken, Token closeToken) {
            return openToken == Token.OPEN && closeToken == Token.CLOSE;
        }

        @Override
        public boolean isStructuralPair(Token openToken, Token closeToken) {
            return false;
        }
    };

    private RetainedGraphProbe() {
    }

    public static void main(String[] args) throws Exception {
        PairTable pairs = recognizeBracketPairGuidesTokens();
        Object tokens = buildIndex(
                "com.sijunyang.bracketpairguides.analysis.token.BracketTokenIndex",
                pairs
        );
        Object activePairs = buildIndex(
                "com.sijunyang.bracketpairguides.analysis.active.ActiveBracketPairIndex",
                pairs
        );
        Class<?> indexesClass = Class.forName(
                "com.sijunyang.bracketpairguides.analysis.snapshot.BracketIndexes"
        );
        Object indexes = indexesClass.getConstructors()[0].newInstance(
                pairs,
                tokens,
                activePairs,
                null
        );

        print("Bracket Pair Guides", GraphLayout.parseInstance(indexes));
    }

    private static PairTable recognizeBracketPairGuidesTokens() {
        PairTable.Draft output = PairTable.draft();
        PairingMachine<Token, Group> machine = new PairingMachine<>(ignored -> RULES);
        PairingMachine<Token, Group>.Session session = machine.newSession(
                output,
                () -> { },
                50_000
        );
        for (int index = 0; index < PAIR_COUNT; index++) {
            int openOffset = index * 2;
            if (!session.accept(
                    Group.MAIN,
                    Token.OPEN,
                    null,
                    false,
                    BracketRole.OPEN,
                    StructuralRole.NONE,
                    openOffset,
                    1,
                    0
            )) {
                throw new IllegalStateException("Sequential input exceeded pending capacity");
            }
            session.accept(
                    Group.MAIN,
                    Token.CLOSE,
                    null,
                    false,
                    BracketRole.CLOSE,
                    StructuralRole.NONE,
                    openOffset + 1,
                    1,
                    0
            );
        }
        PairTable pairs = output.freeze();
        if (pairs.size() != PAIR_COUNT) {
            throw new IllegalStateException("Expected " + PAIR_COUNT + " completed pairs");
        }
        return pairs;
    }

    private static Object buildIndex(String className, PairTable pairs) throws Exception {
        Class<?> indexClass = Class.forName(className);
        Field companionField = indexClass.getField("Companion");
        Object companion = companionField.get(null);
        Method build = companion.getClass().getMethod(
                "build$bracket_pair_guides",
                PairTable.class,
                Function0.class
        );
        return build.invoke(companion, pairs, NEVER_CANCEL);
    }

    private static void print(String label, GraphLayout graph) {
        System.out.printf(
                "%s\t%d objects/arrays\t%d bytes%n",
                label,
                graph.totalCount(),
                graph.totalSize()
        );
    }

    private enum Token {
        OPEN,
        CLOSE
    }

    private enum Group {
        MAIN
    }
}
