package com.sijunyang.bracketpairguides.benchmarks;

import com.sijunyang.bracketpairguides.analysis.pairing.core.PairTable;
import com.sijunyang.bracketpairguides.analysis.pairing.core.BracketRole;
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingMachine;
import com.sijunyang.bracketpairguides.analysis.pairing.core.PairingRules;
import com.sijunyang.bracketpairguides.analysis.pairing.core.StructuralRole;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Thread)
public class PairingMachineBenchmark {
    @Param({"32768", "100000", "200000"})
    public int pairCount;

    private Token[] tokens;
    private BracketRole[] roles;

    @Setup
    public void setup() {
        tokens = new Token[pairCount * 2];
        roles = new BracketRole[tokens.length];
        for (int index = 0; index < pairCount; index++) {
            tokens[index] = Token.OPEN;
            roles[index] = BracketRole.OPEN;
            int closeIndex = tokens.length - index - 1;
            tokens[closeIndex] = Token.CLOSE;
            roles[closeIndex] = BracketRole.CLOSE;
        }
    }

    @Benchmark
    public PairTable pairNestedTokens() {
        PairTable.Draft output = PairTable.draft();
        PairingMachine<Token, Group> machine = new PairingMachine<>(ignored -> RULES);
        PairingMachine<Token, Group>.Session session = machine.newSession(
                output,
                () -> { },
                pairCount
        );
        for (int index = 0; index < tokens.length; index++) {
            session.accept(
                    Group.MAIN,
                    tokens[index],
                    null,
                    false,
                    roles[index],
                    StructuralRole.NONE,
                    index,
                    1,
                    0
            );
        }
        return output.freeze();
    }

    @Benchmark
    public PairTable pairSequentialTokens() {
        PairTable.Draft output = PairTable.draft();
        PairingMachine<Token, Group> machine = new PairingMachine<>(ignored -> RULES);
        PairingMachine<Token, Group>.Session session = machine.newSession(
                output,
                () -> { },
                50_000
        );
        for (int index = 0; index < pairCount; index++) {
            int openOffset = index * 2;
            session.accept(
                    Group.MAIN,
                    Token.OPEN,
                    null,
                    false,
                    BracketRole.OPEN,
                    StructuralRole.NONE,
                    openOffset,
                    1,
                    0
            );
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
        return output.freeze();
    }

    private enum Token {
        OPEN,
        CLOSE
    }

    private enum Group {
        MAIN
    }

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
}
