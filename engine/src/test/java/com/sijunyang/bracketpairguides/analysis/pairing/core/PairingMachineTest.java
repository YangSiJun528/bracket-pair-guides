package com.sijunyang.bracketpairguides.analysis.pairing.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PairingMachineTest {
    @Test
    public void nestedTokensPreserveCompletionOrderDepthAndGeometry() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(session, "main", '(', BracketRole.OPEN, 0, 2, 1);
        accept(session, "main", '[', BracketRole.OPEN, 3, 1, 2);
        accept(session, "main", ']', BracketRole.CLOSE, 4, 1, 2);
        accept(session, "main", ')', BracketRole.CLOSE, 5, 2, 3);

        assertEquals(List.of(
                new PairRecord(3, 1, 4, 1, 1, 2, 2),
                new PairRecord(0, 2, 5, 2, 0, 1, 3)
        ), output.pairs);
    }

    @Test
    public void unrelatedCloserDoesNotLoseAnOpener() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(session, "main", '(', BracketRole.OPEN, 0, 1, 0);
        accept(session, "main", '}', BracketRole.CLOSE, 1, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 2, 1, 0);

        assertEquals(List.of(new PairRecord(0, 1, 2, 1, 0, 0, 0)), output.pairs);
    }

    @Test
    public void matcherGroupsKeepIndependentStacksAndDepths() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(session, "host", '{', BracketRole.OPEN, 0, 1, 0);
        accept(session, "embedded", '(', BracketRole.OPEN, 1, 1, 0);
        accept(session, "host", '}', BracketRole.CLOSE, 2, 1, 0);
        accept(session, "embedded", ')', BracketRole.CLOSE, 3, 1, 0);

        assertEquals(0, output.pairs.get(0).depth);
        assertEquals(0, output.pairs.get(1).depth);
        assertEquals(0, output.pairs.get(0).openOffset);
        assertEquals(1, output.pairs.get(1).openOffset);
    }

    @Test
    public void malformedRecoveryDiscardsUnclosedInnerTokens() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(
                output,
                REGULAR_CHAR_RULES
        );

        accept(session, "main", '{', BracketRole.OPEN, 0, 1, 0);
        accept(session, "main", '(', BracketRole.OPEN, 1, 1, 0);
        accept(session, "main", '}', BracketRole.CLOSE, 2, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 3, 1, 0);

        assertEquals(List.of(new PairRecord(0, 1, 2, 1, 0, 0, 0)), output.pairs);
    }

    @Test
    public void structuralCloserRecoversPastRegularOpenersWithPriority() {
        RecordedPairs output = new RecordedPairs();
        PairingRules<Character> sharedCloser = rules(
                (left, right) -> right == 'x' && (left == '{' || left == '('),
                (left, right) -> left == '{' && right == 'x'
        );
        PairingMachine<Character, String>.Session session = session(output, sharedCloser);

        accept(
                session,
                "main",
                '{',
                BracketRole.OPEN,
                StructuralRole.OPEN,
                0,
                1,
                0
        );
        accept(session, "main", '(', BracketRole.OPEN, 1, 1, 0);
        accept(
                session,
                "main",
                'x',
                BracketRole.CLOSE,
                StructuralRole.CLOSE,
                2,
                1,
                0
        );

        assertEquals(List.of(new PairRecord(0, 1, 2, 1, 0, 0, 0)), output.pairs);
    }

    @Test
    public void regularPairCannotCrossAnUnmatchedStructuralOpener() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(session, "main", '(', BracketRole.OPEN, 0, 1, 0);
        accept(
                session,
                "main",
                '{',
                BracketRole.OPEN,
                StructuralRole.OPEN,
                1,
                1,
                0
        );
        accept(session, "main", ')', BracketRole.CLOSE, 2, 1, 0);
        assertTrue(output.pairs.isEmpty());

        accept(
                session,
                "main",
                '}',
                BracketRole.CLOSE,
                StructuralRole.CLOSE,
                3,
                1,
                0
        );
        accept(session, "main", ')', BracketRole.CLOSE, 4, 1, 0);

        assertEquals(2, output.pairs.size());
        assertEquals(1, output.pairs.get(0).openOffset);
        assertEquals(0, output.pairs.get(1).openOffset);
    }

    @Test
    public void regularPairCanBeContainedByAStructuralPair() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(
                session,
                "main",
                '{',
                BracketRole.OPEN,
                StructuralRole.OPEN,
                0,
                1,
                0
        );
        accept(session, "main", '(', BracketRole.OPEN, 1, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 2, 1, 0);
        accept(
                session,
                "main",
                '}',
                BracketRole.CLOSE,
                StructuralRole.CLOSE,
                3,
                1,
                0
        );

        assertEquals(2, output.pairs.size());
        assertEquals(1, output.pairs.get(0).openOffset);
        assertEquals(0, output.pairs.get(1).openOffset);
    }

    @Test(timeout = 10_000)
    public void unrelatedClosersDoNotRescanADeepStack() {
        int depth = 50_000;
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);
        for (int offset = 0; offset < depth; offset++) {
            accept(session, "main", '(', BracketRole.OPEN, offset, 1, 0);
        }

        for (int index = 0; index < depth; index++) {
            accept(session, "main", ']', BracketRole.CLOSE, depth + index, 1, 0);
        }
        assertTrue(output.pairs.isEmpty());

        for (int index = 0; index < depth; index++) {
            accept(session, "main", ')', BracketRole.CLOSE, depth * 2 + index, 1, 0);
        }
        assertEquals(depth, output.pairs.size());
        assertEquals(depth - 1, output.pairs.get(0).depth);
        assertEquals(0, output.pairs.get(depth - 1).depth);
    }

    @Test(timeout = 10_000)
    public void regularClosersDoNotScanBeyondAStructuralBoundary() {
        int depth = 50_000;
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);
        for (int offset = 0; offset < depth; offset++) {
            accept(session, "main", '(', BracketRole.OPEN, offset, 1, 0);
        }
        accept(
                session,
                "main",
                '{',
                BracketRole.OPEN,
                StructuralRole.OPEN,
                depth,
                1,
                0
        );

        for (int index = 0; index < depth; index++) {
            accept(session, "main", ')', BracketRole.CLOSE, depth + index + 1, 1, 0);
        }
        assertTrue(output.pairs.isEmpty());
        accept(
                session,
                "main",
                '}',
                BracketRole.CLOSE,
                StructuralRole.CLOSE,
                depth * 2 + 1,
                1,
                0
        );
        for (int index = 0; index < depth; index++) {
            accept(session, "main", ')', BracketRole.CLOSE, depth * 2 + index + 2, 1, 0);
        }

        assertEquals(depth + 1, output.pairs.size());
        assertEquals(depth, output.pairs.get(0).openOffset);
        assertEquals(depth - 1, output.pairs.get(1).depth);
        assertEquals(0, output.pairs.get(depth).depth);
    }

    @Test
    public void groupRulesAreResolvedOnlyOncePerSession() {
        RecordedPairs output = new RecordedPairs();
        int[] resolutions = {0};
        PairingMachine<Character, String> machine = new PairingMachine<>(group -> {
            resolutions[0]++;
            return CHAR_RULES;
        });
        PairingMachine<Character, String>.Session session = machine.newSession(
                output,
                NO_CANCELLATION,
                null
        );

        accept(session, "main", '(', BracketRole.OPEN, 0, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 1, 1, 0);
        accept(session, "main", '[', BracketRole.OPEN, 2, 1, 0);
        accept(session, "main", ']', BracketRole.CLOSE, 3, 1, 0);

        assertEquals(1, resolutions[0]);
        assertEquals(2, output.pairs.size());
    }

    @Test
    public void strictContextRequiresTheSameNormalizedValue() {
        RecordedPairs output = new RecordedPairs();
        PairingRules<String> tagRules = rules(
                (left, right) -> left.equals("start") && right.equals("end"),
                (left, right) -> false
        );
        PairingMachine<String, String> machine = new PairingMachine<>(ignored -> tagRules);
        PairingMachine<String, String>.Session session = machine.newSession(
                output,
                NO_CANCELLATION,
                null
        );
        session.accept(
                "xml", "start", "section", true,
                BracketRole.OPEN, StructuralRole.NONE, 0, 1, 0
        );
        session.accept(
                "xml", "start", "item", true,
                BracketRole.OPEN, StructuralRole.NONE, 1, 1, 0
        );
        session.accept(
                "xml", "end", "other", true,
                BracketRole.CLOSE, StructuralRole.NONE, 2, 1, 0
        );
        session.accept(
                "xml", "end", "section", true,
                BracketRole.CLOSE, StructuralRole.NONE, 3, 1, 0
        );

        assertEquals(List.of(new PairRecord(0, 1, 3, 1, 0, 0, 0)), output.pairs);
    }

    @Test
    public void strictContextCanUseANullKey() {
        RecordedPairs output = new RecordedPairs();
        PairingRules<String> tagRules = rules(
                (left, right) -> left.equals("start") && right.equals("end"),
                (left, right) -> false
        );
        PairingMachine<String, String>.Session session =
                new PairingMachine<String, String>(ignored -> tagRules).newSession(
                        output,
                        NO_CANCELLATION,
                        null
                );

        session.accept(
                "xml", "start", null, true,
                BracketRole.OPEN, StructuralRole.NONE, 0, 1, 0
        );
        session.accept(
                "xml", "end", null, true,
                BracketRole.CLOSE, StructuralRole.NONE, 1, 1, 0
        );

        assertEquals(1, output.pairs.size());
    }

    @Test
    public void pureSymmetricTokensCloseBeforeTheyOpenAgain() {
        RecordedPairs output = new RecordedPairs();
        BracketRole symmetric = BracketRole.TOGGLE;
        PairingRules<Character> rules = rules(
                (left, right) -> left == '|' && right == '|',
                (left, right) -> false
        );
        PairingMachine<Character, String>.Session session = session(output, rules);

        accept(session, "main", '|', symmetric, 0, 1, 0);
        accept(session, "main", '|', symmetric, 2, 1, 0);
        accept(session, "main", '|', symmetric, 4, 1, 0);
        accept(session, "main", '|', symmetric, 6, 1, 0);

        assertEquals(List.of(
                new PairRecord(0, 1, 2, 1, 0, 0, 0),
                new PairRecord(4, 1, 6, 1, 0, 0, 0)
        ), output.pairs);
    }

    @Test
    public void structuralToggleCanOpenAndCloseTheSameScope() {
        RecordedPairs output = new RecordedPairs();
        PairingRules<Character> rules = rules(
                (left, right) -> left == '|' && right == '|',
                (left, right) -> left == '|' && right == '|'
        );
        PairingMachine<Character, String>.Session session = session(output, rules);

        accept(
                session, "main", '|', BracketRole.TOGGLE,
                StructuralRole.OPEN_AND_CLOSE, 0, 1, 0
        );
        accept(
                session, "main", '|', BracketRole.TOGGLE,
                StructuralRole.OPEN_AND_CLOSE, 1, 1, 0
        );

        assertEquals(1, output.pairs.size());
        assertEquals(0, output.pairs.get(0).openOffset);
    }

    @Test
    public void trackedOffsetRemainsLiveUntilEveryGroupRemovesIt() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String> machine = new PairingMachine<>(ignored -> CHAR_RULES);
        PairingMachine<Character, String>.Session session = machine.newSession(
                output,
                NO_CANCELLATION,
                7
        );

        accept(session, "round", '(', BracketRole.OPEN, 7, 1, 0);
        accept(session, "square", '[', BracketRole.OPEN, 7, 1, 0);
        assertTrue(session.hasOpenAt(7));
        accept(session, "round", ')', BracketRole.CLOSE, 8, 1, 0);
        assertTrue(session.hasOpenAt(7));
        accept(session, "square", ']', BracketRole.CLOSE, 9, 1, 0);
        assertFalse(session.hasOpenAt(7));
    }

    @Test
    public void trackedCandidateReportsMissingEarlierStructuralContext() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String> machine = new PairingMachine<>(ignored -> CHAR_RULES);
        PairingMachine<Character, String>.Session session = machine.newSession(
                output,
                NO_CANCELLATION,
                7
        );

        accept(session, "main", '(', BracketRole.OPEN, 7, 1, 0);
        accept(
                session,
                "main",
                '}',
                BracketRole.CLOSE,
                StructuralRole.CLOSE,
                8,
                1,
                0
        );

        assertTrue(session.requiresEarlierStructuralContext());
        assertTrue(session.hasOpenAt(7));
        assertTrue(output.pairs.isEmpty());
    }

    @Test
    public void deepMalformedRecoveryChecksCancellation() {
        RecordedPairs output = new RecordedPairs();
        int[] checks = {0};
        CancellationProbe cancellation = () -> {
            if (++checks[0] == 3) {
                throw new TestCancellation();
            }
        };
        PairingRules<String> tagRules = rules(
                (left, right) -> left.equals("start") && right.equals("end"),
                (left, right) -> false
        );
        PairingMachine<String, String> machine = new PairingMachine<>(ignored -> tagRules);
        PairingMachine<String, String>.Session session = machine.newSession(
                output,
                cancellation,
                null
        );
        session.accept(
                "xml", "start", "root", true,
                BracketRole.OPEN, StructuralRole.NONE, 0, 1, 0
        );
        for (int i = 0; i < 10_000; i++) {
            session.accept(
                    "xml",
                    "start",
                    "dangling-" + i,
                    true,
                    BracketRole.OPEN,
                    StructuralRole.NONE,
                    i + 1,
                    1,
                    0
            );
        }

        assertThrows(
                TestCancellation.class,
                () -> session.accept(
                        "xml",
                        "end",
                        "root",
                        true,
                        BracketRole.CLOSE,
                        StructuralRole.NONE,
                        20_000,
                        1,
                        0
                )
        );
        assertEquals(3, checks[0]);
    }

    private static PairingMachine<Character, String>.Session session(RecordedPairs output) {
        return session(output, CHAR_RULES);
    }

    private static PairingMachine<Character, String>.Session session(
            RecordedPairs output,
            PairingRules<Character> rules
    ) {
        PairingMachine<Character, String> machine = new PairingMachine<>(ignored -> rules);
        return machine.newSession(output, NO_CANCELLATION, null);
    }

    private static void accept(
            PairingMachine<Character, String>.Session session,
            String group,
            char token,
            BracketRole role,
            int offset,
            int tokenLength,
            int line
    ) {
        accept(
                session,
                group,
                token,
                role,
                StructuralRole.NONE,
                offset,
                tokenLength,
                line
        );
    }

    private static void accept(
            PairingMachine<Character, String>.Session session,
            String group,
            char token,
            BracketRole role,
            StructuralRole structuralRole,
            int offset,
            int tokenLength,
            int line
    ) {
        session.accept(
                group,
                token,
                null,
                false,
                role,
                structuralRole,
                offset,
                tokenLength,
                line
        );
    }

    private static <T> PairingRules<T> rules(
            BiPredicate<T, T> pair,
            BiPredicate<T, T> structuralPair
    ) {
        return new PairingRules<>() {
            @Override
            public boolean isPair(T openToken, T closeToken) {
                return pair.test(openToken, closeToken);
            }

            @Override
            public boolean isStructuralPair(T openToken, T closeToken) {
                return structuralPair.test(openToken, closeToken);
            }
        };
    }

    private static final PairingRules<Character> CHAR_RULES = rules(
            (left, right) -> switch (left) {
                case '(' -> right == ')';
                case '[' -> right == ']';
                case '{' -> right == '}';
                default -> false;
            },
            (left, right) -> left == '{' && right == '}'
    );
    private static final PairingRules<Character> REGULAR_CHAR_RULES = rules(
            CHAR_RULES::isPair,
            (left, right) -> false
    );

    private static final CancellationProbe NO_CANCELLATION = () -> { };

    private static final class RecordedPairs implements PairSink {
        private final List<PairRecord> pairs = new ArrayList<>();

        @Override
        public void accept(
                int openOffset,
                int openTokenLength,
                int closeOffset,
                int closeTokenLength,
                int depth,
                int openLine,
                int closeLine
        ) {
            pairs.add(new PairRecord(
                    openOffset,
                    openTokenLength,
                    closeOffset,
                    closeTokenLength,
                    depth,
                    openLine,
                    closeLine
            ));
        }
    }

    private record PairRecord(
            int openOffset,
            int openLength,
            int closeOffset,
            int closeLength,
            int depth,
            int openLine,
            int closeLine
    ) {
    }

    private static final class TestCancellation extends RuntimeException {
    }
}
