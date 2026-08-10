package com.sijunyang.bracketpairguides.analysis.pairing.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PairTableTest {
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
}
