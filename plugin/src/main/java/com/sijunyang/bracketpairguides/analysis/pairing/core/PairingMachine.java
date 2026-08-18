package com.sijunyang.bracketpairguides.analysis.pairing.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Platform-neutral, single-pass bracket pairing state machine.
 *
 * <p>A session owns all mutable state and must stay on one thread. Inputs and
 * completed-pair output are supplied explicitly; no document, clock, executor,
 * or global service is read by this package.</p>
 */
public final class PairingMachine<T, G> {
    private final Function<? super G, ? extends PairingRules<T>> rulesForGroup;

    /**
     * Creates a stateless machine configuration.
     *
     * <p>Each session resolves a group's rules once and keeps that result for
     * the rest of the scan, so a group's pairing semantics cannot change
     * halfway through a session.</p>
     */
    public PairingMachine(
            Function<? super G, ? extends PairingRules<T>> rulesForGroup
    ) {
        this.rulesForGroup = Objects.requireNonNull(rulesForGroup, "rulesForGroup");
    }

    public Session newSession(
            PairSink sink,
            CancellationProbe cancellation,
            int maximumPendingOpens
    ) {
        if (maximumPendingOpens <= 0) {
            throw new IllegalArgumentException("Pending-open capacity must be positive");
        }
        return new Session(
                Objects.requireNonNull(sink, "sink"),
                Objects.requireNonNull(cancellation, "cancellation"),
                maximumPendingOpens
        );
    }

    public final class Session {
        private final PairSink sink;
        private final CancellationProbe cancellation;
        private final int maximumPendingOpens;
        private final Map<G, GroupState<T>> states = new HashMap<>();
        private final Map<G, PairingRules<T>> rulesByGroup = new HashMap<>();
        private int pendingOpenCount;

        private Session(
                PairSink sink,
                CancellationProbe cancellation,
                int maximumPendingOpens
        ) {
            this.sink = sink;
            this.cancellation = cancellation;
            this.maximumPendingOpens = maximumPendingOpens;
        }

        /**
         * Accepts one token already classified by the host-language adapter.
         *
         * @return false before an opener would cross the pending-open capacity
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
                int line
        ) {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(structuralRole, "structuralRole");
            if (tokenLength <= 0) {
                return true;
            }

            return switch (role) {
                case OPEN -> open(
                        group,
                        token,
                        context,
                        strictContext,
                        structuralRole.opens(),
                        offset,
                        tokenLength,
                        line
                );
                case CLOSE -> {
                    OpenToken<T> match = close(
                            group,
                            token,
                            context,
                            strictContext,
                            structuralRole.closes()
                    );
                    if (match != null) {
                        emit(match, offset, tokenLength, line);
                    }
                    yield true;
                }
                case TOGGLE -> {
                    OpenToken<T> match = close(
                            group,
                            token,
                            context,
                            strictContext,
                            structuralRole.closes()
                    );
                    if (match != null) {
                        emit(match, offset, tokenLength, line);
                        yield true;
                    }
                    yield open(
                            group,
                            token,
                            context,
                            strictContext,
                            structuralRole.opens(),
                            offset,
                            tokenLength,
                            line
                    );
                }
            };
        }

        private boolean open(
                G group,
                T token,
                String context,
                boolean strictContext,
                boolean structural,
                int offset,
                int tokenLength,
                int line
        ) {
            if (pendingOpenCount == maximumPendingOpens) {
                return false;
            }
            GroupState<T> state = states.computeIfAbsent(
                    group,
                    ignored -> new GroupState<>(rulesFor(group))
            );
            OpenToken<T> open = new OpenToken<>(
                    token,
                    context,
                    strictContext,
                    structural,
                    offset,
                    tokenLength,
                    line,
                    state.stack.size()
            );
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

        private OpenToken<T> close(
                G group,
                T token,
                String context,
                boolean strictContext,
                boolean structural
        ) {
            GroupState<T> state = states.get(group);
            if (state == null || state.stack.isEmpty()) {
                return null;
            }
            PairingRules<T> rules = state.rules;

            OpenToken<T> top = state.stack.getLast();
            boolean topMatches = matches(top, token, context, strictContext, rules);
            OpenToken<T> match;

            if (top.structural == structural && topMatches) {
                match = removeLast(state);
            } else {
                Counts<T> candidates = structural
                        ? state.structuralCounts
                        : state.regularScopes.getLast();
                if (!hasCandidate(
                        candidates,
                        token,
                        context,
                        strictContext,
                        rules
                )) {
                    match = null;
                } else {
                    match = recover(
                            state,
                            token,
                            context,
                            strictContext,
                            structural,
                            rules
                    );
                }
            }

            releaseOversizedEmptyState(group, state);
            return match;
        }

        /**
         * Keeps the allocation benefit for ordinary sequential pairs without
         * retaining a pathological stack's backing arrays for the rest of the
         * document scan. Counts and structural scopes cannot grow beyond the
         * same group's peak stack size.
         */
        private void releaseOversizedEmptyState(G group, GroupState<T> state) {
            if (state.stack.isEmpty() &&
                    state.peakStackSize > MAXIMUM_RETAINED_EMPTY_GROUP_DEPTH) {
                states.put(group, new GroupState<>(state.rules));
            }
        }

        private PairingRules<T> rulesFor(G group) {
            PairingRules<T> cached = rulesByGroup.get(group);
            if (cached != null) {
                return cached;
            }
            PairingRules<T> resolved = Objects.requireNonNull(
                    rulesForGroup.apply(group),
                    "rulesForGroup result"
            );
            rulesByGroup.put(group, resolved);
            return resolved;
        }

        private boolean hasCandidate(
                Counts<T> counts,
                T closeToken,
                String closeContext,
                boolean strictContext,
                PairingRules<T> rules
        ) {
            if (counts.tokenCounts == null) {
                return false;
            }
            int visitedTypes = 0;
            for (Map.Entry<T, Integer> entry : counts.tokenCounts.entrySet()) {
                if ((visitedTypes++ & CANCELLATION_MASK) == 0) {
                    cancellation.check();
                }
                T openToken = entry.getKey();
                if (entry.getValue() == 0 || !rules.isPair(openToken, closeToken)) {
                    continue;
                }
                if (!strictContext || contextCount(counts, openToken, closeContext) > 0) {
                    return true;
                }
            }
            return false;
        }

        private int contextCount(Counts<T> counts, T token, String context) {
            if (counts.contextCounts == null) {
                return 0;
            }
            return counts.contextCounts.getOrDefault(new ContextKey<>(token, context), 0);
        }

        private OpenToken<T> recover(
                GroupState<T> state,
                T closeToken,
                String closeContext,
                boolean strictContext,
                boolean structural,
                PairingRules<T> rules
        ) {
            int discarded = 0;
            while (!state.stack.isEmpty()) {
                if (!structural && state.stack.getLast().structural) {
                    return null;
                }
                if ((discarded++ & CANCELLATION_MASK) == 0) {
                    cancellation.check();
                }
                OpenToken<T> open = removeLast(state);
                if (matches(open, closeToken, closeContext, strictContext, rules) &&
                        open.structural == structural) {
                    return open;
                }
            }
            return null;
        }

        private boolean matches(
                OpenToken<T> open,
                T closeToken,
                String closeContext,
                boolean strictContext,
                PairingRules<T> rules
        ) {
            return rules.isPair(open.token, closeToken) &&
                    (!strictContext ||
                            (open.strictContext && Objects.equals(open.context, closeContext)));
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
                    closeLine
            );
        }
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

    private record ContextKey<T>(T token, String context) {
    }

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
                int depth
        ) {
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
