package com.mirco_grid.backend.service.council;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirco_grid.backend.service.council.CouncilApi.Suburb;
import com.mirco_grid.backend.service.council.CouncilDataRepository.ConsumptionRow;
import com.mirco_grid.backend.service.council.CouncilDataRepository.CurrentSolarRow;
import com.mirco_grid.backend.service.council.CouncilDataRepository.SolarRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The ranking, on a fixture small enough to work out by hand.
 *
 * <p>Real localities and their real postcodes, so the seed does the joining it
 * does in production, but invented installation counts - the point is the
 * arithmetic and the guards, not the Wollongong numbers, which
 * {@link EnergyDataReadersTest} covers.
 */
class EquityIndexServiceTest {

    private final SuburbSeed seed = new SuburbSeed();

    /**
     * Postcode 2530 twice as dense as 2500, and using more per dwelling.
     *
     * <p>2530: 900 installations over 1,000 accounts = 90 per 100 homes.
     * 2500: 200 over 1,000 = 20 per 100 homes.
     */
    private EquityIndexService serviceWithFixture() {
        List<SolarRow> solar = List.of(
                solar("Horsley", 600, 3600, 12.0),
                solar("Dapto", 300, 1800, 6.0),
                solar("Wollongong", 120, 700, 8.0),
                solar("Coniston", 80, 460, 4.0),
                // Below the ranking floor: a bush block riding its postcode.
                solar("Maddens Plains", 4, 20, null),
                // Below the growth floor: 40% off a base of thirty is noise.
                solar("Otford", 30, 170, 40.0),
                // Partly-Shellharbour postcode: no density can be computed.
                solar("Windang", 300, 1700, 7.0),
                // The unattributed aggregate row, which is not a place.
                solar(SuburbSolarCsvReader.UNATTRIBUTED_ROW, 500, 3000, 9.0));

        Map<String, ConsumptionRow> consumption = new LinkedHashMap<>();
        consumption.put("2530", consumption("2530", 5000, 1000));
        consumption.put("2500", consumption("2500", 3000, 1000));
        consumption.put("2508", consumption("2508", 6000, 1000));
        consumption.put("2528", consumption("2528", 4000, 1000));

        return new EquityIndexService(new StubRepository(solar, consumption), seed);
    }

    // --- density -------------------------------------------------------------

    @Test
    void computesDensityOverThePostcodesAccountsNotTheSuburbs() {
        List<Suburb> suburbs = serviceWithFixture().suburbs();

        // 600 + 300 in 2530, over 1,000 domestic accounts.
        assertThat(find(suburbs, "Horsley").pvDensityPct()).isEqualTo(90.0);
        assertThat(find(suburbs, "Dapto").pvDensityPct()).isEqualTo(90.0);
        // 120 + 80 in 2500.
        assertThat(find(suburbs, "Wollongong").pvDensityPct()).isEqualTo(20.0);
        assertThat(find(suburbs, "Coniston").pvDensityPct()).isEqualTo(20.0);
    }

    @Test
    void givesEverySuburbInAPostcodeTheSameConsumptionPerDwelling() {
        List<Suburb> suburbs = serviceWithFixture().suburbs();

        assertThat(find(suburbs, "Horsley").domesticMwhPerDwelling()).isEqualTo(5.0);
        assertThat(find(suburbs, "Dapto").domesticMwhPerDwelling()).isEqualTo(5.0);
        assertThat(find(suburbs, "Wollongong").domesticMwhPerDwelling()).isEqualTo(3.0);
    }

    // --- the score -----------------------------------------------------------

    @Test
    void ranksTheDenserPostcodeAboveTheSparserOne() {
        List<Suburb> suburbs = serviceWithFixture().suburbs();

        assertThat(find(suburbs, "Horsley").equityScore())
                .isGreaterThan(find(suburbs, "Wollongong").equityScore());
        assertThat(find(suburbs, "Horsley").rank()).isEqualTo(1);
    }

    @Test
    void separatesSuburbsInsideAPostcodeByTheirOwnGrowth() {
        List<Suburb> suburbs = serviceWithFixture().suburbs();

        // Same density and consumption; Horsley grew faster than Dapto.
        assertThat(find(suburbs, "Horsley").solarScore())
                .isEqualTo(find(suburbs, "Dapto").solarScore());
        assertThat(find(suburbs, "Horsley").growthScore())
                .isGreaterThan(find(suburbs, "Dapto").growthScore());
        assertThat(find(suburbs, "Horsley").equityScore())
                .isGreaterThan(find(suburbs, "Dapto").equityScore());
    }

    @Test
    void weightsTheThreeTermsAsThePublishedFormulaSays() {
        Suburb horsley = find(serviceWithFixture().suburbs(), "Horsley");

        // Densest postcode and fastest growth, so both of those normalise to 1.
        assertThat(horsley.solarScore()).isEqualTo(1.0);
        assertThat(horsley.growthScore()).isEqualTo(1.0);
        // Consumption runs 3.0 (2500) to 6.0 (2508); Horsley's 5.0 is two
        // thirds of the way up, and the term is inverted.
        assertThat(horsley.consumptionScore()).isEqualTo(0.667);

        // 100 x (0.5 x 1 + 0.2 x 1 + 0.3 x (1 - 2/3)) = 80.
        assertThat(horsley.equityScore()).isEqualTo(80.0);
    }

    @Test
    void normalisesEachTermAcrossTheRankedLocalitiesOnly() {
        List<Suburb> suburbs = serviceWithFixture().suburbs();

        // Every term sits inside 0..1 for every ranked locality.
        assertThat(suburbs.stream().filter(Suburb::ranked)).allSatisfy(suburb -> {
            assertThat(suburb.solarScore()).isBetween(0.0, 1.0);
            assertThat(suburb.growthScore()).isBetween(0.0, 1.0);
            assertThat(suburb.consumptionScore()).isBetween(0.0, 1.0);
            assertThat(suburb.equityScore()).isBetween(0.0, 100.0);
        });

        // The sparsest ranked postcode bottoms its solar term out at zero, and
        // the heaviest-using one tops its consumption term out at one. Both are
        // 2508 in this fixture: 34 installations over 1,000 accounts, 6 MWh a
        // dwelling.
        Suburb sparsest = find(suburbs, "Otford");
        assertThat(sparsest.solarScore()).isZero();
        assertThat(sparsest.consumptionScore()).isEqualTo(1.0);
    }

    @Test
    void invertsTheConsumptionTerm() {
        List<Suburb> suburbs = serviceWithFixture().suburbs();

        Suburb light = find(suburbs, "Wollongong"); // 3 MWh per dwelling
        Suburb heavy = find(suburbs, "Otford"); // 6 MWh per dwelling
        assertThat(light.consumptionScore()).isLessThan(heavy.consumptionScore());
    }

    // --- the guards ----------------------------------------------------------

    @Test
    void leavesTheUnattributedAggregateRowOutEntirely() {
        List<Suburb> suburbs = serviceWithFixture().suburbs();

        assertThat(suburbs)
                .noneMatch(suburb ->
                        suburb.locality().equals(SuburbSolarCsvReader.UNATTRIBUTED_ROW));
    }

    @Test
    void listsButDoesNotRankALocalityWithAlmostNoSolar() {
        List<Suburb> suburbs = serviceWithFixture().suburbs();

        Suburb bush = find(suburbs, "Maddens Plains");
        assertThat(bush.ranked()).isFalse();
        assertThat(bush.equityScore()).isNull();
        assertThat(bush.rank()).isNull();
        assertThat(bush.hotspot()).isFalse();
    }

    @Test
    void ignoresAGrowthRateComputedOffTooSmallABase() {
        List<Suburb> suburbs = serviceWithFixture().suburbs();

        Suburb otford = find(suburbs, "Otford");
        // Its own 40% would be the highest in the fixture and would score 1.
        assertThat(otford.resYoyPct()).isEqualTo(40.0);
        assertThat(otford.growthScore()).isLessThan(1.0);
    }

    @Test
    void doesNotRankASuburbWhosePostcodeIsMostlyAnotherCouncil() {
        List<Suburb> suburbs = serviceWithFixture().suburbs();

        Suburb windang = find(suburbs, "Windang");
        assertThat(windang.ranked()).isFalse();
        assertThat(windang.pvDensityPct()).isNull();
        // The per-dwelling average is still an average, so it survives.
        assertThat(windang.domesticMwhPerDwelling()).isEqualTo(4.0);
    }

    @Test
    void flagsTheBottomQuartileAsHotspots() {
        EquityIndexService service = serviceWithFixture();

        List<Suburb> ranked = service.suburbs().stream().filter(Suburb::ranked).toList();
        assertThat(ranked).hasSize(5);

        // The worst-scoring locality is always in the bottom quartile.
        assertThat(ranked.get(ranked.size() - 1).hotspot()).isTrue();
        assertThat(ranked.get(0).hotspot()).isFalse();
        assertThat(service.hotspots())
                .allSatisfy(hotspot -> {
                    assertThat(hotspot.action()).isNotBlank();
                    assertThat(hotspot.actionReason()).isNotBlank();
                });
    }

    @Test
    void numbersTheRanksContiguouslyOverTheRankedLocalitiesOnly() {
        List<Suburb> ranked = serviceWithFixture().suburbs().stream()
                .filter(Suburb::ranked)
                .toList();

        assertThat(ranked.stream().map(Suburb::rank))
                .containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void publishesTheFormulaItActuallyUsed() {
        var methodology = serviceWithFixture().methodology();

        assertThat(methodology.terms()).hasSize(3);
        assertThat(methodology.terms().stream().mapToDouble(CouncilApi.Term::weight).sum())
                .isEqualTo(1.0);
        assertThat(methodology.formula()).contains("0.5", "0.2", "0.3");
        assertThat(methodology.notes()).isNotEmpty();
    }

    @Test
    void findsALocalityWhateverTheCallersCasing() {
        EquityIndexService service = serviceWithFixture();

        assertThat(service.find("horsley")).isNotNull();
        assertThat(service.find("  HORSLEY  ")).isNotNull();
        assertThat(service.find("Nowhere")).isNull();
        assertThat(service.find(null)).isNull();
    }

    // --- fixture helpers -----------------------------------------------------

    private static Suburb find(List<Suburb> suburbs, String locality) {
        return suburbs.stream()
                .filter(suburb -> suburb.locality().equals(locality))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No suburb " + locality));
    }

    private static SolarRow solar(String locality, int installs, double kw, Double yoy) {
        return new SolarRow(
                locality, installs, kw, 0, 0.0, yoy, 0, 0.0, 0, 0.0, null, 0, 0.0);
    }

    private static ConsumptionRow consumption(String postcode, double domesticMwh, int accounts) {
        return new ConsumptionRow(
                postcode, domesticMwh * 2, domesticMwh, domesticMwh, 0, 0, domesticMwh,
                accounts + 500, accounts);
    }

    /** Stands in for the SQLite store; the schema is not what is under test. */
    private static final class StubRepository extends CouncilDataRepository {

        private final List<SolarRow> solar;
        private final Map<String, ConsumptionRow> consumption;

        StubRepository(List<SolarRow> solar, Map<String, ConsumptionRow> consumption) {
            super(null);
            this.solar = solar;
            this.consumption = consumption;
        }

        @Override
        public List<SolarRow> suburbSolar() {
            return solar;
        }

        @Override
        public Map<String, CurrentSolarRow> suburbSolarCurrentByLocality() {
            return Map.of();
        }

        @Override
        public Map<String, ConsumptionRow> consumptionByPostcode() {
            return consumption;
        }
    }
}
