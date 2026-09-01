package com.sijunyang.bracketpairguides.analysis.pairing.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.Test;

public class PairTableTest {
    private static final CancellationProbe NO_CANCELLATION = () -> {};

    @Test
    public void draftFreezesPrimitivePairGeometry() {
        PairTable.Draft draft = PairTable.draft();
        draft.accept(2, 1, 10, 2, 3, 4, 8);

        PairTable table = draft.freeze();

        assertThat(table.size()).isEqualTo(1);
        assertThat(table.openOffsetAt(0)).isEqualTo(2);
        assertThat(table.openTokenLengthAt(0)).isEqualTo(1);
        assertThat(table.closeOffsetAt(0)).isEqualTo(10);
        assertThat(table.closeTokenLengthAt(0)).isEqualTo(2);
        assertThat(table.depthAt(0)).isEqualTo(3);
        assertThat(table.openLineAt(0)).isEqualTo(4);
        assertThat(table.closeLineAt(0)).isEqualTo(8);
        assertThat(table.hasWellFormedTokenRangeAt(0, 12)).isTrue();
        assertThat(table.hasWellFormedTokenRangeAt(0, 11)).isFalse();
    }

    @Test
    public void draftIsSingleUseAndEmptyTablesAreShared() {
        PairTable.Draft draft = PairTable.draft();
        PairTable first = draft.freeze();

        assertThat(first).isSameAs(PairTable.empty());
        assertThatIllegalStateException().isThrownBy(draft::freeze);
        assertThatIllegalStateException().isThrownBy(() -> draft.accept(0, 1, 1, 1, 0, 0, 0));
    }

    @Test
    public void contentIdentityRequiresEveryGeometryColumn() {
        int[] geometry = {2, 1, 10, 2, 3, 4, 8};
        PairTable baseline = table(geometry);
        PairTable equivalent = table(geometry.clone());

        assertThat(equivalent).isNotSameAs(baseline);
        assertThat(equivalent.contentHash()).isEqualTo(baseline.contentHash());
        assertThat(baseline.hasSameContent(equivalent, NO_CANCELLATION)).isTrue();
        assertThat(equivalent.hasSameContent(baseline, NO_CANCELLATION)).isTrue();

        for (int column = 0; column < geometry.length; column++) {
            int[] changed = geometry.clone();
            changed[column]++;
            assertThat(baseline.hasSameContent(table(changed), NO_CANCELLATION))
                    .as("geometry column %s participates in content identity", column)
                    .isFalse();
        }
        assertThat(baseline.hasSameContent(null, NO_CANCELLATION)).isFalse();
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

        assertThatExceptionOfType(TestCancellation.class)
                .isThrownBy(
                        () ->
                                first.freeze()
                                        .hasSameContent(
                                                second.freeze(),
                                                () -> {
                                                    if (++checks[0] == 2) {
                                                        throw new TestCancellation();
                                                    }
                                                }));
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
                geometry[6]);
        return draft.freeze();
    }

    private static final class TestCancellation extends RuntimeException {}
}
