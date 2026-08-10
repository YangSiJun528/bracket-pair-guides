package com.sijunyang.bracketpairguides.analysis.pairing.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PairTableTest {
    private static final CancellationProbe NO_CANCELLATION = () -> { };

    @Test
    public void draftFreezesPrimitivePairGeometry() {
        PairTable.Draft draft = PairTable.draft();
        draft.accept(2, 1, 10, 2, 3, 4, 8);

        PairTable table = draft.freeze();

        assertEquals(1, table.size());
        assertEquals(2, table.openOffsetAt(0));
        assertEquals(1, table.openTokenLengthAt(0));
        assertEquals(10, table.closeOffsetAt(0));
        assertEquals(2, table.closeTokenLengthAt(0));
        assertEquals(3, table.depthAt(0));
        assertEquals(4, table.openLineAt(0));
        assertEquals(8, table.closeLineAt(0));
        assertTrue(table.hasWellFormedTokenRangeAt(0, 12));
        assertFalse(table.hasWellFormedTokenRangeAt(0, 11));
    }

    @Test
    public void draftIsSingleUseAndEmptyTablesAreShared() {
        PairTable.Draft draft = PairTable.draft();
        PairTable first = draft.freeze();

        assertSame(PairTable.empty(), first);
        assertThrows(IllegalStateException.class, draft::freeze);
        assertThrows(
                IllegalStateException.class,
                () -> draft.accept(0, 1, 1, 1, 0, 0, 0)
        );
    }

    @Test
    public void contentIdentityRequiresEveryGeometryColumn() {
        int[] geometry = {2, 1, 10, 2, 3, 4, 8};
        PairTable baseline = table(geometry);
        PairTable equivalent = table(geometry.clone());

        assertNotSame(baseline, equivalent);
        assertEquals(baseline.contentHash(), equivalent.contentHash());
        assertTrue(baseline.hasSameContent(equivalent, NO_CANCELLATION));
        assertTrue(equivalent.hasSameContent(baseline, NO_CANCELLATION));

        for (int column = 0; column < geometry.length; column++) {
            int[] changed = geometry.clone();
            changed[column]++;
            assertFalse(
                    "Column " + column + " must participate in content identity",
                    baseline.hasSameContent(table(changed), NO_CANCELLATION)
            );
        }
        assertFalse(baseline.hasSameContent(null, NO_CANCELLATION));
    }

    @Test
    public void contentIdentityChecksCancellationDuringLargeExactComparisons() {
        PairTable.Draft first = PairTable.draft();
        PairTable.Draft second = PairTable.draft();
        for (int index = 0; index < 300; index++) {
            first.accept(index * 2, 1, index * 2 + 1, 1, index, 0, 0);
            second.accept(index * 2, 1, index * 2 + 1, 1, index, 0, 0);
        }
        int[] checks = {0};

        assertThrows(
                TestCancellation.class,
                () -> first.freeze().hasSameContent(second.freeze(), () -> {
                    if (++checks[0] == 2) {
                        throw new TestCancellation();
                    }
                })
        );
    }

    private static PairTable table(int... geometry) {
        PairTable.Draft draft = PairTable.draft();
        draft.accept(
                geometry[0],
                geometry[1],
                geometry[2],
                geometry[3],
                geometry[4],
                geometry[5],
                geometry[6]
        );
        return draft.freeze();
    }

    private static final class TestCancellation extends RuntimeException {
    }
}
