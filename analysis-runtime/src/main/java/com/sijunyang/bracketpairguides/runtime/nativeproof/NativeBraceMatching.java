/*
 * Copyright 2000-2023 JetBrains s.r.o. and contributors.
 * SPDX-License-Identifier: Apache-2.0
 * Adapted from BraceMatchingUtil.MatchBraceContext, IntelliJ 241.19416.15,
 * platform/lang-impl/src/com/intellij/codeInsight/highlighting/BraceMatchingUtil.java:134-234.
 * https://github.com/JetBrains/intellij-community/blob/idea/241.19416.15/platform/lang-impl/src/com/intellij/codeInsight/highlighting/BraceMatchingUtil.java
 * See licenses/intellij-native-brace-context-Apache-2.0.txt.
 * Owns per-call stacks and cancellation, avoiding the platform's global matchBrace monitor.
 */
package com.sijunyang.bracketpairguides.runtime.nativeproof;

import com.intellij.codeInsight.highlighting.BraceMatcher;
import com.intellij.codeInsight.highlighting.BraceMatchingUtil;
import com.intellij.codeInsight.highlighting.XmlAwareBraceMatcher;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.util.Comparing;
import com.intellij.psi.tree.IElementType;
import java.util.ArrayList;
import java.util.Objects;

/** Host continuations retain no iterator or live text between separately validated read actions. */
public final class NativeBraceMatching {
    private NativeBraceMatching() {}

    public enum Step {
        MORE,
        MATCHED,
        UNMATCHED
    }

    /** Cooperative work admission; one extension call can exceed the deadline. */
    public static final class WorkBudget {
        private static final Runnable NO_CANCELLATION = () -> {};
        private final long deadlineNs;
        private final Runnable checkCanceled;
        private final boolean cooperative;
        private int remaining;
        private int consumed;

        public WorkBudget(int maximumOperations, long deadlineNs, Runnable checkCanceled) {
            if (maximumOperations <= 0)
                throw new IllegalArgumentException("Positive work budget required");
            remaining = maximumOperations;
            this.deadlineNs = deadlineNs;
            this.checkCanceled = Objects.requireNonNull(checkCanceled, "checkCanceled");
            cooperative = true;
        }

        private WorkBudget() {
            remaining = Integer.MAX_VALUE;
            deadlineNs = 0;
            checkCanceled = NO_CANCELLATION;
            cooperative = false;
        }

        public int consumed() {
            return consumed;
        }

        public boolean exhausted() {
            return remaining == 0;
        }

        private boolean take() {
            if (remaining == 0) return false;
            if (cooperative && (consumed & 31) == 0) {
                checkCanceled.run();
                if (deadlineNs != 0 && System.nanoTime() >= deadlineNs) {
                    remaining = 0;
                    return false;
                }
            }
            remaining--;
            consumed++;
            return true;
        }

        /** Synchronous compatibility wrappers do not add deadlines or cancellation checkpoints. */
        public static WorkBudget unlimited() {
            return new WorkBudget();
        }
    }

    public static Session matching(
            CharSequence text,
            FileType fileType,
            HighlighterIterator iterator,
            boolean forward,
            Runnable checkCanceled) {
        checkCanceled.run();
        return new Session(text, fileType, iterator, forward);
    }

    public static Session structuralLeft(FileType fileType, HighlighterIterator iterator) {
        return new Session(fileType, iterator);
    }

    public static boolean match(
            CharSequence text,
            FileType fileType,
            HighlighterIterator iterator,
            boolean forward,
            Runnable checkCanceled) {
        Session session = matching(text, fileType, iterator, forward, checkCanceled);
        Step step;
        do {
            step = session.advance(text, iterator, WorkBudget.unlimited(), checkCanceled);
        } while (step == Step.MORE);
        return step == Step.MATCHED;
    }

    /**
     * Same implementation as bounded structural search, useful to callers already holding read
     * access.
     */
    public static boolean findStructuralLeft(
            CharSequence text,
            FileType fileType,
            HighlighterIterator iterator,
            Runnable checkCanceled) {
        Session session = structuralLeft(fileType, iterator);
        Step step;
        do {
            step = session.advance(text, iterator, WorkBudget.unlimited(), checkCanceled);
        } while (step == Step.MORE);
        return step == Step.MATCHED;
    }

    public static final class Session {
        private static final int MOVE = 0,
                GROUP = 1,
                TAG = 2,
                OPEN_ROLE = 3,
                CLOSE_ROLE = 4,
                OPPOSITE = 5,
                MEMBERSHIP = 6,
                RECOVERY_COMPARE = 7,
                RECOVERY_POP = 8,
                RESTORE_COMPARE = 9,
                FINAL_COMPARE = 10;
        private static final int STRUCTURAL = 20,
                STRUCTURAL_RIGHT = 21,
                STRUCTURAL_PUSH = 22,
                STRUCTURAL_LEFT = 23,
                STRUCTURAL_GROUP = 24,
                STRUCTURAL_STRICT = 25,
                STRUCTURAL_CASE = 26,
                STRUCTURAL_TAG = 27,
                STRUCTURAL_PAIR = 28,
                RETREAT = 29;
        private final FileType fileType;
        private final BraceMatcher matcher;
        private final boolean forward;
        private final int group;
        private final String initialTag;
        private boolean strict;
        private boolean caseSensitive;
        private final ArrayList<IElementType> braces = new ArrayList<>();
        private final ArrayList<String> tags = new ArrayList<>();
        private int phase;
        private int work;
        private int membershipIndex;
        private int structuralGroup;
        private IElementType token;
        private IElementType top;
        private IElementType opposite;
        private String tag;
        private String topTag;
        private Step result = Step.MORE;
        private boolean failed;

        private Session(
                CharSequence text,
                FileType fileType,
                HighlighterIterator iterator,
                boolean forward) {
            this.fileType = fileType;
            this.forward = forward;
            // Preserve native constructor lookup/callback order, including repeated lookups.
            BraceMatcher strictMatcher = BraceMatchingUtil.getBraceMatcher(fileType, iterator);
            int strictGroup = tokenGroup(iterator.getTokenType());
            strict =
                    strictMatcher instanceof XmlAwareBraceMatcher xml
                            && xml.isStrictTagMatching(fileType, strictGroup);
            matcher = BraceMatchingUtil.getBraceMatcher(fileType, iterator);
            IElementType initialToken = iterator.getTokenType();
            group = tokenGroup(initialToken);
            initialTag = tagName(text, iterator);
            caseSensitive =
                    matcher instanceof XmlAwareBraceMatcher xml
                            && xml.areTagsCaseSensitive(fileType, group);
            braces.add(initialToken);
            if (strict) tags.add(initialTag);
            phase = MOVE;
        }

        // Adapted from BraceMatchingUtil.findStructuralLeftBrace (241 and263,263lines251–293).
        // Its asymmetric tag-stack pushes/pops and separate right/left role queries are
        // intentional.
        private Session(FileType fileType, HighlighterIterator iterator) {
            this.fileType = fileType;
            matcher = BraceMatchingUtil.getBraceMatcher(fileType, iterator);
            forward = false;
            group = 0;
            initialTag = null;
            phase = STRUCTURAL;
        }

        /** Failure is terminal: a callback may already have mutated stack/cursor state. */
        public Step advance(
                CharSequence text,
                HighlighterIterator iterator,
                WorkBudget budget,
                Runnable checkCanceled) {
            if (failed) throw new IllegalStateException("Failed native continuation cannot resume");
            try {
                return advanceOwned(text, iterator, budget, checkCanceled);
            } catch (RuntimeException | Error problem) {
                failed = true;
                throw problem;
            }
        }

        private Step advanceOwned(
                CharSequence text,
                HighlighterIterator iterator,
                WorkBudget budget,
                Runnable checkCanceled) {
            if (result != Step.MORE) return result;
            while (budget.take()) {
                switch (phase) {
                    case MOVE -> {
                        probe(checkCanceled);
                        if (forward) iterator.advance();
                        else iterator.retreat();
                        if (iterator.atEnd()) return finish(false);
                        token = iterator.getTokenType();
                        phase = GROUP;
                    }
                    case GROUP -> phase = tokenGroup(token) == group ? TAG : MOVE;
                    case TAG -> {
                        tag = tagName(text, iterator);
                        phase =
                                !strict && !Comparing.equal(initialTag, tag, caseSensitive)
                                        ? MOVE
                                        : OPEN_ROLE;
                    }
                    case OPEN_ROLE -> {
                        if (isBrace(!forward, text, iterator)) {
                            braces.add(token);
                            if (strict) tags.add(tag);
                            phase = MOVE;
                        } else phase = CLOSE_ROLE;
                    }
                    case CLOSE_ROLE -> {
                        if (!isBrace(forward, text, iterator)) phase = MOVE;
                        else {
                            top = braces.remove(braces.size() - 1);
                            topTag = strict ? tags.remove(tags.size() - 1) : null;
                            phase = strict ? FINAL_COMPARE : OPPOSITE;
                        }
                    }
                    case OPPOSITE -> {
                        opposite = matcher.getOppositeBraceTokenType(token);
                        membershipIndex = 0;
                        phase = MEMBERSHIP;
                    }
                    case MEMBERSHIP -> {
                        if (membershipIndex == braces.size()) {
                            phase =
                                    initialTag == null || !initialTag.equals(tag)
                                            ? RESTORE_COMPARE
                                            : FINAL_COMPARE;
                        } else {
                            probe(checkCanceled);
                            IElementType entry = braces.get(membershipIndex++);
                            if (opposite == null ? entry == null : opposite.equals(entry))
                                phase = RECOVERY_COMPARE;
                        }
                    }
                    case RECOVERY_COMPARE -> {
                        // Query first even with an empty stack; do not replay a callback after
                        // yielding.
                        boolean paired = BraceMatchingUtil.isPairBraces(top, token, fileType);
                        phase = paired || braces.isEmpty() ? FINAL_COMPARE : RECOVERY_POP;
                    }
                    case RECOVERY_POP -> {
                        probe(checkCanceled);
                        top = braces.remove(braces.size() - 1);
                        phase = RECOVERY_COMPARE;
                    }
                    case RESTORE_COMPARE -> {
                        if (!BraceMatchingUtil.isPairBraces(top, token, fileType)) {
                            braces.add(top);
                            phase = MOVE;
                        } else phase = FINAL_COMPARE;
                    }
                    case FINAL_COMPARE -> {
                        if (!BraceMatchingUtil.isPairBraces(top, token, fileType)
                                || strict && !Comparing.equal(topTag, tag, caseSensitive))
                            return finish(false);
                        if (braces.isEmpty()) return finish(true);
                        phase = MOVE;
                    }
                    case STRUCTURAL -> {
                        if (iterator.atEnd()) return finish(false);
                        phase =
                                BraceMatchingUtil.isStructuralBraceToken(fileType, iterator, text)
                                        ? STRUCTURAL_RIGHT
                                        : RETREAT;
                    }
                    case STRUCTURAL_RIGHT -> {
                        if (BraceMatchingUtil.isRBraceToken(iterator, text, fileType)) {
                            braces.add(iterator.getTokenType());
                            phase = STRUCTURAL_PUSH;
                        } else phase = STRUCTURAL_LEFT;
                    }
                    case STRUCTURAL_PUSH -> {
                        tags.add(tagName(text, iterator));
                        phase = STRUCTURAL_LEFT;
                    }
                    case STRUCTURAL_LEFT -> {
                        if (!BraceMatchingUtil.isLBraceToken(iterator, text, fileType))
                            phase = RETREAT;
                        else if (braces.isEmpty()) return finish(true);
                        else phase = STRUCTURAL_GROUP;
                    }
                    case STRUCTURAL_GROUP -> {
                        structuralGroup = matcher.getBraceTokenGroupId(iterator.getTokenType());
                        top = braces.remove(braces.size() - 1);
                        token = iterator.getTokenType();
                        phase = STRUCTURAL_STRICT;
                    }
                    case STRUCTURAL_STRICT -> {
                        strict =
                                matcher instanceof XmlAwareBraceMatcher xml
                                        && xml.isStrictTagMatching(fileType, structuralGroup);
                        phase = STRUCTURAL_CASE;
                    }
                    case STRUCTURAL_CASE -> {
                        caseSensitive =
                                matcher instanceof XmlAwareBraceMatcher xml
                                        && xml.areTagsCaseSensitive(fileType, structuralGroup);
                        topTag = null;
                        tag = null;
                        phase = strict ? STRUCTURAL_TAG : STRUCTURAL_PAIR;
                    }
                    case STRUCTURAL_TAG -> {
                        topTag = tags.remove(tags.size() - 1);
                        tag = tagName(text, iterator);
                        phase = STRUCTURAL_PAIR;
                    }
                    case STRUCTURAL_PAIR -> {
                        if (!BraceMatchingUtil.isPairBraces(top, token, fileType)
                                || strict && !Comparing.equal(topTag, tag, caseSensitive))
                            return finish(false);
                        phase = RETREAT;
                    }
                    case RETREAT -> {
                        probe(checkCanceled);
                        iterator.retreat();
                        phase = STRUCTURAL;
                    }
                    default -> throw new IllegalStateException("Unknown native phase");
                }
            }
            return Step.MORE;
        }

        private Step finish(boolean matched) {
            return result = matched ? Step.MATCHED : Step.UNMATCHED;
        }

        private void probe(Runnable checkCanceled) {
            if ((work++ & 0xFF) == 0) checkCanceled.run();
        }

        private int tokenGroup(IElementType type) {
            return type == null
                    ? -1
                    : BraceMatchingUtil.getBraceMatcher(fileType, type).getBraceTokenGroupId(type);
        }

        private String tagName(CharSequence text, HighlighterIterator iterator) {
            return matcher instanceof XmlAwareBraceMatcher xml
                    ? xml.getTagName(text, iterator)
                    : null;
        }

        private boolean isBrace(boolean right, CharSequence text, HighlighterIterator iterator) {
            return right
                    ? BraceMatchingUtil.isRBraceToken(iterator, text, fileType)
                    : BraceMatchingUtil.isLBraceToken(iterator, text, fileType);
        }
    }
}
