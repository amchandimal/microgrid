package com.mirco_grid.backend.service.council;

import com.mirco_grid.backend.service.council.CouncilApi.Methodology;
import com.mirco_grid.backend.service.council.CouncilApi.Suburb;
import com.mirco_grid.backend.service.council.CouncilApi.Term;
import com.mirco_grid.backend.service.council.CouncilDataRepository.ConsumptionRow;
import com.mirco_grid.backend.service.council.CouncilDataRepository.CurrentSolarRow;
import com.mirco_grid.backend.service.council.CouncilDataRepository.SolarRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Scores every locality 0-100 on how well it is served by rooftop solar.
 *
 * <p>Three terms, weighted as the brief sets them out: how much solar is
 * already on the roofs (half), how fast that is still growing (a fifth), and
 * how much electricity the homes there get through (the remaining three
 * tenths, inverted - high use with low solar is a household under pressure,
 * not a household doing well). Each term is min-maxed across the LGA first, so
 * a score answers "compared with the rest of Wollongong" rather than being an
 * absolute.
 *
 * <p>The denominator is the whole problem. Solar density needs a dwelling
 * count and the challenge data has none by suburb - it has installations by
 * suburb and electricity accounts by postcode. Splitting a postcode's accounts
 * across its suburbs was tried and thrown away: with no suburb boundaries to
 * weight by, it produced densities over 200% and put Koonawarra at the top of
 * the table. So density is computed at the level the data actually supports -
 * residential installations in a postcode over that postcode's domestic
 * electricity accounts - and every suburb in a postcode carries it. Nothing in
 * the index is estimated; within a postcode, suburbs are separated by their own
 * growth rate.
 *
 * <p>That resolution is not a limitation to hide, it is the finding: consumption
 * is only published by postcode, so postcode is as fine as an equity judgement
 * about Wollongong can honestly get from this data. The methodology travels with
 * every response so the dashboard says so on screen.
 *
 * <p>Deliberately not stored. Every input is fixed once the files are loaded, so
 * the whole ranking is computed once on first use and held; changing a weight is
 * a code change and a restart, not a migration.
 */
@Service
public class EquityIndexService {

    /** The brief's weights. They sum to 1 and the API sends them to the UI. */
    private static final double WEIGHT_SOLAR = 0.5;
    private static final double WEIGHT_GROWTH = 0.2;
    private static final double WEIGHT_CONSUMPTION = 0.3;

    /** Below the bottom quartile of the ranked localities is a hotspot. */
    private static final double HOTSPOT_QUANTILE = 0.25;

    /** See {@link SuburbSeed#PARTIAL_POSTCODES} - Windang cannot be ranked. */
    private static final Set<String> PARTIAL_POSTCODES = SuburbSeed.PARTIAL_POSTCODES;

    /**
     * A locality needs this many residential installations to be ranked.
     *
     * <p>Two of the postcode's three terms are the same for every suburb in it,
     * so a locality with almost no installations of its own would be scored
     * almost entirely on its neighbours. Maddens Plains and Woronora Dam have
     * none at all - they are national park and a reservoir - and an
     * intervention list that names them is not one a councillor can act on.
     * They stay in the table, with the reason shown, but out of the ranking.
     */
    private static final int MIN_INSTALLS_TO_RANK = 25;

    /**
     * And this many before its own growth rate is believed.
     *
     * <p>Year-on-year percentages off a base of five installations swing by
     * twenty points when one household signs up. Below this, the locality is
     * given the median growth so the noise cannot carry it up the table.
     */
    private static final int MIN_INSTALLS_FOR_OWN_GROWTH = 50;

    private final CouncilDataRepository repository;
    private final SuburbSeed seed;

    /** Computed once; every input is immutable after the loader has run. */
    private volatile List<Suburb> ranking;

    public EquityIndexService(CouncilDataRepository repository, SuburbSeed seed) {
        this.repository = repository;
        this.seed = seed;
    }

    /** Every locality, best-served first. */
    public List<Suburb> suburbs() {
        List<Suburb> cached = ranking;
        if (cached == null) {
            synchronized (this) {
                cached = ranking;
                if (cached == null) {
                    cached = compute();
                    ranking = cached;
                }
            }
        }
        return cached;
    }

    /** @return the locality, matched case-insensitively, or null */
    public Suburb find(String locality) {
        if (locality == null) {
            return null;
        }
        String wanted = locality.trim();
        for (Suburb suburb : suburbs()) {
            if (suburb.locality().equalsIgnoreCase(wanted)) {
                return suburb;
            }
        }
        return null;
    }

    public List<Suburb> hotspots() {
        return suburbs().stream().filter(Suburb::hotspot).toList();
    }

    /** The formula, sent alongside every ranking so the UI can show its working. */
    public Methodology methodology() {
        return new Methodology(
                "equity = 100 x (0.5 x norm(pv_density) + 0.2 x norm(yoy_growth)"
                        + " + 0.3 x (1 - norm(domestic_mwh_per_dwelling)))",
                List.of(
                        new Term("Rooftop solar density", WEIGHT_SOLAR,
                                "Residential installations since 2001 over domestic electricity"
                                        + " accounts, by postcode",
                                false),
                        new Term("Growth", WEIGHT_GROWTH,
                                "Residential % increase year on year, FY 2024-25, by suburb",
                                false),
                        new Term("Household consumption", WEIGHT_CONSUMPTION,
                                "Domestic MWh over domestic accounts, by postcode, 2021/22",
                                true)),
                "A locality scoring below the 25th percentile of ranked localities"
                        + " is flagged as a hotspot.",
                "There is no dwelling count by suburb anywhere in the data, so density is"
                        + " computed where the data supports it: a postcode's residential"
                        + " installations over its domestic electricity accounts. Nothing is"
                        + " estimated - suburbs sharing a postcode share that figure, and are"
                        + " separated by their own growth rate.",
                List.of(
                        "Each term is min-max normalised across the ranked localities, so a"
                                + " score is a position within Wollongong, not an absolute.",
                        "Density here is installations per domestic electricity account, which"
                                + " counts units as well as houses. The LGA headline figure of"
                                + " 36.4% is installations per house, so the two are not"
                                + " directly comparable.",
                        "Consumption and density are only published by postcode, so every"
                                + " suburb sharing a postcode carries the same two terms.",
                        "A locality needs " + MIN_INSTALLS_TO_RANK + " residential installations"
                                + " to be ranked, and " + MIN_INSTALLS_FOR_OWN_GROWTH + " before"
                                + " its own growth rate is used rather than the median. Below"
                                + " that the numbers are noise: national park and reservoir"
                                + " localities would otherwise be scored on their neighbours.",
                        "Localities with no usable year-on-year growth are given the median"
                                + " growth rather than zero, so a missing figure is not read as"
                                + " a stalled suburb.",
                        "Consumption is read as pressure, following the brief: high use with"
                                + " low solar is a household paying more than it should. The"
                                + " reverse can also be true - a household that cannot afford"
                                + " to heat or cool shows up as low consumption and scores well"
                                + " here - so a low consumption term is worth reading beside"
                                + " the rebate take-up panel rather than on its own.",
                        "Windang is listed but not ranked: postcode 2528 is mostly in the"
                                + " Shellharbour LGA, so its account count does not belong to"
                                + " Windang's installations.",
                        "The aggregate \"" + SuburbSolarCsvReader.UNATTRIBUTED_ROW + "\" row in"
                                + " the source CSV is excluded - it is installations that could"
                                + " not be attributed to a locality, not a place."));
    }

    // --- the computation ------------------------------------------------------

    private List<Suburb> compute() {
        List<SolarRow> solar = repository.suburbSolar().stream()
                .filter(row -> row.locality() != null)
                .filter(row ->
                        !row.locality().equalsIgnoreCase(SuburbSolarCsvReader.UNATTRIBUTED_ROW))
                .toList();
        Map<String, CurrentSolarRow> current = repository.suburbSolarCurrentByLocality();
        Map<String, ConsumptionRow> consumption = repository.consumptionByPostcode();

        Map<String, Postcode> postcodes = postcodeTotals(solar, consumption);

        // The raw terms, before normalising. Absent means "cannot be ranked on this".
        Map<String, Double> pvDensity = new LinkedHashMap<>();
        Map<String, Double> growth = new LinkedHashMap<>();
        Map<String, Double> mwhPerDwelling = new LinkedHashMap<>();

        for (SolarRow row : solar) {
            String name = row.locality();
            Postcode postcode = postcodes.get(seed.postcodeOf(name));
            if (postcode != null) {
                if (postcode.pvDensityPct() != null) {
                    pvDensity.put(name, postcode.pvDensityPct());
                }
                mwhPerDwelling.put(name, postcode.mwhPerDwelling());
            }
            if (row.resYoyPct() != null
                    && orZero(row.resInstallsAlltime()) >= MIN_INSTALLS_FOR_OWN_GROWTH) {
                growth.put(name, row.resYoyPct());
            }
        }

        Map<String, Integer> installs = new LinkedHashMap<>();
        solar.forEach(row -> installs.put(row.locality(), orZero(row.resInstallsAlltime())));

        // Ranked when both postcode terms are available and the locality has
        // enough solar of its own to be talking about. Growth is optional and
        // falls back to the median.
        List<String> rankable = solar.stream()
                .map(SolarRow::locality)
                .filter(name -> pvDensity.containsKey(name) && mwhPerDwelling.containsKey(name))
                .filter(name -> installs.getOrDefault(name, 0) >= MIN_INSTALLS_TO_RANK)
                .toList();

        Range densityRange = Range.over(rankable, pvDensity);
        Range growthRange = Range.over(rankable, growth);
        Range loadRange = Range.over(rankable, mwhPerDwelling);
        double medianGrowth = median(rankable, growth);
        double medianLoad = median(rankable, mwhPerDwelling);
        double lgaDensity = lgaPvDensity(postcodes);

        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Double> solarScores = new LinkedHashMap<>();
        Map<String, Double> growthScores = new LinkedHashMap<>();
        Map<String, Double> loadScores = new LinkedHashMap<>();

        for (String name : rankable) {
            double solarScore = densityRange.normalise(pvDensity.get(name));
            double growthScore = growthRange.normalise(growth.getOrDefault(name, medianGrowth));
            double loadScore = loadRange.normalise(mwhPerDwelling.get(name));

            solarScores.put(name, solarScore);
            growthScores.put(name, growthScore);
            loadScores.put(name, loadScore);
            scores.put(name, 100 * (WEIGHT_SOLAR * solarScore
                    + WEIGHT_GROWTH * growthScore
                    + WEIGHT_CONSUMPTION * (1 - loadScore)));
        }

        double hotspotCutoff =
                quantile(scores.values().stream().sorted().toList(), HOTSPOT_QUANTILE);

        List<Suburb> built = new ArrayList<>();
        for (SolarRow row : solar) {
            String name = row.locality();
            SuburbSeed.Locality place = seed.find(name);
            Postcode postcode = postcodes.get(seed.postcodeOf(name));
            CurrentSolarRow now = current.get(name);
            Double score = scores.get(name);
            boolean ranked = score != null;

            built.add(new Suburb(
                    name,
                    place == null ? null : place.postcode(),
                    orZero(row.resInstallsAlltime()),
                    orZero(row.resKwAlltime()),
                    orZero(row.resInstallsFy()),
                    orZero(row.resKwFy()),
                    row.resYoyPct(),
                    orZero(row.comInstallsAlltime()),
                    orZero(row.comKwAlltime()),
                    orZero(row.comInstallsFy()),
                    orZero(row.comKwFy()),
                    row.comYoyPct(),
                    orZero(row.psInstallsFy()),
                    orZero(row.psKwFy()),
                    now == null ? null : now.resInstalls(),
                    now == null ? null : now.resKw(),
                    avgSystemKw(row),
                    postcode == null ? null : postcode.residentialInstalls(),
                    postcode == null ? null : postcode.domesticAccounts(),
                    round(pvDensity.get(name), 1),
                    round(mwhPerDwelling.get(name), 2),
                    round(solarScores.get(name), 3),
                    round(growthScores.get(name), 3),
                    round(loadScores.get(name), 3),
                    round(score, 1),
                    null,
                    ranked,
                    ranked && score <= hotspotCutoff,
                    null,
                    null,
                    place == null ? null : place.lat(),
                    place == null ? null : place.lng()));
        }

        // Best served first; unranked localities fall to the bottom, alphabetical.
        built.sort(Comparator
                .comparing((Suburb s) -> s.equityScore() == null)
                .thenComparing(s -> s.equityScore() == null ? 0 : -s.equityScore())
                .thenComparing(Suburb::locality));

        return withRanksAndActions(built, lgaDensity, medianLoad);
    }

    /**
     * Rank numbers and the suggested intervention, once the order is settled.
     *
     * <p>The action is derived from the same three terms the score is, so a
     * councillor reading "Solar Banks" beside a suburb can see in that row why
     * it says so.
     */
    private static List<Suburb> withRanksAndActions(
            List<Suburb> ordered, double lgaDensity, double medianLoad) {

        List<Suburb> finished = new ArrayList<>(ordered.size());
        int rank = 0;
        for (Suburb suburb : ordered) {
            Integer place = suburb.ranked() ? ++rank : null;
            String action = null;
            String reason = null;

            if (suburb.hotspot()) {
                boolean lowDensity = suburb.pvDensityPct() != null
                        && suburb.pvDensityPct() < lgaDensity;
                boolean heavyUse = suburb.domesticMwhPerDwelling() != null
                        && suburb.domesticMwhPerDwelling() > medianLoad;

                if (lowDensity && heavyUse) {
                    action = "Solar Banks round, targeted";
                    reason = String.format(
                            "Postcode %s runs at %.1f solar installations per 100 homes against"
                                    + " %.1f across the LGA, and households there use %.1f MWh a"
                                    + " year. Low access and high bills in the same place.",
                            suburb.postcode(), suburb.pvDensityPct(), lgaDensity,
                            suburb.domesticMwhPerDwelling());
                } else if (lowDensity) {
                    action = "Solar Banks plus a renter and apartment offer";
                    reason = String.format(
                            "Only %.1f installations per 100 homes in postcode %s against %.1f"
                                    + " across the LGA. Households here are largely people who"
                                    + " cannot put panels on their own roof.",
                            suburb.pvDensityPct(), suburb.postcode(), lgaDensity);
                } else if (heavyUse) {
                    action = "Community battery candidate";
                    reason = String.format(
                            "Solar access is near the LGA average at %.1f per 100 homes, but"
                                    + " households use %.1f MWh a year against a %.1f median."
                                    + " Shared storage shifts that load off the evening peak.",
                            suburb.pvDensityPct(), suburb.domesticMwhPerDwelling(), medianLoad);
                } else {
                    action = "Review at the next Solar Banks round";
                    reason = "In the bottom quartile overall without one term dominating -"
                            + " uptake has stalled rather than never started.";
                }
            }

            finished.add(new Suburb(
                    suburb.locality(), suburb.postcode(),
                    suburb.resInstallsAlltime(), suburb.resKwAlltime(),
                    suburb.resInstallsFy(), suburb.resKwFy(), suburb.resYoyPct(),
                    suburb.comInstallsAlltime(), suburb.comKwAlltime(),
                    suburb.comInstallsFy(), suburb.comKwFy(), suburb.comYoyPct(),
                    suburb.psInstallsFy(), suburb.psKwFy(),
                    suburb.resInstallsCurrent(), suburb.resKwCurrent(),
                    suburb.avgSystemKw(),
                    suburb.postcodeResInstalls(), suburb.postcodeDomesticAccounts(),
                    suburb.pvDensityPct(), suburb.domesticMwhPerDwelling(),
                    suburb.solarScore(), suburb.growthScore(), suburb.consumptionScore(),
                    suburb.equityScore(), place, suburb.ranked(), suburb.hotspot(),
                    action, reason, suburb.lat(), suburb.lng()));
        }
        return List.copyOf(finished);
    }

    // --- postcode totals ------------------------------------------------------

    /** Everything the two postcode-level terms are built from. */
    private record Postcode(
            String postcode,
            int residentialInstalls,
            int domesticAccounts,
            /** Null where the LGA only covers part of the postcode. */
            Double pvDensityPct,
            double mwhPerDwelling) {}

    private Map<String, Postcode> postcodeTotals(
            List<SolarRow> solar, Map<String, ConsumptionRow> consumption) {

        Map<String, Integer> installsByPostcode = new LinkedHashMap<>();
        for (SolarRow row : solar) {
            String postcode = seed.postcodeOf(row.locality());
            if (postcode == null) {
                continue;
            }
            installsByPostcode.merge(postcode, orZero(row.resInstallsAlltime()), Integer::sum);
        }

        Map<String, Postcode> totals = new LinkedHashMap<>();
        installsByPostcode.forEach((postcode, installs) -> {
            ConsumptionRow load = consumption.get(postcode);
            if (load == null || load.domesticAccounts() <= 0) {
                return;
            }
            Double density = PARTIAL_POSTCODES.contains(postcode)
                    ? null
                    : 100.0 * installs / load.domesticAccounts();
            totals.put(postcode, new Postcode(
                    postcode,
                    installs,
                    load.domesticAccounts(),
                    density,
                    load.domesticMwh() / load.domesticAccounts()));
        });
        return totals;
    }

    /** Installations per 100 domestic accounts across every usable postcode. */
    private static double lgaPvDensity(Map<String, Postcode> postcodes) {
        long installs = 0;
        long accounts = 0;
        for (Postcode postcode : postcodes.values()) {
            if (postcode.pvDensityPct() == null) {
                continue;
            }
            installs += postcode.residentialInstalls();
            accounts += postcode.domesticAccounts();
        }
        return accounts == 0 ? 0 : 100.0 * installs / accounts;
    }

    /** Average system size, in kW - a genuinely per-suburb figure. */
    private static Double avgSystemKw(SolarRow row) {
        int installs = orZero(row.resInstallsAlltime());
        if (installs == 0) {
            return null;
        }
        return round(orZero(row.resKwAlltime()) / installs, 2);
    }

    // --- normalising ----------------------------------------------------------

    /** Min and max of one term across the localities being ranked. */
    private record Range(double min, double max) {

        static Range over(List<String> names, Map<String, Double> values) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (String name : names) {
                Double value = values.get(name);
                if (value == null) {
                    continue;
                }
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
            return min > max ? new Range(0, 0) : new Range(min, max);
        }

        /** 0 at the worst locality, 1 at the best. Flat ranges land in the middle. */
        double normalise(Double value) {
            if (value == null || max <= min) {
                return 0.5;
            }
            return Math.max(0, Math.min(1, (value - min) / (max - min)));
        }
    }

    private static double median(List<String> names, Map<String, Double> values) {
        List<Double> sorted = names.stream()
                .map(values::get)
                .filter(Objects::nonNull)
                .sorted()
                .toList();
        return sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
    }

    /** Linear-interpolated quantile of an already sorted list. */
    private static double quantile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double position = q * (sorted.size() - 1);
        int low = (int) Math.floor(position);
        int high = (int) Math.ceil(position);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (position - low);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static double orZero(Double value) {
        return value == null ? 0 : value;
    }

    private static Double round(Double value, int places) {
        if (value == null) {
            return null;
        }
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}
