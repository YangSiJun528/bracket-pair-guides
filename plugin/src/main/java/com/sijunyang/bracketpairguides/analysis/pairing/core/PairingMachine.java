package com.sijunyang.bracketpairguides.analysis.pairing.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Platform-neutral, single-pass bracket pairing state machine.
 *
 * <p>A session owns all mutable state and requires exclusive sequential access. A suspended owner
 * may resume on another thread with a happens-before handoff. Inputs and completed-pair output are
 * supplied explicitly; no document, clock, executor, or global service is read by this package.
 */
public final class PairingMachine<T, G> {
    private final Function<? super G, ? extends PairingRules<T>> rulesForGroup;

    /** Creates a machine whose sessions request compatibility answers explicitly. */
    public PairingMachine() {
        this.rulesForGroup = null;
    }

    /** Result of beginning a token or supplying one requested compatibility answer. */
    public enum Step {
        NEEDS_RULE,
        ACCEPTED,
        PENDING_CAPACITY
    }

    /** Immutable compatibility query; no platform dependency or callback is retained. */
    public record RuleRequest<T, G>(G group, T openToken, T closeToken) {}

    /**
     * Creates a stateless machine configuration.
     *
     * <p>Each session resolves a group's rules once and keeps that result for the rest of the scan,
     * so a group's pairing semantics cannot change halfway through a session.
     */
    public PairingMachine(Function<? super G, ? extends PairingRules<T>> rulesForGroup) {
        this.rulesForGroup = Objects.requireNonNull(rulesForGroup, "rulesForGroup");
    }

    public Session newSession(
            PairSink sink, CancellationProbe cancellation, int maximumPendingOpens) {
        if (maximumPendingOpens <= 0) {
            throw new IllegalArgumentException("Pending-open capacity must be positive");
        }
        return new Session(
                Objects.requireNonNull(sink, "sink"),
                Objects.requireNonNull(cancellation, "cancellation"),
                maximumPendingOpens);
    }

    public final class Session {
        private final PairSink sink;
        private final CancellationProbe cancellation;
        private final int maximumPendingOpens;
        private final Map<G, GroupState<T>> states = new HashMap<>();
        private final Map<G, PairingRules<T>> rulesByGroup = new HashMap<>();
        private int pendingOpenCount;

        // One token's continuation. Synchronous callers reuse these fields and allocate no
        // operation or RuleRequest per token/comparison. No other token can enter mid-close.
        private G currentGroup;
        private T currentToken;
        private String currentContext;
        private boolean currentStrictContext;
        private BracketRole currentRole;
        private StructuralRole currentStructuralRole;
        private int currentOffset;
        private int currentTokenLength;
        private int currentLine;
        private GroupState<T> currentState;
        private OpenToken<T> comparedOpen;
        private T requestedOpenToken;
        private Counts<T> candidateCounts;
        private Iterator<Map.Entry<T, Integer>> candidateIterator;
        private int visitedTypes;
        private int discarded;
        private Comparison comparison;
        private boolean awaitingRule;
        private RuleRequest<T, G> lastRuleRequest;
        private boolean comparingSynchronously;

        private Session(PairSink sink, CancellationProbe cancellation, int maximumPendingOpens) {
            this.sink = sink;
            this.cancellation = cancellation;
            this.maximumPendingOpens = maximumPendingOpens;
        }

        /**
         * Accepts one classified token synchronously through the same resumable implementation.
         * Each group's configured rules are still resolved once, on its first accepted opener.
         */
        public boolean accept(
                G group,
                T token,
                String context,
                boolean strictContext,
                BracketRole role,
                StructuralRole structuralRole,
                int offset,
                int tokenLength,
                int line) {
            if (rulesForGroup == null) {
                throw new IllegalStateException("Synchronous acceptance requires configured rules");
            }
            Step step =
                    begin(
                            group,
                            token,
                            context,
                            strictContext,
                            role,
                            structuralRole,
                            offset,
                            tokenLength,
                            line,
                            true);
            while (step == Step.NEEDS_RULE) {
                boolean answer = currentState.rules.isPair(requestedOpenToken, currentToken);
                // Keep the original synchronous cancellation checkpoints. Public resume also
                // checks at the asynchronous seam before applying an externally supplied answer.
                step = applyAnswer(answer);
            }
            return step == Step.ACCEPTED;
        }

        /**
         * Begins one classified token. NEEDS_RULE requires ruleRequest()/resume() until complete.
         * Starting another token while an answer is pending is rejected before any mutation.
         * Cancellation or a callback failure aborts the analysis; discard this session in that
         * case.
         */
        public Step begin(
                G group,
                T token,
                String context,
                boolean strictContext,
                BracketRole role,
                StructuralRole structuralRole,
                int offset,
                int tokenLength,
                int line) {
            return begin(
                    group,
                    token,
                    context,
                    strictContext,
                    role,
                    structuralRole,
                    offset,
                    tokenLength,
                    line,
                    false);
        }

        private Step begin(
                G group,
                T token,
                String context,
                boolean strictContext,
                BracketRole role,
                StructuralRole structuralRole,
                int offset,
                int tokenLength,
                int line,
                boolean synchronousRules) {
            if (awaitingRule || comparingSynchronously) {
                throw new IllegalStateException("A compatibility answer is pending");
            }
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(structuralRole, "structuralRole");
            if (tokenLength <= 0) {
                return Step.ACCEPTED;
            }
            if (role == BracketRole.OPEN) {
                return open(
                                group,
                                token,
                                context,
                                strictContext,
                                structuralRole.opens(),
                                offset,
                                tokenLength,
                                line)
                        ? Step.ACCEPTED
                        : Step.PENDING_CAPACITY;
            }

            GroupState<T> state = states.get(group);
            OpenToken<T> top =
                    state == null || state.stack.isEmpty() ? null : state.stack.getLast();
            if (top == null && role == BracketRole.CLOSE) {
                if (state != null) {
                    releaseOversizedEmptyState(group, state);
                }
                return Step.ACCEPTED;
            }
            boolean topAnswer = false;
            if (synchronousRules && top != null) {
                // The common matched-top close needs no retained continuation. A failed answer
                // enters the same TOP transition below, without invoking the callback again.
                comparingSynchronously = true;
                try {
                    topAnswer = state.rules.isPair(top.token, token);
                } finally {
                    comparingSynchronously = false;
                }
                boolean topMatches = matchesContext(top, topAnswer, strictContext, context);
                if (top.structural == structuralRole.closes() && topMatches) {
                    return finishMatch(
                            group, state, removeLast(state), offset, tokenLength, line, false);
                }
            }

            currentGroup = group;
            currentToken = token;
            currentContext = context;
            currentStrictContext = strictContext;
            currentRole = role;
            currentStructuralRole = structuralRole;
            currentOffset = offset;
            currentTokenLength = tokenLength;
            currentLine = line;
            currentState = state;
            if (top == null) {
                return finishClose(null);
            }
            comparedOpen = top;
            comparison = Comparison.TOP;
            return synchronousRules ? applyAnswer(topAnswer) : requestRule(top.token);
        }

        /**
         * Returns the outstanding query as an immutable value, only when NEEDS_RULE was returned.
         */
        public RuleRequest<T, G> ruleRequest() {
            requirePendingRule();
            // One immutable repeated query is common in nesting and XML. Retain only the last
            // identities, never token occurrences or contexts, regardless of document length.
            if (lastRuleRequest == null
                    || lastRuleRequest.group() != currentGroup
                    || lastRuleRequest.openToken() != requestedOpenToken
                    || lastRuleRequest.closeToken() != currentToken) {
                lastRuleRequest = new RuleRequest<>(currentGroup, requestedOpenToken, currentToken);
            }
            return lastRuleRequest;
        }

        /**
         * Applies exactly one answer and continues after that comparison, without replaying scans.
         */
        public Step resume(boolean isPair) {
            requirePendingRule();
            cancellation.check();
            return applyAnswer(isPair);
        }

        private void requirePendingRule() {
            if (!awaitingRule) {
                throw new IllegalStateException("No compatibility answer is pending");
            }
        }

        private Step requestRule(T openToken) {
            requestedOpenToken = openToken;
            awaitingRule = true;
            return Step.NEEDS_RULE;
        }

        private Step applyAnswer(boolean isPair) {
            awaitingRule = false;
            boolean structural = currentStructuralRole.closes();
            return switch (comparison) {
                case TOP -> {
                    boolean topMatches =
                            matchesContext(
                                    comparedOpen, isPair, currentStrictContext, currentContext);
                    if (comparedOpen.structural == structural && topMatches) {
                        yield finishClose(removeLast(currentState));
                    }
                    candidateCounts =
                            structural
                                    ? currentState.structuralCounts
                                    : currentState.regularScopes.getLast();
                    comparedOpen = null;
                    if (candidateCounts.tokenCounts == null) {
                        yield finishClose(null);
                    }
                    candidateIterator = candidateCounts.tokenCounts.entrySet().iterator();
                    visitedTypes = 0;
                    comparison = Comparison.CANDIDATE;
                    yield nextCandidate();
                }
                case CANDIDATE -> {
                    if (isPair
                            && (!currentStrictContext
                                    || contextCount(
                                                    candidateCounts,
                                                    requestedOpenToken,
                                                    currentContext)
                                            > 0)) {
                        candidateIterator = null;
                        candidateCounts = null;
                        comparison = Comparison.RECOVERY;
                        discarded = 0;
                        yield nextRecovery();
                    }
                    yield nextCandidate();
                }
                case RECOVERY -> {
                    if (matchesContext(comparedOpen, isPair, currentStrictContext, currentContext)
                            && comparedOpen.structural == structural) {
                        yield finishClose(comparedOpen);
                    }
                    comparedOpen = null;
                    yield nextRecovery();
                }
            };
        }

        private Step nextCandidate() {
            while (candidateIterator.hasNext()) {
                Map.Entry<T, Integer> entry = candidateIterator.next();
                if ((visitedTypes++ & CANCELLATION_MASK) == 0) {
                    cancellation.check();
                }
                if (entry.getValue() != 0) {
                    return requestRule(entry.getKey());
                }
            }
            return finishClose(null);
        }

        private Step nextRecovery() {
            if (currentState.stack.isEmpty()
                    || (!currentStructuralRole.closes()
                            && currentState.stack.getLast().structural)) {
                return finishClose(null);
            }
            if ((discarded++ & CANCELLATION_MASK) == 0) {
                cancellation.check();
            }
            // Preserve the original removal-before-compare ordering. The continuation owns this
            // removed opener until its answer arrives, so suspension never repeats the removal.
            comparedOpen = removeLast(currentState);
            return requestRule(comparedOpen.token);
        }

        private boolean matchesContext(
                OpenToken<T> open, boolean isPair, boolean strictContext, String context) {
            return isPair
                    && (!strictContext
                            || (open.strictContext && Objects.equals(open.context, context)));
        }

        private Step finishMatch(
                G group,
                GroupState<T> state,
                OpenToken<T> match,
                int offset,
                int length,
                int line,
                boolean hasContinuation) {
            releaseOversizedEmptyState(group, state);
            if (hasContinuation) {
                clearContinuation();
            }
            emit(match, offset, length, line);
            return Step.ACCEPTED;
        }

        private Step finishClose(OpenToken<T> match) {
            if (match != null) {
                return finishMatch(
                        currentGroup,
                        currentState,
                        match,
                        currentOffset,
                        currentTokenLength,
                        currentLine,
                        true);
            }
            if (currentState != null) {
                releaseOversizedEmptyState(currentGroup, currentState);
            }
            Step result = Step.ACCEPTED;
            int offset = currentOffset;
            int length = currentTokenLength;
            int line = currentLine;
            if (currentRole == BracketRole.TOGGLE) {
                result =
                        open(
                                        currentGroup,
                                        currentToken,
                                        currentContext,
                                        currentStrictContext,
                                        currentStructuralRole.opens(),
                                        offset,
                                        length,
                                        line)
                                ? Step.ACCEPTED
                                : Step.PENDING_CAPACITY;
            }
            clearContinuation();
            return result;
        }

        private void clearContinuation() {
            currentGroup = null;
            currentToken = null;
            currentContext = null;
            currentRole = null;
            currentStructuralRole = null;
            currentState = null;
            comparedOpen = null;
            requestedOpenToken = null;
            candidateCounts = null;
            candidateIterator = null;
            comparison = null;
            awaitingRule = false;
        }

        private boolean open(
                G group,
                T token,
                String context,
                boolean strictContext,
                boolean structural,
                int offset,
                int tokenLength,
                int line) {
            if (pendingOpenCount == maximumPendingOpens) {
                return false;
            }
            GroupState<T> state =
                    states.computeIfAbsent(
                            group,
                            ignored ->
                                    new GroupState<>(
                                            rulesForGroup == null ? null : rulesFor(group)));
            OpenToken<T> open =
                    new OpenToken<>(
                            token,
                            context,
                            strictContext,
                            structural,
                            offset,
                            tokenLength,
                            line,
                            state.stack.size());
            state.stack.addLast(open);
            state.peakStackSize = Math.max(state.peakStackSize, state.stack.size());
            pendingOpenCount++;
            if (structural) {
                increment(state.structuralCounts, open);
                state.regularScopes.addLast(new Counts<>());
            } else {
                increment(state.regularScopes.getLast(), open);
            }
            return true;
        }

        /**
         * Keeps the allocation benefit for ordinary sequential pairs without retaining a
         * pathological stack's backing arrays for the rest of the document scan. Counts and
         * structural scopes cannot grow beyond the same group's peak stack size.
         */
        private void releaseOversizedEmptyState(G group, GroupState<T> state) {
            if (state.stack.isEmpty() && state.peakStackSize > MAXIMUM_RETAINED_EMPTY_GROUP_DEPTH) {
                states.put(group, new GroupState<>(state.rules));
            }
        }

        private PairingRules<T> rulesFor(G group) {
            PairingRules<T> cached = rulesByGroup.get(group);
            if (cached != null) {
                return cached;
            }
            PairingRules<T> resolved =
                    Objects.requireNonNull(
                            Objects.requireNonNull(rulesForGroup, "Configured rules are required")
                                    .apply(group),
                            "rulesForGroup result");
            rulesByGroup.put(group, resolved);
            return resolved;
        }

        private int contextCount(Counts<T> counts, T token, String context) {
            if (counts.contextCounts == null) {
                return 0;
            }
            return counts.contextCounts.getOrDefault(new ContextKey<>(token, context), 0);
        }

        private OpenToken<T> removeLast(GroupState<T> state) {
            OpenToken<T> open = state.stack.removeLast();
            pendingOpenCount--;
            if (open.structural) {
                decrement(state.structuralCounts, open);
                state.regularScopes.removeLast();
            } else {
                decrement(state.regularScopes.getLast(), open);
            }
            return open;
        }

        private void increment(Counts<T> counts, OpenToken<T> open) {
            if (counts.tokenCounts == null) {
                counts.tokenCounts = new HashMap<>();
            }
            increment(counts.tokenCounts, open.token);
            if (open.strictContext) {
                if (counts.contextCounts == null) {
                    counts.contextCounts = new HashMap<>();
                }
                increment(counts.contextCounts, new ContextKey<>(open.token, open.context));
            }
        }

        private void decrement(Counts<T> counts, OpenToken<T> open) {
            if (counts.tokenCounts != null) {
                decrement(counts.tokenCounts, open.token);
            }
            if (open.strictContext && counts.contextCounts != null) {
                decrement(counts.contextCounts, new ContextKey<>(open.token, open.context));
            }
        }

        private <K> void increment(Map<K, Integer> counts, K key) {
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }

        private <K> void decrement(Map<K, Integer> counts, K key) {
            Integer current = counts.get(key);
            if (current == null) {
                return;
            }
            if (current == 1) {
                counts.remove(key);
            } else {
                counts.put(key, current - 1);
            }
        }

        private void emit(OpenToken<T> open, int closeOffset, int closeLength, int closeLine) {
            sink.accept(
                    open.offset,
                    open.tokenLength,
                    closeOffset,
                    closeLength,
                    open.depth,
                    open.line,
                    closeLine);
        }
    }

    private enum Comparison {
        TOP,
        CANDIDATE,
        RECOVERY
    }

    private static final class GroupState<T> {
        private final ArrayDeque<OpenToken<T>> stack = new ArrayDeque<>();
        private final Counts<T> structuralCounts = new Counts<>();
        private final ArrayDeque<Counts<T>> regularScopes = new ArrayDeque<>();
        private final PairingRules<T> rules;
        private int peakStackSize;

        private GroupState(PairingRules<T> rules) {
            this.rules = rules;
            regularScopes.addLast(new Counts<>());
        }
    }

    private static final class Counts<T> {
        private Map<T, Integer> tokenCounts;
        private Map<ContextKey<T>, Integer> contextCounts;
    }

    private record ContextKey<T>(T token, String context) {}

    private static final int MAXIMUM_RETAINED_EMPTY_GROUP_DEPTH = 1_024;

    private static final class OpenToken<T> {
        private final T token;
        private final String context;
        private final boolean strictContext;
        private final boolean structural;
        private final int offset;
        private final int tokenLength;
        private final int line;
        private final int depth;

        private OpenToken(
                T token,
                String context,
                boolean strictContext,
                boolean structural,
                int offset,
                int tokenLength,
                int line,
                int depth) {
            this.token = token;
            this.context = context;
            this.strictContext = strictContext;
            this.structural = structural;
            this.offset = offset;
            this.tokenLength = tokenLength;
            this.line = line;
            this.depth = depth;
        }
    }

    private static final int CANCELLATION_MASK = 0xFF;
}
