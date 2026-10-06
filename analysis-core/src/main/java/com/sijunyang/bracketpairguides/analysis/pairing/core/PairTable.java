package com.sijunyang.bracketpairguides.analysis.pairing.core;

import java.util.Arrays;

/** Immutable primitive table containing recognized bracket-pair geometry. */
public final class PairTable {
    private static final int CANCELLATION_MASK = 0xFF;
    private static final PairTable EMPTY =
            new PairTable(
                    new int[0],
                    new int[0],
                    new int[0],
                    new int[0],
                    new int[0],
                    new int[0],
                    new int[0],
                    0,
                    1);

    private final int[] openOffsets;
    private final int[] openTokenLengths;
    private final int[] closeOffsets;
    private final int[] closeTokenLengths;
    private final int[] depths;
    private final int[] openLines;
    private final int[] closeLines;
    private final int size;
    private final int contentHash;

    private PairTable(
            int[] openOffsets,
            int[] openTokenLengths,
            int[] closeOffsets,
            int[] closeTokenLengths,
            int[] depths,
            int[] openLines,
            int[] closeLines,
            int size,
            int contentHash) {
        this.openOffsets = openOffsets;
        this.openTokenLengths = openTokenLengths;
        this.closeOffsets = closeOffsets;
        this.closeTokenLengths = closeTokenLengths;
        this.depths = depths;
        this.openLines = openLines;
        this.closeLines = closeLines;
        this.size = size;
        this.contentHash = contentHash;
    }

    public static PairTable empty() {
        return EMPTY;
    }

    public static Draft draft() {
        return new Draft();
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns whether both tables contain exactly the same pair geometry. Backing-array identity
     * and unused capacity are intentionally ignored.
     */
    public boolean hasSameContent(PairTable other, CancellationProbe cancellation) {
        if (cancellation == null) {
            throw new NullPointerException("cancellation");
        }
        if (this == other) {
            return true;
        }
        if (other == null || size != other.size || contentHash != other.contentHash) {
            return false;
        }
        for (int index = 0; index < size; index++) {
            if ((index & CANCELLATION_MASK) == 0) {
                cancellation.check();
            }
            if (openOffsets[index] != other.openOffsets[index]
                    || openTokenLengths[index] != other.openTokenLengths[index]
                    || closeOffsets[index] != other.closeOffsets[index]
                    || closeTokenLengths[index] != other.closeTokenLengths[index]
                    || depths[index] != other.depths[index]
                    || openLines[index] != other.openLines[index]
                    || closeLines[index] != other.closeLines[index]) {
                return false;
            }
        }
        return true;
    }

    /** Fast rejection key; callers must still use exact content comparison. */
    public int contentHash() {
        return contentHash;
    }

    public int openOffsetAt(int index) {
        checkIndex(index);
        return openOffsets[index];
    }

    public int openTokenLengthAt(int index) {
        checkIndex(index);
        return openTokenLengths[index];
    }

    public int closeOffsetAt(int index) {
        checkIndex(index);
        return closeOffsets[index];
    }

    public int closeTokenLengthAt(int index) {
        checkIndex(index);
        return closeTokenLengths[index];
    }

    public int depthAt(int index) {
        checkIndex(index);
        return depths[index];
    }

    public int openLineAt(int index) {
        checkIndex(index);
        return openLines[index];
    }

    public int closeLineAt(int index) {
        checkIndex(index);
        return closeLines[index];
    }

    public boolean hasWellFormedTokenRangeAt(int index, int maximumEndOffset) {
        checkIndex(index);
        if (maximumEndOffset < 0
                || openOffsets[index] < 0
                || closeOffsets[index] < 0
                || openTokenLengths[index] <= 0
                || closeTokenLengths[index] <= 0) {
            return false;
        }

        long openEnd = (long) openOffsets[index] + openTokenLengths[index];
        long closeEnd = (long) closeOffsets[index] + closeTokenLengths[index];
        return openEnd <= closeOffsets[index] && closeEnd <= maximumEndOffset;
    }

    public boolean hasWellFormedTokenRangeAt(int index) {
        return hasWellFormedTokenRangeAt(index, Integer.MAX_VALUE);
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(
                    "Pair index " + index + " is outside 0 until " + size);
        }
    }

    /** Single-use mutable pair table whose arrays become owned by the frozen table. */
    public static final class Draft implements PairSink {
        private static final int INITIAL_CAPACITY = 16;

        private int[] openOffsets = new int[INITIAL_CAPACITY];
        private int[] openTokenLengths = new int[INITIAL_CAPACITY];
        private int[] closeOffsets = new int[INITIAL_CAPACITY];
        private int[] closeTokenLengths = new int[INITIAL_CAPACITY];
        private int[] depths = new int[INITIAL_CAPACITY];
        private int[] openLines = new int[INITIAL_CAPACITY];
        private int[] closeLines = new int[INITIAL_CAPACITY];
        private int size;
        private int contentHash = 1;
        private boolean frozen;

        @Override
        public void accept(
                int openOffset,
                int openTokenLength,
                int closeOffset,
                int closeTokenLength,
                int depth,
                int openLine,
                int closeLine) {
            ensureMutable();
            ensureCapacity(size + 1);
            openOffsets[size] = openOffset;
            openTokenLengths[size] = openTokenLength;
            closeOffsets[size] = closeOffset;
            closeTokenLengths[size] = closeTokenLength;
            depths[size] = depth;
            openLines[size] = openLine;
            closeLines[size] = closeLine;
            contentHash = 31 * contentHash + openOffset;
            contentHash = 31 * contentHash + openTokenLength;
            contentHash = 31 * contentHash + closeOffset;
            contentHash = 31 * contentHash + closeTokenLength;
            contentHash = 31 * contentHash + depth;
            contentHash = 31 * contentHash + openLine;
            contentHash = 31 * contentHash + closeLine;
            size++;
        }

        public PairTable freeze() {
            ensureMutable();
            frozen = true;
            if (size == 0) {
                release();
                return EMPTY;
            }
            PairTable table =
                    new PairTable(
                            openOffsets,
                            openTokenLengths,
                            closeOffsets,
                            closeTokenLengths,
                            depths,
                            openLines,
                            closeLines,
                            size,
                            contentHash);
            release();
            return table;
        }

        private void ensureCapacity(int required) {
            if (required < 0) {
                throw new OutOfMemoryError("Pair table exceeds the JVM array limit");
            }
            if (required <= openOffsets.length) {
                return;
            }
            long grown = (long) openOffsets.length + (openOffsets.length >> 1);
            int next = (int) Math.min(Math.max(grown, required), Integer.MAX_VALUE);
            openOffsets = Arrays.copyOf(openOffsets, next);
            openTokenLengths = Arrays.copyOf(openTokenLengths, next);
            closeOffsets = Arrays.copyOf(closeOffsets, next);
            closeTokenLengths = Arrays.copyOf(closeTokenLengths, next);
            depths = Arrays.copyOf(depths, next);
            openLines = Arrays.copyOf(openLines, next);
            closeLines = Arrays.copyOf(closeLines, next);
        }

        private void ensureMutable() {
            if (frozen) {
                throw new IllegalStateException("PairTable.Draft is single-use");
            }
        }

        private void release() {
            openOffsets = null;
            openTokenLengths = null;
            closeOffsets = null;
            closeTokenLengths = null;
            depths = null;
            openLines = null;
            closeLines = null;
        }
    }
}
