package com.mirco_grid.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The apportionment on its own.
 *
 * <p>The one property that matters is that the parts add up: a dashboard
 * headline of 27,773 systems over a list that sums to 27,769 is a dashboard
 * nobody trusts twice.
 */
class GridAllocationServiceTest {

    @Test
    void handsOutExactlyTheTotal() {
        int[] out = GridAllocationService.apportion(26678, new double[] {0.5, 0.3, 0.2});
        assertThat(Arrays.stream(out).sum()).isEqualTo(26678);
        assertThat(out).containsExactly(13339, 8003, 5336);
    }

    /** Three thirds of ten: someone has to get the fourth, nobody gets two. */
    @Test
    void spreadsTheRemainderOneEach() {
        int[] out = GridAllocationService.apportion(10, new double[] {1 / 3.0, 1 / 3.0, 1 / 3.0});
        assertThat(Arrays.stream(out).sum()).isEqualTo(10);
        assertThat(out).contains(4, 3, 3);
        assertThat(Arrays.stream(out).max().orElseThrow()).isEqualTo(4);
    }

    @Test
    void givesTheLeftoversToWhoeverWasRoundedDownHardest() {
        // Exact shares 4.9, 4.1, 1.0 - the 0.9 gets the spare system.
        int[] out = GridAllocationService.apportion(10, new double[] {0.49, 0.41, 0.10});
        assertThat(out).containsExactly(5, 4, 1);
    }

    @Test
    void staysExactAcrossManyAreas() {
        double[] shares = new double[67];
        Arrays.fill(shares, 1 / 67.0);
        for (int total : new int[] {19, 1076, 26678, 189737}) {
            assertThat(Arrays.stream(GridAllocationService.apportion(total, shares)).sum())
                    .isEqualTo(total);
        }
    }

    @Test
    void followsTheShares() {
        int[] out = GridAllocationService.apportion(1000, new double[] {0.8, 0.15, 0.05});
        assertThat(out[0]).isGreaterThan(out[1]);
        assertThat(out[1]).isGreaterThan(out[2]);
        assertThat(out[0]).isEqualTo(800);
    }

    @Test
    void givesNothingToAnAreaWithNoShare() {
        int[] out = GridAllocationService.apportion(100, new double[] {1.0, 0.0, 0.0});
        assertThat(out).containsExactly(100, 0, 0);
    }

    @Test
    void survivesADegenerateField() {
        assertThat(GridAllocationService.apportion(100, new double[] {0, 0, 0}))
                .containsExactly(0, 0, 0);
        assertThat(GridAllocationService.apportion(0, new double[] {0.5, 0.5}))
                .containsExactly(0, 0);
        assertThat(GridAllocationService.apportion(100, new double[] {})).isEmpty();
    }

    /** A negative share is not a thing; it must not become a negative count. */
    @Test
    void neverHandsOutANegativeCount() {
        int[] out = GridAllocationService.apportion(100, new double[] {1.2, -0.2});
        assertThat(Arrays.stream(out).min().orElseThrow()).isNotNegative();
        assertThat(Arrays.stream(out).sum()).isLessThanOrEqualTo(100);
    }
}
