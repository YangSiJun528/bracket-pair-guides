package com.sijunyang.bracketpairguides.analysis.pairing.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import org.junit.Test;

public class PairingMachineTest {
    @Test
    public void nestedTokensPreserveCompletionOrderDepthAndGeometry() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(session, "main", '(', BracketRole.OPEN, 0, 2, 1);
        accept(session, "main", '[', BracketRole.OPEN, 3, 1, 2);
        accept(session, "main", ']', BracketRole.CLOSE, 4, 1, 2);
        accept(session, "main", ')', BracketRole.CLOSE, 5, 2, 3);

        assertThat(output.pairs)
                .containsExactly(
                        new PairRecord(3, 1, 4, 1, 1, 2, 2), new PairRecord(0, 2, 5, 2, 0, 1, 3));
    }

    @Test
    public void unrelatedCloserDoesNotLoseAnOpener() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(session, "main", '(', BracketRole.OPEN, 0, 1, 0);
        accept(session, "main", '}', BracketRole.CLOSE, 1, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 2, 1, 0);

        assertThat(output.pairs).containsExactly(new PairRecord(0, 1, 2, 1, 0, 0, 0));
    }

    @Test
    public void matcherGroupsKeepIndependentStacksAndDepths() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(session, "host", '{', BracketRole.OPEN, 0, 1, 0);
        accept(session, "embedded", '(', BracketRole.OPEN, 1, 1, 0);
        accept(session, "host", '}', BracketRole.CLOSE, 2, 1, 0);
        accept(session, "embedded", ')', BracketRole.CLOSE, 3, 1, 0);

        assertThat(output.pairs).extracting(PairRecord::depth).containsExactly(0, 0);
        assertThat(output.pairs).extracting(PairRecord::openOffset).containsExactly(0, 1);
    }

    @Test
    public void malformedRecoveryDiscardsUnclosedInnerTokens() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(session, "main", '{', BracketRole.OPEN, 0, 1, 0);
        accept(session, "main", '(', BracketRole.OPEN, 1, 1, 0);
        accept(session, "main", '}', BracketRole.CLOSE, 2, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 3, 1, 0);

        assertThat(output.pairs).containsExactly(new PairRecord(0, 1, 2, 1, 0, 0, 0));
    }

    @Test
    public void structuralCloserRecoversPastRegularOpenersWithPriority() {
        RecordedPairs output = new RecordedPairs();
        PairingRules<Character> sharedCloser =
                rules((left, right) -> right == 'x' && (left == '{' || left == '('));
        PairingMachine<Character, String>.Session session = session(output, sharedCloser);

        accept(session, "main", '{', BracketRole.OPEN, StructuralRole.OPEN, 0, 1, 0);
        accept(session, "main", '(', BracketRole.OPEN, 1, 1, 0);
        accept(session, "main", 'x', BracketRole.CLOSE, StructuralRole.CLOSE, 2, 1, 0);

        assertThat(output.pairs).containsExactly(new PairRecord(0, 1, 2, 1, 0, 0, 0));
    }

    @Test
    public void occurrenceStructuralRolesSeparateOtherwiseIdenticalTokenTypes() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(session, "main", '(', BracketRole.OPEN, StructuralRole.OPEN, 0, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 1, 1, 0);
        assertThat(output.pairs).isEmpty();

        accept(session, "main", ')', BracketRole.CLOSE, StructuralRole.CLOSE, 2, 1, 0);
        assertThat(output.pairs).containsExactly(new PairRecord(0, 1, 2, 1, 0, 0, 0));

        accept(session, "main", '(', BracketRole.OPEN, 3, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, StructuralRole.CLOSE, 4, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 5, 1, 0);

        assertThat(output.pairs)
                .containsExactly(
                        new PairRecord(0, 1, 2, 1, 0, 0, 0), new PairRecord(3, 1, 5, 1, 0, 0, 0));
    }

    @Test
    public void regularPairCannotCrossAnUnmatchedStructuralOpener() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(session, "main", '(', BracketRole.OPEN, 0, 1, 0);
        accept(session, "main", '{', BracketRole.OPEN, StructuralRole.OPEN, 1, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 2, 1, 0);
        assertThat(output.pairs).isEmpty();

        accept(session, "main", '}', BracketRole.CLOSE, StructuralRole.CLOSE, 3, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 4, 1, 0);

        assertThat(output.pairs).hasSize(2);
        assertThat(output.pairs).extracting(PairRecord::openOffset).containsExactly(1, 0);
    }

    @Test
    public void regularPairCanBeContainedByAStructuralPair() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);

        accept(session, "main", '{', BracketRole.OPEN, StructuralRole.OPEN, 0, 1, 0);
        accept(session, "main", '(', BracketRole.OPEN, 1, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 2, 1, 0);
        accept(session, "main", '}', BracketRole.CLOSE, StructuralRole.CLOSE, 3, 1, 0);

        assertThat(output.pairs).hasSize(2);
        assertThat(output.pairs).extracting(PairRecord::openOffset).containsExactly(1, 0);
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
        assertThat(output.pairs).isEmpty();

        for (int index = 0; index < depth; index++) {
            accept(session, "main", ')', BracketRole.CLOSE, depth * 2 + index, 1, 0);
        }
        assertThat(output.pairs).hasSize(depth);
        assertThat(output.pairs.get(0).depth).isEqualTo(depth - 1);
        assertThat(output.pairs.get(depth - 1).depth).isZero();
    }

    @Test(timeout = 10_000)
    public void regularClosersDoNotScanBeyondAStructuralBoundary() {
        int depth = 50_000;
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = session(output);
        for (int offset = 0; offset < depth; offset++) {
            accept(session, "main", '(', BracketRole.OPEN, offset, 1, 0);
        }
        accept(session, "main", '{', BracketRole.OPEN, StructuralRole.OPEN, depth, 1, 0);

        for (int index = 0; index < depth; index++) {
            accept(session, "main", ')', BracketRole.CLOSE, depth + index + 1, 1, 0);
        }
        assertThat(output.pairs).isEmpty();
        accept(session, "main", '}', BracketRole.CLOSE, StructuralRole.CLOSE, depth * 2 + 1, 1, 0);
        for (int index = 0; index < depth; index++) {
            accept(session, "main", ')', BracketRole.CLOSE, depth * 2 + index + 2, 1, 0);
        }

        assertThat(output.pairs).hasSize(depth + 1);
        assertThat(output.pairs.get(0).openOffset).isEqualTo(depth);
        assertThat(output.pairs.get(1).depth).isEqualTo(depth - 1);
        assertThat(output.pairs.get(depth).depth).isZero();
    }

    @Test
    public void emptyGroupIgnoresUnmatchedCloserAndReusesResolvedRules() {
        RecordedPairs output = new RecordedPairs();
        int[] resolutions = {0};
        PairingMachine<Character, String> machine =
                new PairingMachine<>(
                        group -> {
                            resolutions[0]++;
                            return CHAR_RULES;
                        });
        PairingMachine<Character, String>.Session session =
                machine.newSession(output, NO_CANCELLATION, Integer.MAX_VALUE);

        accept(session, "main", '(', BracketRole.OPEN, 0, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 1, 1, 0);
        accept(session, "main", '}', BracketRole.CLOSE, 2, 1, 0);
        accept(session, "main", '[', BracketRole.OPEN, 3, 1, 0);
        accept(session, "main", ']', BracketRole.CLOSE, 4, 1, 0);

        assertThat(resolutions[0]).isEqualTo(1);
        assertThat(output.pairs).hasSize(2);
    }

    @Test
    public void sequentialPairsReleasePendingCapacityAndReuseGroupState() throws Exception {
        RecordedPairs output = new RecordedPairs();
        int[] resolutions = {0};
        PairingMachine<Character, String> machine =
                new PairingMachine<>(
                        group -> {
                            resolutions[0]++;
                            return CHAR_RULES;
                        });
        PairingMachine<Character, String>.Session session =
                machine.newSession(output, NO_CANCELLATION, 1);

        assertThat(
                        session.accept(
                                "main",
                                '(',
                                null,
                                false,
                                BracketRole.OPEN,
                                StructuralRole.NONE,
                                0,
                                1,
                                0))
                .isTrue();
        assertThat(
                        session.accept(
                                "main",
                                ')',
                                null,
                                false,
                                BracketRole.CLOSE,
                                StructuralRole.NONE,
                                1,
                                1,
                                0))
                .isTrue();
        Object lightweightState = groupState(session, "main");

        boolean accepted = true;
        for (int pair = 1; pair < 10_000; pair++) {
            int openOffset = pair * 2;
            accepted &=
                    session.accept(
                            "main",
                            '(',
                            null,
                            false,
                            BracketRole.OPEN,
                            StructuralRole.NONE,
                            openOffset,
                            1,
                            0);
            accepted &=
                    session.accept(
                            "main",
                            ')',
                            null,
                            false,
                            BracketRole.CLOSE,
                            StructuralRole.NONE,
                            openOffset + 1,
                            1,
                            0);
        }

        assertThat(accepted).isTrue();
        assertThat(groupState(session, "main")).isSameAs(lightweightState);
        assertThat(resolutions[0]).isEqualTo(1);
        assertThat(output.pairs).hasSize(10_000);
    }

    @Test
    public void oversizedEmptyGroupReleasesItsStateAndKeepsResolvedRules() throws Exception {
        RecordedPairs output = new RecordedPairs();
        int[] resolutions = {0};
        PairingMachine<Character, String> machine =
                new PairingMachine<>(
                        group -> {
                            resolutions[0]++;
                            return CHAR_RULES;
                        });
        PairingMachine<Character, String>.Session session =
                machine.newSession(output, NO_CANCELLATION, Integer.MAX_VALUE);
        int depth = 2_048;
        accept(session, "main", '{', BracketRole.OPEN, StructuralRole.OPEN, 0, 1, 0);
        for (int offset = 1; offset < depth; offset++) {
            accept(session, "main", '(', BracketRole.OPEN, offset, 1, 0);
        }
        Object oversizedState = groupState(session, "main");
        accept(session, "main", '}', BracketRole.CLOSE, StructuralRole.CLOSE, depth, 1, 0);

        Object lightweightState = groupState(session, "main");
        assertThat(lightweightState).isNotSameAs(oversizedState);
        accept(session, "main", '}', BracketRole.CLOSE, depth + 1, 1, 0);
        accept(session, "main", '[', BracketRole.OPEN, depth + 2, 1, 0);
        accept(session, "main", ']', BracketRole.CLOSE, depth + 3, 1, 0);

        assertThat(groupState(session, "main")).isSameAs(lightweightState);
        assertThat(resolutions[0]).isEqualTo(1);
        assertThat(output.pairs).hasSize(2);
    }

    @Test
    public void strictContextRequiresTheSameNormalizedValue() {
        RecordedPairs output = new RecordedPairs();
        PairingRules<String> tagRules =
                rules((left, right) -> left.equals("start") && right.equals("end"));
        PairingMachine<String, String> machine = new PairingMachine<>(ignored -> tagRules);
        PairingMachine<String, String>.Session session =
                machine.newSession(output, NO_CANCELLATION, Integer.MAX_VALUE);
        session.accept(
                "xml", "start", "section", true, BracketRole.OPEN, StructuralRole.NONE, 0, 1, 0);
        session.accept(
                "xml", "start", "item", true, BracketRole.OPEN, StructuralRole.NONE, 1, 1, 0);
        session.accept(
                "xml", "end", "other", true, BracketRole.CLOSE, StructuralRole.NONE, 2, 1, 0);
        session.accept(
                "xml", "end", "section", true, BracketRole.CLOSE, StructuralRole.NONE, 3, 1, 0);

        assertThat(output.pairs).containsExactly(new PairRecord(0, 1, 3, 1, 0, 0, 0));
    }

    @Test
    public void strictContextCanUseANullKey() {
        RecordedPairs output = new RecordedPairs();
        PairingRules<String> tagRules =
                rules((left, right) -> left.equals("start") && right.equals("end"));
        PairingMachine<String, String>.Session session =
                new PairingMachine<String, String>(ignored -> tagRules)
                        .newSession(output, NO_CANCELLATION, Integer.MAX_VALUE);

        session.accept("xml", "start", null, true, BracketRole.OPEN, StructuralRole.NONE, 0, 1, 0);
        session.accept("xml", "end", null, true, BracketRole.CLOSE, StructuralRole.NONE, 1, 1, 0);

        assertThat(output.pairs).hasSize(1);
    }

    @Test
    public void pureSymmetricTokensCloseBeforeTheyOpenAgain() {
        RecordedPairs output = new RecordedPairs();
        BracketRole symmetric = BracketRole.TOGGLE;
        PairingRules<Character> rules = rules((left, right) -> left == '|' && right == '|');
        PairingMachine<Character, String>.Session session = session(output, rules);

        accept(session, "main", '|', symmetric, 0, 1, 0);
        accept(session, "main", '|', symmetric, 2, 1, 0);
        accept(session, "main", '|', symmetric, 4, 1, 0);
        accept(session, "main", '|', symmetric, 6, 1, 0);

        assertThat(output.pairs)
                .containsExactly(
                        new PairRecord(0, 1, 2, 1, 0, 0, 0), new PairRecord(4, 1, 6, 1, 0, 0, 0));
    }

    @Test
    public void structuralToggleCanOpenAndCloseTheSameScope() {
        RecordedPairs output = new RecordedPairs();
        PairingRules<Character> rules = rules((left, right) -> left == '|' && right == '|');
        PairingMachine<Character, String>.Session session = session(output, rules);

        accept(session, "main", '|', BracketRole.TOGGLE, StructuralRole.OPEN_AND_CLOSE, 0, 1, 0);
        accept(session, "main", '|', BracketRole.TOGGLE, StructuralRole.OPEN_AND_CLOSE, 1, 1, 0);

        assertThat(output.pairs).hasSize(1);
        assertThat(output.pairs.get(0).openOffset).isZero();
    }

    @Test
    public void pendingOpenCapacityRejectsTheNextOpenerBeforeAllocation() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String> machine = new PairingMachine<>(ignored -> CHAR_RULES);
        PairingMachine<Character, String>.Session session =
                machine.newSession(output, NO_CANCELLATION, 2);

        assertThat(
                        session.accept(
                                "main",
                                '(',
                                null,
                                false,
                                BracketRole.OPEN,
                                StructuralRole.NONE,
                                0,
                                1,
                                0))
                .isTrue();
        assertThat(
                        session.accept(
                                "main",
                                '[',
                                null,
                                false,
                                BracketRole.OPEN,
                                StructuralRole.NONE,
                                1,
                                1,
                                0))
                .isTrue();
        assertThat(
                        session.accept(
                                "main",
                                '{',
                                null,
                                false,
                                BracketRole.OPEN,
                                StructuralRole.NONE,
                                2,
                                1,
                                0))
                .isFalse();
        assertThat(output.pairs)
                .as("no completed-prefix result escapes after capacity exhaustion")
                .isEmpty();
    }

    @Test
    public void deepMalformedRecoveryChecksCancellation() {
        RecordedPairs output = new RecordedPairs();
        int[] checks = {0};
        CancellationProbe cancellation =
                () -> {
                    if (++checks[0] == 3) {
                        throw new TestCancellation();
                    }
                };
        PairingRules<String> tagRules =
                rules((left, right) -> left.equals("start") && right.equals("end"));
        PairingMachine<String, String> machine = new PairingMachine<>(ignored -> tagRules);
        PairingMachine<String, String>.Session session =
                machine.newSession(output, cancellation, Integer.MAX_VALUE);
        session.accept(
                "xml", "start", "root", true, BracketRole.OPEN, StructuralRole.NONE, 0, 1, 0);
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
                    0);
        }

        assertThatExceptionOfType(TestCancellation.class)
                .isThrownBy(
                        () ->
                                session.accept(
                                        "xml",
                                        "end",
                                        "root",
                                        true,
                                        BracketRole.CLOSE,
                                        StructuralRole.NONE,
                                        20_000,
                                        1,
                                        0));
        assertThat(checks[0]).isEqualTo(3);
    }

    @Test
    public void resumedTopMatchKeepsItsQueryImmutableAndEmitsOnlyAfterItsAnswer() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = explicitSession(output);
        assertThat(begin(session, '(', BracketRole.OPEN, 0))
                .isEqualTo(PairingMachine.Step.ACCEPTED);
        assertThat(begin(session, ')', BracketRole.CLOSE, 1))
                .isEqualTo(PairingMachine.Step.NEEDS_RULE);
        PairingMachine.RuleRequest<Character, String> query = session.ruleRequest();
        assertThat(query).isEqualTo(new PairingMachine.RuleRequest<>("main", '(', ')'));
        assertThat(output.pairs).isEmpty();

        assertThat(session.resume(true)).isEqualTo(PairingMachine.Step.ACCEPTED);
        assertThat(query).isEqualTo(new PairingMachine.RuleRequest<>("main", '(', ')'));
        assertThat(output.pairs).containsExactly(new PairRecord(0, 1, 1, 1, 0, 0, 0));
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(session::ruleRequest);
    }

    @Test
    public void candidateAndRecoveryComparisonsResumeWithoutReplayingEarlierQueries() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = explicitSession(output);
        begin(session, '(', BracketRole.OPEN, 0);
        begin(session, '[', BracketRole.OPEN, 1);
        assertThat(begin(session, ')', BracketRole.CLOSE, 2))
                .isEqualTo(PairingMachine.Step.NEEDS_RULE);
        assertThat(session.ruleRequest().openToken()).isEqualTo('[');

        assertThat(session.resume(false)).isEqualTo(PairingMachine.Step.NEEDS_RULE);
        // Character keys '(' and '[' occupy different HashMap buckets, with '(' first.
        assertThat(session.ruleRequest().openToken()).isEqualTo('(');
        assertThat(session.resume(true)).isEqualTo(PairingMachine.Step.NEEDS_RULE);
        assertThat(session.ruleRequest().openToken()).isEqualTo('[');
        assertThat(session.resume(false)).isEqualTo(PairingMachine.Step.NEEDS_RULE);
        assertThat(session.ruleRequest().openToken()).isEqualTo('(');
        assertThat(session.resume(true)).isEqualTo(PairingMachine.Step.ACCEPTED);
        assertThat(output.pairs).containsExactly(new PairRecord(0, 1, 2, 1, 0, 0, 0));
        assertThat(begin(session, ']', BracketRole.CLOSE, 3))
                .isEqualTo(PairingMachine.Step.ACCEPTED);
    }

    @Test
    public void unrelatedCloserFinishesItsCandidateScanWithoutDiscardingAnOpen() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = explicitSession(output);
        begin(session, '(', BracketRole.OPEN, 0);
        begin(session, '[', BracketRole.OPEN, 1);
        PairingMachine.Step step = begin(session, '}', BracketRole.CLOSE, 2);
        List<Character> compared = new ArrayList<>();
        while (step == PairingMachine.Step.NEEDS_RULE) {
            compared.add(session.ruleRequest().openToken());
            step = session.resume(false);
        }
        assertThat(compared).containsExactly('[', '(', '[');
        assertThat(step).isEqualTo(PairingMachine.Step.ACCEPTED);
        assertThat(begin(session, ']', BracketRole.CLOSE, 3))
                .isEqualTo(PairingMachine.Step.NEEDS_RULE);
        assertThat(session.resume(true)).isEqualTo(PairingMachine.Step.ACCEPTED);
        assertThat(begin(session, ')', BracketRole.CLOSE, 4))
                .isEqualTo(PairingMachine.Step.NEEDS_RULE);
        assertThat(session.resume(true)).isEqualTo(PairingMachine.Step.ACCEPTED);
        assertThat(output.pairs).extracting(PairRecord::openOffset).containsExactly(1, 0);
    }

    @Test
    public void pendingComparisonRejectsAnotherTokenAndStrayAnswersAreRejected() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session = explicitSession(output);
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> session.resume(true));
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(session::ruleRequest);
        begin(session, '(', BracketRole.OPEN, 0);
        begin(session, ')', BracketRole.CLOSE, 1);
        PairingMachine.RuleRequest<Character, String> query = session.ruleRequest();
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> begin(session, '[', BracketRole.OPEN, 2));
        assertThat(session.ruleRequest()).isEqualTo(query);
        assertThat(session.resume(true)).isEqualTo(PairingMachine.Step.ACCEPTED);
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> session.resume(true));
        assertThat(output.pairs).hasSize(1);
    }

    @Test
    public void explicitPendingCapacityAndToggleDoNotNeedSynchronousRules() {
        RecordedPairs output = new RecordedPairs();
        PairingMachine<Character, String>.Session session =
                new PairingMachine<Character, String>().newSession(output, NO_CANCELLATION, 1);
        assertThat(begin(session, '|', BracketRole.TOGGLE, 0))
                .isEqualTo(PairingMachine.Step.ACCEPTED);
        assertThat(begin(session, '(', BracketRole.OPEN, 1))
                .isEqualTo(PairingMachine.Step.PENDING_CAPACITY);
        assertThat(begin(session, '|', BracketRole.TOGGLE, 2))
                .isEqualTo(PairingMachine.Step.NEEDS_RULE);
        assertThat(session.resume(true)).isEqualTo(PairingMachine.Step.ACCEPTED);
        assertThat(begin(session, '|', BracketRole.TOGGLE, 3))
                .isEqualTo(PairingMachine.Step.ACCEPTED);
        assertThat(output.pairs).containsExactly(new PairRecord(0, 1, 2, 1, 0, 0, 0));
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(
                        () ->
                                session.accept(
                                        "main",
                                        '|',
                                        null,
                                        false,
                                        BracketRole.TOGGLE,
                                        StructuralRole.NONE,
                                        4,
                                        1,
                                        0));
    }

    @Test
    public void synchronousAndResumedPathsPreservePairsAndLazyRuleCallOrder() {
        List<TokenInput> inputs =
                List.of(
                        new TokenInput(
                                "main", '{', null, false, BracketRole.OPEN, StructuralRole.OPEN),
                        new TokenInput(
                                "main", '(', null, false, BracketRole.OPEN, StructuralRole.NONE),
                        new TokenInput(
                                "main", ']', null, false, BracketRole.CLOSE, StructuralRole.NONE),
                        new TokenInput(
                                "main", 'x', null, false, BracketRole.CLOSE, StructuralRole.CLOSE),
                        new TokenInput(
                                "xml", '(', "root", true, BracketRole.OPEN, StructuralRole.NONE),
                        new TokenInput(
                                "xml", '(', "child", true, BracketRole.OPEN, StructuralRole.NONE),
                        new TokenInput(
                                "xml", ')', "root", true, BracketRole.CLOSE, StructuralRole.NONE),
                        new TokenInput(
                                "main",
                                '|',
                                null,
                                false,
                                BracketRole.TOGGLE,
                                StructuralRole.OPEN_AND_CLOSE),
                        new TokenInput(
                                "main",
                                '|',
                                null,
                                false,
                                BracketRole.TOGGLE,
                                StructuralRole.OPEN_AND_CLOSE),
                        new TokenInput(
                                "main", '[', null, false, BracketRole.OPEN, StructuralRole.NONE),
                        new TokenInput(
                                "main", ']', null, false, BracketRole.CLOSE, StructuralRole.NONE));
        RecordedPairs synchronous = new RecordedPairs();
        RecordedPairs resumed = new RecordedPairs();
        List<PairingMachine.RuleRequest<Character, String>> synchronousCalls = new ArrayList<>();
        List<PairingMachine.RuleRequest<Character, String>> resumedCalls = new ArrayList<>();
        int[] resolutions = {0};
        PairingMachine<Character, String>.Session synchronousSession =
                new PairingMachine<Character, String>(
                                group -> {
                                    resolutions[0]++;
                                    return rules(
                                            (open, close) -> {
                                                synchronousCalls.add(
                                                        new PairingMachine.RuleRequest<>(
                                                                group, open, close));
                                                return examplePair(open, close);
                                            });
                                })
                        .newSession(synchronous, NO_CANCELLATION, 20);
        PairingMachine<Character, String>.Session resumedSession = explicitSession(resumed);
        for (int offset = 0; offset < inputs.size(); offset++) {
            TokenInput input = inputs.get(offset);
            boolean accepted =
                    synchronousSession.accept(
                            input.group,
                            input.token,
                            input.context,
                            input.strictContext,
                            input.role,
                            input.structuralRole,
                            offset,
                            1,
                            offset / 3);
            PairingMachine.Step step =
                    resumedSession.begin(
                            input.group,
                            input.token,
                            input.context,
                            input.strictContext,
                            input.role,
                            input.structuralRole,
                            offset,
                            1,
                            offset / 3);
            while (step == PairingMachine.Step.NEEDS_RULE) {
                PairingMachine.RuleRequest<Character, String> query = resumedSession.ruleRequest();
                resumedCalls.add(query);
                step = resumedSession.resume(examplePair(query.openToken(), query.closeToken()));
            }
            assertThat(step == PairingMachine.Step.ACCEPTED).isEqualTo(accepted);
        }
        assertThat(resumed.pairs).containsExactlyElementsOf(synchronous.pairs);
        assertThat(resumedCalls).containsExactlyElementsOf(synchronousCalls);
        assertThat(resolutions[0]).isEqualTo(2);
        assertThat(resumed.pairs).extracting(PairRecord::openOffset).containsExactly(0, 4, 7, 9);
    }

    @Test
    public void failedSynchronousTopAnswerContinuesWithoutRepeatingThatComparison() {
        RecordedPairs output = new RecordedPairs();
        List<PairingMachine.RuleRequest<Character, String>> calls = new ArrayList<>();
        PairingMachine<Character, String>.Session session =
                new PairingMachine<Character, String>(
                                group ->
                                        rules(
                                                (open, close) -> {
                                                    calls.add(
                                                            new PairingMachine.RuleRequest<>(
                                                                    group, open, close));
                                                    return calls.size() > 1;
                                                }))
                        .newSession(output, NO_CANCELLATION, 10);
        accept(session, "main", '(', BracketRole.OPEN, 0, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 1, 1, 0);

        assertThat(calls)
                .containsExactly(
                        new PairingMachine.RuleRequest<>("main", '(', ')'),
                        new PairingMachine.RuleRequest<>("main", '(', ')'),
                        new PairingMachine.RuleRequest<>("main", '(', ')'));
        assertThat(output.pairs).containsExactly(new PairRecord(0, 1, 1, 1, 0, 0, 0));
    }

    @Test
    public void synchronousTopCallbackCannotStartAnotherToken() {
        RecordedPairs output = new RecordedPairs();
        AtomicReference<PairingMachine<Character, String>.Session> owner = new AtomicReference<>();
        PairingMachine<Character, String>.Session session =
                new PairingMachine<Character, String>(
                                group ->
                                        rules(
                                                (open, close) -> {
                                                    assertThatExceptionOfType(
                                                                    IllegalStateException.class)
                                                            .isThrownBy(
                                                                    () ->
                                                                            begin(
                                                                                    owner.get(),
                                                                                    '[',
                                                                                    BracketRole
                                                                                            .OPEN,
                                                                                    2));
                                                    return true;
                                                }))
                        .newSession(output, NO_CANCELLATION, 10);
        owner.set(session);
        accept(session, "main", '(', BracketRole.OPEN, 0, 1, 0);
        accept(session, "main", ')', BracketRole.CLOSE, 1, 1, 0);
        assertThat(output.pairs).containsExactly(new PairRecord(0, 1, 1, 1, 0, 0, 0));
        assertThat(begin(session, '[', BracketRole.OPEN, 2))
                .isEqualTo(PairingMachine.Step.ACCEPTED);
    }

    @Test
    public void cancellationWhileRecoveryAnswerIsPendingLeavesThatComparisonUnapplied() {
        RecordedPairs output = new RecordedPairs();
        boolean[] canceled = {false};
        CancellationProbe cancellation =
                () -> {
                    if (canceled[0]) throw new TestCancellation();
                };
        PairingMachine<Character, String>.Session session =
                new PairingMachine<Character, String>().newSession(output, cancellation, 10);
        begin(session, '(', BracketRole.OPEN, 0);
        begin(session, '[', BracketRole.OPEN, 1);
        begin(session, ')', BracketRole.CLOSE, 2);
        session.resume(false); // Top '[' did not match; inspect candidate '('.
        session.resume(true); // Candidate exists; '[' was removed and awaits its recovery answer.
        PairingMachine.RuleRequest<Character, String> recoveryQuery = session.ruleRequest();
        assertThat(recoveryQuery.openToken()).isEqualTo('[');
        canceled[0] = true;
        assertThatExceptionOfType(TestCancellation.class).isThrownBy(() -> session.resume(false));
        assertThat(output.pairs).isEmpty();
        assertThat(session.ruleRequest()).isEqualTo(recoveryQuery);
        // A one-shot probe failure has not applied the answer or rewound recovery. Production
        // abandons canceled sessions; continuing here directly verifies that no scan is replayed.
        canceled[0] = false;
        assertThat(session.resume(false)).isEqualTo(PairingMachine.Step.NEEDS_RULE);
        assertThat(session.ruleRequest().openToken()).isEqualTo('(');
        assertThat(session.resume(true)).isEqualTo(PairingMachine.Step.ACCEPTED);
        assertThat(output.pairs).containsExactly(new PairRecord(0, 1, 2, 1, 0, 0, 0));
    }

    @Test
    public void longResumedRecoveryKeepsCancellationCheckpointsAfterRepeatedSuspensions() {
        RecordedPairs output = new RecordedPairs();
        int[] checks = {0};
        CancellationProbe cancellation =
                () -> {
                    if (++checks[0] == 261) throw new TestCancellation();
                };
        PairingMachine<String, String>.Session session =
                new PairingMachine<String, String>().newSession(output, cancellation, 1_000);
        session.begin("xml", "start", "root", true, BracketRole.OPEN, StructuralRole.NONE, 0, 1, 0);
        for (int offset = 1; offset <= 600; offset++) {
            session.begin(
                    "xml",
                    "start",
                    "dangling-" + offset,
                    true,
                    BracketRole.OPEN,
                    StructuralRole.NONE,
                    offset,
                    1,
                    0);
        }
        assertThat(
                        session.begin(
                                "xml",
                                "end",
                                "root",
                                true,
                                BracketRole.CLOSE,
                                StructuralRole.NONE,
                                601,
                                1,
                                0))
                .isEqualTo(PairingMachine.Step.NEEDS_RULE);
        int[] suppliedAnswers = {0};
        assertThatExceptionOfType(TestCancellation.class)
                .isThrownBy(
                        () -> {
                            PairingMachine.Step step;
                            do {
                                suppliedAnswers[0]++;
                                step = session.resume(true);
                            } while (step == PairingMachine.Step.NEEDS_RULE);
                        });
        // 258 seam checks, one candidate checkpoint, then recovery checkpoints at 0 and 256.
        assertThat(suppliedAnswers[0]).isEqualTo(258);
        assertThat(checks[0]).isEqualTo(261);
        assertThat(output.pairs).isEmpty();
    }

    private static boolean examplePair(char open, char close) {
        return CHAR_RULES.isPair(open, close)
                || (close == 'x' && (open == '{' || open == '('))
                || (open == '|' && close == '|');
    }

    private static PairingMachine<Character, String>.Session explicitSession(RecordedPairs output) {
        return new PairingMachine<Character, String>()
                .newSession(output, NO_CANCELLATION, Integer.MAX_VALUE);
    }

    private static PairingMachine.Step begin(
            PairingMachine<Character, String>.Session session,
            char token,
            BracketRole role,
            int offset) {
        return session.begin("main", token, null, false, role, StructuralRole.NONE, offset, 1, 0);
    }

    private record TokenInput(
            String group,
            char token,
            String context,
            boolean strictContext,
            BracketRole role,
            StructuralRole structuralRole) {}

    private static PairingMachine<Character, String>.Session session(RecordedPairs output) {
        return session(output, CHAR_RULES);
    }

    @SuppressWarnings("unchecked")
    private static Object groupState(
            PairingMachine<Character, String>.Session session, String group) throws Exception {
        var statesField = session.getClass().getDeclaredField("states");
        statesField.setAccessible(true);
        return ((Map<String, Object>) statesField.get(session)).get(group);
    }

    private static PairingMachine<Character, String>.Session session(
            RecordedPairs output, PairingRules<Character> rules) {
        PairingMachine<Character, String> machine = new PairingMachine<>(ignored -> rules);
        return machine.newSession(output, NO_CANCELLATION, Integer.MAX_VALUE);
    }

    private static void accept(
            PairingMachine<Character, String>.Session session,
            String group,
            char token,
            BracketRole role,
            int offset,
            int tokenLength,
            int line) {
        accept(session, group, token, role, StructuralRole.NONE, offset, tokenLength, line);
    }

    private static void accept(
            PairingMachine<Character, String>.Session session,
            String group,
            char token,
            BracketRole role,
            StructuralRole structuralRole,
            int offset,
            int tokenLength,
            int line) {
        session.accept(group, token, null, false, role, structuralRole, offset, tokenLength, line);
    }

    private static <T> PairingRules<T> rules(BiPredicate<T, T> pair) {
        return new PairingRules<>() {
            @Override
            public boolean isPair(T openToken, T closeToken) {
                return pair.test(openToken, closeToken);
            }
        };
    }

    private static final PairingRules<Character> CHAR_RULES =
            rules(
                    (left, right) ->
                            switch (left) {
                                case '(' -> right == ')';
                                case '[' -> right == ']';
                                case '{' -> right == '}';
                                default -> false;
                            });

    private static final CancellationProbe NO_CANCELLATION = () -> {};

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
                int closeLine) {
            pairs.add(
                    new PairRecord(
                            openOffset,
                            openTokenLength,
                            closeOffset,
                            closeTokenLength,
                            depth,
                            openLine,
                            closeLine));
        }
    }

    private record PairRecord(
            int openOffset,
            int openLength,
            int closeOffset,
            int closeLength,
            int depth,
            int openLine,
            int closeLine) {}

    private static final class TestCancellation extends RuntimeException {}
}
