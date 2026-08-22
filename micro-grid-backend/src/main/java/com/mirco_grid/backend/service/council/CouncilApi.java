package com.mirco_grid.backend.service.council;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything the council dashboard is sent, in one place.
 *
 * <p>These are the wire shapes rather than the storage shapes: the tables are
 * whatever the source spreadsheets dictated, and the joins, the derived
 * figures and the equity index all happen between there and here. Keeping the
 * contract in a single file means the Angular models can be read off it
 * side by side.
 */
public final class CouncilApi {

    private CouncilApi() {}

    // --- auth ----------------------------------------------------------------

    public record LoginRequest(String username, String password) {}

    public record LoginResponse(String token, String role, String displayName) {}

    // --- shared --------------------------------------------------------------

    /** Where a figure came from, so the UI can show its provenance. */
    public record SourceNote(String label, String source) {}

    /**
     * How the equity index is put together.
     *
     * <p>Sent with every ranking response rather than written into the UI, so
     * what is on screen cannot drift from what was actually computed.
     */
    public record Methodology(
            String formula,
            List<Term> terms,
            String hotspotRule,
            String dwellingEstimate,
            List<String> notes) {}

    /** One weighted term of the index. */
    public record Term(String name, double weight, String source, boolean inverted) {}

    // --- /summary ------------------------------------------------------------

    public record Summary(
            String lga,
            String period,
            int totalInstallations,
            int residentialInstallations,
            int commercialInstallations,
            int powerStations,
            int housesInLga,
            double pvDensityPct,
            double installedResidentialKw,
            double totalInstalledKw,
            double avgKwhPerKwInstalled,
            double annualSavingsAud,
            double annualSavingsPerSystemAud,
            double annualCo2OffsetTonnes,
            double avgAnnualBillAud,
            double avgGridCostCkwh,
            RebateGap rebateGap,
            int localityCount,
            int rankedCount,
            int hotspotCount,
            /** The headline block exactly as the CSV wrote it, for the fine print. */
            Map<String, String> lgaSummary,
            List<SourceNote> sources) {}

    /** Rebate accounts against households estimated to be eligible. */
    public record RebateGap(
            String program,
            String fy,
            double accounts,
            double eligible,
            double takeUpPct,
            double missingOut) {}

    // --- /suburbs ------------------------------------------------------------

    public record Suburb(
            String locality,
            String postcode,
            int resInstallsAlltime,
            double resKwAlltime,
            int resInstallsFy,
            double resKwFy,
            Double resYoyPct,
            int comInstallsAlltime,
            double comKwAlltime,
            int comInstallsFy,
            double comKwFy,
            Double comYoyPct,
            int psInstallsFy,
            double psKwFy,
            /** From the all-time CSV, which runs three quarters later than the FY one. */
            Integer resInstallsCurrent,
            Double resKwCurrent,
            /** kW per installation - one of the few genuinely per-suburb ratios. */
            Double avgSystemKw,
            /** Postcode-level, because that is the finest the consumption data goes. */
            Integer postcodeResInstalls,
            Integer postcodeDomesticAccounts,
            /** Installations per 100 domestic accounts in the postcode. */
            Double pvDensityPct,
            Double domesticMwhPerDwelling,
            Double solarScore,
            Double growthScore,
            Double consumptionScore,
            Double equityScore,
            Integer rank,
            /** False where there is too little housing to rank the locality fairly. */
            boolean ranked,
            boolean hotspot,
            String action,
            String actionReason,
            Double lat,
            Double lng) {}

    public record SuburbLeague(
            List<Suburb> suburbs, Methodology methodology, List<SourceNote> sources) {}

    public record SuburbDetail(
            Suburb suburb,
            Consumption consumption,
            List<Suburb> postcodePeers,
            Methodology methodology) {}

    // --- /trend --------------------------------------------------------------

    public record TrendPoint(
            String month,
            double capacityResidentialKw,
            double capacityCommercialKw,
            double capacityPowerStationsKw,
            int installsResidential,
            int installsCommercial,
            int installsPowerStations) {}

    /** A span of the series worth pointing at. {@code monthEnd} is inclusive. */
    public record TrendAnnotation(String month, String monthEnd, String label, String detail) {}

    public record Trend(
            List<TrendPoint> points,
            List<TrendAnnotation> annotations,
            List<SourceNote> sources) {}

    // --- /consumption --------------------------------------------------------

    public record Consumption(
            String postcode,
            List<String> localities,
            double totalMwh,
            double domesticMwh,
            double controlledLoadMwh,
            double commercialMwh,
            double industrialMwh,
            int totalAccounts,
            int domesticAccounts,
            Double domesticMwhPerDwelling) {}

    public record ConsumptionReport(
            List<Consumption> postcodes,
            String headline,
            /** What was left out of the chart, and why. */
            String note,
            List<SourceNote> sources) {}

    // --- /rebates ------------------------------------------------------------

    public record RebatePoint(
            String fy,
            Double accounts,
            Double paidAmountAud,
            Double uniqueCustomers,
            Double eligibleCustomers,
            Double takeUpPct) {}

    public record RebateProgram(String program, List<RebatePoint> points) {}

    /** EAPA vouchers approved in FY2022-23. */
    public record EapaVouchers(
            String fy,
            int electricityApplications,
            int gasApplications,
            double valueAud,
            String source) {}

    public record Rebates(
            List<String> financialYears,
            List<RebateProgram> programs,
            RebateGap headlineGap,
            EapaVouchers eapa,
            List<SourceNote> sources) {}

    // --- /equity-map ---------------------------------------------------------

    public record EquityMapEntry(
            String locality,
            String postcode,
            double lat,
            double lng,
            Double equityScore,
            boolean hotspot,
            boolean ranked,
            int resInstallsAlltime,
            double resKwAlltime,
            Double resYoyPct,
            Double domesticMwhPerDwelling,
            Double pvDensityPct) {}

    public record EquityMap(
            List<EquityMapEntry> localities,
            int mappedCount,
            int localityCount,
            Methodology methodology) {}

    // --- /grid-stress --------------------------------------------------------

    public record GridStressArea(
            String id,
            String name,
            String suburb,
            double lat,
            double lng,
            String state,
            boolean exportConstrained,
            double pctDayInPeak,
            double supplyKw,
            double demandKw,
            double priceSignalCkwh,
            Integer batterySocPct) {}

    public record GridStress(
            OffsetDateTime asOf,
            List<GridStressArea> areas,
            int peakNow,
            int exportConstrainedNow,
            String note) {}
}
