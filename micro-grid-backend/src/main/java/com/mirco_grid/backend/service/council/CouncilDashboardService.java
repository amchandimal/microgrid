package com.mirco_grid.backend.service.council;

import com.mirco_grid.backend.service.council.CouncilApi.Consumption;
import com.mirco_grid.backend.service.council.CouncilApi.ConsumptionReport;
import com.mirco_grid.backend.service.council.CouncilApi.EquityMap;
import com.mirco_grid.backend.service.council.CouncilApi.EquityMapEntry;
import com.mirco_grid.backend.service.council.CouncilApi.RebateGap;
import com.mirco_grid.backend.service.council.CouncilApi.RebatePoint;
import com.mirco_grid.backend.service.council.CouncilApi.RebateProgram;
import com.mirco_grid.backend.service.council.CouncilApi.Rebates;
import com.mirco_grid.backend.service.council.CouncilApi.SourceNote;
import com.mirco_grid.backend.service.council.CouncilApi.Suburb;
import com.mirco_grid.backend.service.council.CouncilApi.SuburbDetail;
import com.mirco_grid.backend.service.council.CouncilApi.SuburbLeague;
import com.mirco_grid.backend.service.council.CouncilApi.Summary;
import com.mirco_grid.backend.service.council.CouncilApi.Trend;
import com.mirco_grid.backend.service.council.CouncilApi.TrendAnnotation;
import com.mirco_grid.backend.service.council.CouncilApi.TrendPoint;
import com.mirco_grid.backend.service.council.CouncilDataRepository.ConsumptionRow;
import com.mirco_grid.backend.service.council.CouncilDataRepository.MonthlyRow;
import com.mirco_grid.backend.service.council.CouncilDataRepository.RebateRow;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Turns the loaded tables into the seven things the dashboard asks for.
 *
 * <p>The rule throughout is that the browser is sent finished figures and the
 * citation that goes with them: the KPI strip does not do arithmetic on raw
 * columns, the charts do not have to know that the two monthly series are
 * separate files, and every panel carries the name of the workbook it came
 * from so nothing on screen is unattributable.
 */
@Service
public class CouncilDashboardService {

    private static final String LGA = "Wollongong City Council";

    /** The program whose take-up gap is the headline equity statistic. */
    private static final String HEADLINE_REBATE = "Low Income Household Rebate";

    private static final String METRIC_ACCOUNTS = "Total customer accounts";
    private static final String METRIC_PAID = "Total paid amount ($)";
    private static final String METRIC_UNIQUE = "Estimated number of unique customers";
    private static final String METRIC_ELIGIBLE = "Estimated number of eligible customers";

    private final CouncilDataRepository repository;
    private final EquityIndexService equityIndex;
    private final SuburbSeed seed;

    public CouncilDashboardService(
            CouncilDataRepository repository, EquityIndexService equityIndex, SuburbSeed seed) {
        this.repository = repository;
        this.equityIndex = equityIndex;
        this.seed = seed;
    }

    // --- /summary ------------------------------------------------------------

    public Summary summary() {
        Map<String, String> raw = repository.lgaSummary();
        List<Suburb> suburbs = equityIndex.suburbs();

        return new Summary(
                LGA,
                "Financial year 2024-25, installations since 2001",
                (int) figure(raw, "Total installations"),
                (int) figure(raw, "Residential installations"),
                (int) figure(raw, "Commercial installations"),
                (int) figure(raw, "Power stations"),
                (int) figure(raw, "Number of houses in LGA"),
                figure(raw, "LGA residential PV density"),
                figure(raw, "Installed residential capacity"),
                figure(raw, "Total installed capacity"),
                figure(raw, "Average annual kWh generated per kW installed"),
                figure(raw, "Annual total savings"),
                figure(raw, "Annual savings for a new system"),
                figure(raw, "Annual CO2 offset (all installations)"),
                CouncilConstants.AVG_ANNUAL_BILL_AUD,
                CouncilConstants.AVG_GRID_COST_C_KWH,
                headlineRebateGap(),
                suburbs.size(),
                (int) suburbs.stream().filter(Suburb::ranked).count(),
                (int) suburbs.stream().filter(Suburb::hotspot).count(),
                raw,
                List.of(
                        CouncilConstants.SOLAR_SOURCE,
                        CouncilConstants.BILL_SOURCE,
                        CouncilConstants.REBATE_SOURCE));
    }

    /** A headline figure out of the CSV preamble, tilde and units and all. */
    private static double figure(Map<String, String> summary, String key) {
        Double value = DataValues.number(summary.get(key));
        return value == null ? 0 : value;
    }

    // --- /suburbs ------------------------------------------------------------

    public SuburbLeague suburbs() {
        return new SuburbLeague(
                equityIndex.suburbs(),
                equityIndex.methodology(),
                List.of(CouncilConstants.SOLAR_SOURCE, CouncilConstants.CONSUMPTION_SOURCE));
    }

    /** @return the locality, or null when no such suburb is in the data */
    public SuburbDetail suburb(String locality) {
        Suburb suburb = equityIndex.find(locality);
        if (suburb == null) {
            return null;
        }
        Consumption consumption = suburb.postcode() == null
                ? null
                : consumptionFor(suburb.postcode());

        List<Suburb> peers = equityIndex.suburbs().stream()
                .filter(other -> suburb.postcode() != null
                        && suburb.postcode().equals(other.postcode())
                        && !other.locality().equals(suburb.locality()))
                .toList();

        return new SuburbDetail(suburb, consumption, peers, equityIndex.methodology());
    }

    // --- /trend --------------------------------------------------------------

    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    /** Months at the tail of the series that are checked for a late dip. */
    private static final int TAIL_MONTHS = 6;
    /** The window the tail is compared against. */
    private static final int BASELINE_MONTHS = 12;

    public Trend trend() {
        Map<String, TrendPoint> byMonth = new TreeMap<>();

        for (MonthlyRow row : repository.monthlyCapacity()) {
            byMonth.put(row.month(), new TrendPoint(
                    row.month(), row.residential(), row.commercial(), row.powerStations(),
                    0, 0, 0));
        }
        for (MonthlyRow row : repository.monthlyInstallations()) {
            TrendPoint existing = byMonth.get(row.month());
            byMonth.put(row.month(), new TrendPoint(
                    row.month(),
                    existing == null ? 0 : existing.capacityResidentialKw(),
                    existing == null ? 0 : existing.capacityCommercialKw(),
                    existing == null ? 0 : existing.capacityPowerStationsKw(),
                    (int) Math.round(row.residential()),
                    (int) Math.round(row.commercial()),
                    (int) Math.round(row.powerStations())));
        }

        List<TrendPoint> points = List.copyOf(byMonth.values());
        return new Trend(points, lateDip(points), List.of(CouncilConstants.SOLAR_SOURCE));
    }

    /**
     * Flags the drop at the end of the series rather than hard-coding it.
     *
     * <p>The last months of the file sit well below the year that precedes
     * them. That is worth pointing at on the chart, but it is also what a
     * partially reported quarter looks like - installers certify retrospectively -
     * so the annotation says both and lets the reader decide.
     *
     * <p>One annotation covering the whole run, not one per month: five labels
     * stacked against the right-hand edge of a 300-point chart is unreadable,
     * and the point being made is about the run rather than any single month.
     */
    private static List<TrendAnnotation> lateDip(List<TrendPoint> points) {
        if (points.size() < BASELINE_MONTHS + TAIL_MONTHS) {
            return List.of();
        }
        int tailStart = points.size() - TAIL_MONTHS;
        double baseline = points.subList(tailStart - BASELINE_MONTHS, tailStart).stream()
                .mapToInt(TrendPoint::installsResidential)
                .average()
                .orElse(0);
        if (baseline <= 0) {
            return List.of();
        }

        List<TrendPoint> tail = points.subList(tailStart, points.size());
        double tailAverage = tail.stream()
                .mapToInt(TrendPoint::installsResidential)
                .average()
                .orElse(0);
        if (tailAverage >= baseline * 0.9) {
            return List.of();
        }

        return List.of(new TrendAnnotation(
                tail.get(0).month(),
                tail.get(tail.size() - 1).month(),
                String.format("%.0f%% of the prior year's rate", 100 * tailAverage / baseline),
                String.format(
                        "%s to %s averaged %.0f residential systems a month against %.0f a"
                                + " month over the preceding year. Recent months are often"
                                + " still being certified, so treat the tail of this series as"
                                + " provisional rather than as a collapse in demand.",
                        label(tail.get(0).month()),
                        label(tail.get(tail.size() - 1).month()),
                        tailAverage,
                        baseline)));
    }

    private static String label(String isoMonth) {
        return YearMonth.parse(isoMonth).format(MONTH_LABEL);
    }

    // --- /consumption --------------------------------------------------------

    /**
     * Electricity by postcode, for the postcodes this Council covers.
     *
     * <p>The workbook is titled for the Wollongong LGA but reports the whole
     * network area, so it carries Albion Park and Shellharbour as well. Those
     * have no Wollongong locality against them in the seed, and leaving them on
     * a Wollongong chart would put load the Council has no say over next to
     * load it does. They are named in the note rather than dropped silently.
     */
    public ConsumptionReport consumption() {
        List<Consumption> postcodes = new ArrayList<>();
        List<String> outside = new ArrayList<>();

        for (ConsumptionRow row : repository.postcodeConsumption()) {
            if (row.totalMwh() <= 0) {
                // 2520-2522 are post-office boxes and the university; they
                // report no load at all.
                continue;
            }
            Consumption consumption = toConsumption(row);
            if (consumption.localities().isEmpty()
                    || SuburbSeed.PARTIAL_POSTCODES.contains(row.postcode())) {
                outside.add(row.postcode());
                continue;
            }
            postcodes.add(consumption);
        }
        postcodes.sort(Comparator.comparing(Consumption::postcode));

        String note = outside.isEmpty()
                ? "Postcodes 2520 to 2522 are omitted - they are post-office boxes and the"
                        + " university, and report no load."
                : "Postcodes " + String.join(", ", outside) + " are in the workbook but sit"
                        + " wholly or mostly in the Shellharbour LGA, so their load is not"
                        + " charted as Wollongong's. 2520 to 2522 report no load at all.";

        return new ConsumptionReport(
                List.copyOf(postcodes),
                headline(postcodes),
                note,
                List.of(CouncilConstants.CONSUMPTION_SOURCE));
    }

    private Consumption consumptionFor(String postcode) {
        ConsumptionRow row = repository.consumptionByPostcode().get(postcode);
        return row == null ? null : toConsumption(row);
    }

    private Consumption toConsumption(ConsumptionRow row) {
        return new Consumption(
                row.postcode(),
                seed.localitiesIn(row.postcode()),
                row.totalMwh(),
                row.domesticMwh(),
                row.controlledLoadMwh(),
                row.commercialMwh(),
                row.industrialMwh(),
                row.totalAccounts(),
                row.domesticAccounts(),
                row.domesticAccounts() == 0
                        ? null
                        : Math.round(row.domesticMwh() / row.domesticAccounts() * 100) / 100.0);
    }

    /** The one thing worth saying about this chart, computed from it. */
    private static String headline(List<Consumption> postcodes) {
        Consumption heaviest = postcodes.stream()
                .max(Comparator.comparingDouble(Consumption::industrialMwh))
                .orElse(null);
        if (heaviest == null) {
            return "";
        }
        Consumption cbd = postcodes.stream()
                .filter(row -> "2500".equals(row.postcode()))
                .findFirst()
                .orElse(heaviest);

        double domesticShare = 100 * postcodes.stream().mapToDouble(Consumption::domesticMwh).sum()
                / postcodes.stream().mapToDouble(Consumption::totalMwh).sum();

        return String.format(
                "Industry dominates the LGA's load: postcode %s alone draws %.0f GWh"
                        + " industrial against %.0f GWh domestic, and even in the city centre"
                        + " (%s) it is %.0f GWh against %.0f GWh. Households are %.0f%% of all"
                        + " electricity used here, which is why residential solar has to be"
                        + " argued on equity rather than on total megawatt hours.",
                heaviest.postcode(),
                heaviest.industrialMwh() / 1000,
                heaviest.domesticMwh() / 1000,
                cbd.postcode(),
                cbd.industrialMwh() / 1000,
                cbd.domesticMwh() / 1000,
                domesticShare);
    }

    // --- /rebates ------------------------------------------------------------

    public Rebates rebates() {
        List<RebateRow> rows = repository.rebateTrend();

        Set<String> years = new LinkedHashSet<>();
        // program -> fy -> metric -> value
        Map<String, Map<String, Map<String, Double>>> byProgram = new LinkedHashMap<>();

        for (RebateRow row : rows) {
            years.add(row.fy());
            byProgram
                    .computeIfAbsent(row.program(), key -> new TreeMap<>())
                    .computeIfAbsent(row.fy(), key -> new LinkedHashMap<>())
                    .put(row.metric(), row.value());
        }

        List<String> financialYears = years.stream().sorted().toList();

        List<RebateProgram> programs = new ArrayList<>();
        byProgram.forEach((program, byYear) -> {
            List<RebatePoint> points = new ArrayList<>();
            for (String fy : financialYears) {
                Map<String, Double> metrics = byYear.get(fy);
                if (metrics == null) {
                    continue;
                }
                Double accounts = metrics.get(METRIC_ACCOUNTS);
                Double eligible = metrics.get(METRIC_ELIGIBLE);
                points.add(new RebatePoint(
                        fy,
                        accounts,
                        metrics.get(METRIC_PAID),
                        metrics.get(METRIC_UNIQUE),
                        eligible,
                        takeUpPct(accounts, eligible)));
            }
            programs.add(new RebateProgram(program, List.copyOf(points)));
        });

        return new Rebates(
                financialYears,
                List.copyOf(programs),
                headlineRebateGap(),
                CouncilConstants.EAPA_FY_2022_23,
                List.of(CouncilConstants.REBATE_SOURCE));
    }

    /**
     * Accounts against eligible households for the flagship rebate.
     *
     * <p>The two are reported side by side and are never equal: entitlement is
     * estimated from Centrelink and ATO records, take-up is counted from
     * retailer reporting, and the difference is households that qualify for
     * money off their bill and are not getting it. That gap is the single
     * strongest argument for a Council-run sign-up drive, so it is computed
     * from the latest year the workbook reports both.
     */
    private RebateGap headlineRebateGap() {
        Double accounts = null;
        Double eligible = null;
        String fy = null;

        for (RebateRow row : repository.rebateTrend()) {
            if (!HEADLINE_REBATE.equalsIgnoreCase(row.program())) {
                continue;
            }
            if (METRIC_ACCOUNTS.equals(row.metric())) {
                if (fy == null || row.fy().compareTo(fy) >= 0) {
                    fy = row.fy();
                    accounts = row.value();
                }
            }
        }
        if (fy == null) {
            return null;
        }
        for (RebateRow row : repository.rebateTrend()) {
            if (HEADLINE_REBATE.equalsIgnoreCase(row.program())
                    && METRIC_ELIGIBLE.equals(row.metric())
                    && fy.equals(row.fy())) {
                eligible = row.value();
            }
        }
        if (accounts == null || eligible == null || eligible <= 0) {
            return null;
        }
        return new RebateGap(
                HEADLINE_REBATE,
                fy,
                accounts,
                eligible,
                Math.round(takeUpPct(accounts, eligible) * 10) / 10.0,
                eligible - accounts);
    }

    private static Double takeUpPct(Double accounts, Double eligible) {
        if (accounts == null || eligible == null || eligible <= 0) {
            return null;
        }
        return Math.round(100.0 * accounts / eligible * 10) / 10.0;
    }

    // --- /equity-map ---------------------------------------------------------

    public EquityMap equityMap() {
        List<Suburb> suburbs = equityIndex.suburbs();
        List<EquityMapEntry> mapped = new ArrayList<>();

        for (Suburb suburb : suburbs) {
            if (suburb.lat() == null || suburb.lng() == null) {
                continue;
            }
            mapped.add(new EquityMapEntry(
                    suburb.locality(),
                    suburb.postcode(),
                    suburb.lat(),
                    suburb.lng(),
                    suburb.equityScore(),
                    suburb.hotspot(),
                    suburb.ranked(),
                    suburb.resInstallsAlltime(),
                    suburb.resKwAlltime(),
                    suburb.resYoyPct(),
                    suburb.domesticMwhPerDwelling(),
                    suburb.pvDensityPct()));
        }
        return new EquityMap(
                List.copyOf(mapped), mapped.size(), suburbs.size(), equityIndex.methodology());
    }

    /** Just the suburbs the index flags, best action first. */
    public List<Suburb> hotspots() {
        return equityIndex.hotspots();
    }

    /** Every source the dashboard draws on, for the provenance footer. */
    public List<SourceNote> sources() {
        return List.of(
                CouncilConstants.SOLAR_SOURCE,
                CouncilConstants.CONSUMPTION_SOURCE,
                CouncilConstants.REBATE_SOURCE,
                CouncilConstants.BILL_SOURCE,
                CouncilConstants.GRID_SOURCE);
    }
}
