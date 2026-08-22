package com.mirco_grid.backend.service.council;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The quirks of the Energy Equity Challenge files, one per test.
 *
 * <p>Every one of these is a real cell from a real file - if a future export
 * changes its house style, this is where it breaks rather than three tables
 * downstream.
 */
class DataValuesTest {

    // --- numbers -------------------------------------------------------------

    @Test
    void readsThroughThousandsSeparators() {
        assertThat(DataValues.number("27,776")).isEqualTo(27776.0);
        assertThat(DataValues.number("155,495")).isEqualTo(155495.0);
    }

    @Test
    void readsThroughApproximationAndCurrency() {
        assertThat(DataValues.number("~$30,577,000")).isEqualTo(30577000.0);
        assertThat(DataValues.number("~$1,400")).isEqualTo(1400.0);
    }

    @Test
    void readsThroughPercentSigns() {
        assertThat(DataValues.number("~36.4%")).isEqualTo(36.4);
        assertThat(DataValues.number("7.29%")).isEqualTo(7.29);
    }

    @Test
    void readsThroughTrailingUnits() {
        assertThat(DataValues.number("154,000 tonnes")).isEqualTo(154000.0);
        assertThat(DataValues.number("4,270 tonnes")).isEqualTo(4270.0);
    }

    /**
     * The distinction the equity index depends on: a suburb with no reported
     * growth is not a suburb that grew by zero.
     */
    @Test
    void treatsTheFilesHyphenAsMissingRatherThanZero() {
        assertThat(DataValues.number("-")).isNull();
        assertThat(DataValues.number("")).isNull();
        assertThat(DataValues.number("   ")).isNull();
        assertThat(DataValues.number("n/a")).isNull();
        assertThat(DataValues.number(null)).isNull();

        assertThat(DataValues.numberOrZero("-")).isEqualTo(0);
        assertThat(DataValues.integerOrZero("-")).isEqualTo(0);
    }

    @Test
    void roundsToWholeInstallationCounts() {
        assertThat(DataValues.integer("1,074")).isEqualTo(1074);
        assertThat(DataValues.integer("-")).isNull();
    }

    @Test
    void keepsOnlyTheFirstDecimalPoint() {
        assertThat(DataValues.number("1.234.5")).isEqualTo(1.2345);
    }

    // --- names ---------------------------------------------------------------

    @Test
    void stripsTheFootnoteMarkerFromALocality() {
        assertThat(DataValues.locality("Avon *")).isEqualTo("Avon");
        assertThat(DataValues.locality("Woronora Dam *")).isEqualTo("Woronora Dam");
        assertThat(DataValues.locality("Darkes Forest *")).isEqualTo("Darkes Forest");
    }

    @Test
    void leavesAnUnmarkedLocalityAlone() {
        assertThat(DataValues.locality("Mount Saint Thomas")).isEqualTo("Mount Saint Thomas");
        assertThat(DataValues.locality(null)).isNull();
    }

    @Test
    void stripsTheFootnoteDigitsGluedOntoRebateLabels() {
        assertThat(DataValues.withoutFootnote("NSW Gas Rebate5")).isEqualTo("NSW Gas Rebate");
        assertThat(DataValues.withoutFootnote("Seniors Energy Rebate7"))
                .isEqualTo("Seniors Energy Rebate");
        assertThat(DataValues.withoutFootnote("Total customer accounts1"))
                .isEqualTo("Total customer accounts");
        assertThat(DataValues.withoutFootnote("Total paid amount ($)2"))
                .isEqualTo("Total paid amount ($)");
    }

    @Test
    void leavesLabelsThatEndInARealNumberAlone() {
        assertThat(DataValues.withoutFootnote("Low Income Household Rebate"))
                .isEqualTo("Low Income Household Rebate");
        assertThat(DataValues.withoutFootnote("Energy Accounts Payment Assistance (EAPA) Scheme"))
                .isEqualTo("Energy Accounts Payment Assistance (EAPA) Scheme");
        // A digit after a space or a digit is part of the label, not a marker.
        assertThat(DataValues.withoutFootnote("FY2022-23")).isEqualTo("FY2022-23");
        assertThat(DataValues.withoutFootnote("Table 1")).isEqualTo("Table 1");
    }
}
