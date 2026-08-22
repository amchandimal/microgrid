package com.mirco_grid.backend.service.council;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reads the shipped files and checks them against figures taken by hand.
 *
 * <p>Every expected value here was read out of the source spreadsheet, not out
 * of the parser: Austinmer's two different all-time totals, the LGA headline
 * block, postcode 2500's twenty thousand domestic accounts, the Low Income
 * Household Rebate's take-up against entitlement. If a parser starts reading a
 * column one to the left, these fail rather than the dashboard quietly showing
 * the wrong suburb's numbers.
 */
class EnergyDataReadersTest {

    // --- the suburb CSVs -----------------------------------------------------

    @Test
    void readsTheLgaHeadlineBlockOffTheTopOfTheFile() throws IOException {
        Map<String, String> summary =
                SuburbSolarCsvReader.read(EnergyDataFiles.SUBURB_SOLAR_FY).lgaSummary();

        assertThat(summary).containsEntry("Total installations", "27,776");
        assertThat(summary).containsEntry("Number of houses in LGA", "73,390");
        assertThat(summary).containsEntry("LGA residential PV density", "~36.4%");
        assertThat(summary).containsEntry("Annual total savings", "~$30,577,000");
        assertThat(summary).containsEntry("Annual CO2 offset (all installations)", "154,000 tonnes");
        assertThat(summary).hasSize(15);
    }

    @Test
    void readsEveryLocalityOutOfTheFinancialYearFile() throws IOException {
        List<String[]> rows =
                SuburbSolarCsvReader.read(EnergyDataFiles.SUBURB_SOLAR_FY).rows();

        assertThat(rows).hasSize(67);
        assertThat(rows).allSatisfy(row -> assertThat(row).hasSizeGreaterThanOrEqualTo(13));

        String[] austinmer = row(rows, "Austinmer");
        assertThat(DataValues.integer(austinmer[1])).isEqualTo(427);
        assertThat(DataValues.number(austinmer[2])).isEqualTo(2313);
        assertThat(DataValues.integer(austinmer[3])).isEqualTo(29);
        assertThat(DataValues.number(austinmer[5])).isEqualTo(7.29);
    }

    /**
     * The two files disagree on purpose - one counts to the end of FY24-25 and
     * the other to the end of the series - so both are loaded and labelled.
     */
    @Test
    void readsTheLaterAllTimeTotalsFromTheOtherFile() throws IOException {
        List<String[]> rows =
                SuburbSolarCsvReader.read(EnergyDataFiles.SUBURB_SOLAR_ALL_TIME).rows();

        assertThat(rows).hasSize(67);

        String[] austinmer = row(rows, "Austinmer");
        assertThat(DataValues.integer(austinmer[1])).isEqualTo(452);
        assertThat(DataValues.number(austinmer[2])).isEqualTo(2532);
    }

    @Test
    void stripsTheFootnoteMarkerOffLocalityNames() throws IOException {
        List<String[]> rows =
                SuburbSolarCsvReader.read(EnergyDataFiles.SUBURB_SOLAR_FY).rows();

        assertThat(rows.stream().map(row -> DataValues.locality(row[0])))
                .contains("Avon", "Woronora Dam", "Darkes Forest")
                .doesNotContain("Avon *");
    }

    private static String[] row(List<String[]> rows, String locality) {
        return rows.stream()
                .filter(cells -> locality.equals(DataValues.locality(cells[0])))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + locality));
    }

    // --- the monthly series --------------------------------------------------

    @Test
    void readsTheCapacitySeriesWrittenAsJanTwoThousandAndOne() throws IOException {
        List<MonthlySeriesCsvReader.Point> points =
                MonthlySeriesCsvReader.read(EnergyDataFiles.MONTHLY_CAPACITY);

        assertThat(points).hasSize(300);
        assertThat(points.get(0).month()).isEqualTo("2001-01");
        assertThat(points.get(points.size() - 1).month()).isEqualTo("2025-12");

        MonthlySeriesCsvReader.Point december = points.get(points.size() - 1);
        assertThat(december.residential()).isEqualTo(859);
        assertThat(december.commercial()).isEqualTo(187);
    }

    /**
     * This one writes "Jan-01", pads every row with a trailing empty column and
     * finishes with an unlabelled totals row. All three have to survive.
     */
    @Test
    void readsTheInstallationSeriesWrittenAsJanZeroOne() throws IOException {
        List<MonthlySeriesCsvReader.Point> points =
                MonthlySeriesCsvReader.read(EnergyDataFiles.MONTHLY_INSTALLATIONS);

        assertThat(points).hasSize(300);
        assertThat(points.get(0).month()).isEqualTo("2001-01");
        assertThat(points.get(points.size() - 1).month()).isEqualTo("2025-12");
        assertThat(points.get(points.size() - 1).residential()).isEqualTo(107);

        // The totals row at the bottom has no date, so it must not be a point.
        double total = points.stream()
                .mapToDouble(MonthlySeriesCsvReader.Point::residential)
                .sum();
        assertThat(total).isEqualTo(26678);
    }

    @Test
    void sortsTheMonthsBecauseTheKeyIsIsoRatherThanTheFilesLabel() throws IOException {
        List<MonthlySeriesCsvReader.Point> points =
                MonthlySeriesCsvReader.read(EnergyDataFiles.MONTHLY_CAPACITY);

        assertThat(points.stream().map(MonthlySeriesCsvReader.Point::month))
                .isSorted();
    }

    // --- the consumption workbook --------------------------------------------

    @Test
    void readsBothBlocksOfTheConsumptionSheet() throws IOException {
        List<ConsumptionWorkbookReader.Row2122> rows = ConsumptionWorkbookReader.read();

        assertThat(rows).hasSize(19);

        ConsumptionWorkbookReader.Row2122 cbd = rows.stream()
                .filter(row -> row.postcode().equals("2500"))
                .findFirst()
                .orElseThrow();

        assertThat(cbd.totalMwh()).isCloseTo(436081.58, within(0.01));
        assertThat(cbd.domesticMwh()).isCloseTo(80467.54, within(0.01));
        assertThat(cbd.industrialMwh()).isCloseTo(288324.60, within(0.01));
        // The second block, read positionally off the first.
        assertThat(cbd.totalAccounts()).isEqualTo(23385);
        assertThat(cbd.domesticAccounts()).isEqualTo(20289);
    }

    @Test
    void keepsThePostcodesThatReportNothing() throws IOException {
        List<ConsumptionWorkbookReader.Row2122> rows = ConsumptionWorkbookReader.read();

        assertThat(rows.stream().map(ConsumptionWorkbookReader.Row2122::postcode))
                .contains("2520", "2521", "2522");
        assertThat(rows.stream()
                        .filter(row -> row.postcode().equals("2520"))
                        .findFirst()
                        .orElseThrow()
                        .totalMwh())
                .isZero();
    }

    private static org.assertj.core.data.Offset<Double> within(double tolerance) {
        return org.assertj.core.data.Offset.offset(tolerance);
    }

    // --- the rebates workbook ------------------------------------------------

    @Test
    void unpivotsTableOneOfTheRebatesWorkbook() throws IOException {
        List<RebateWorkbookReader.Point> points = RebateWorkbookReader.read();

        assertThat(points).isNotEmpty();
        assertThat(points.stream().map(RebateWorkbookReader.Point::fy).distinct())
                .containsExactlyInAnyOrder(
                        "FY2017-18", "FY2018-19", "FY2019-20",
                        "FY2020-21", "FY2021-22", "FY2022-23");

        assertThat(points.stream().map(RebateWorkbookReader.Point::program).distinct())
                .contains(
                        "Low Income Household Rebate",
                        "NSW Gas Rebate",
                        "Seniors Energy Rebate",
                        "Energy Accounts Payment Assistance (EAPA) Scheme",
                        "All Rebates");
    }

    /** The pair the dashboard leads with: take-up against entitlement. */
    @Test
    void readsTheTakeUpGapForTheFlagshipRebate() throws IOException {
        List<RebateWorkbookReader.Point> points = RebateWorkbookReader.read();

        assertThat(value(points, "Low Income Household Rebate", "Total customer accounts",
                        "FY2022-23"))
                .isEqualTo(911_200);
        assertThat(value(points, "Low Income Household Rebate",
                        "Estimated number of eligible customers", "FY2022-23"))
                .isEqualTo(1_181_300);
        assertThat(value(points, "Low Income Household Rebate", "Total paid amount ($)",
                        "FY2022-23"))
                .isEqualTo(227_982_900);
    }

    /**
     * The Seniors rebate did not exist before 2019 and the workbook says "n/a".
     * That has to stay missing rather than become a zero on a chart.
     */
    @Test
    void leavesOutTheYearsAProgramDidNotExist() throws IOException {
        List<RebateWorkbookReader.Point> points = RebateWorkbookReader.read();

        assertThat(points)
                .noneMatch(point -> point.program().equals("Seniors Energy Rebate")
                        && point.fy().equals("FY2017-18"));
        assertThat(value(points, "Seniors Energy Rebate", "Total customer accounts", "FY2022-23"))
                .isEqualTo(43_300);
    }

    private static double value(
            List<RebateWorkbookReader.Point> points,
            String program,
            String metric,
            String fy) {
        return points.stream()
                .filter(point -> point.program().equals(program))
                .filter(point -> point.metric().equals(metric))
                .filter(point -> point.fy().equals(fy))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No " + metric + " for " + program + " in " + fy))
                .value();
    }
}
