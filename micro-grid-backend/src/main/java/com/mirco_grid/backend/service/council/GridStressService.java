package com.mirco_grid.backend.service.council;

import com.mirco_grid.backend.service.GridService;
import com.mirco_grid.backend.service.GridStatus;
import com.mirco_grid.backend.service.council.CouncilApi.GridStress;
import com.mirco_grid.backend.service.council.CouncilApi.GridStressArea;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The live half of the council dashboard.
 *
 * <p>Everything else on the dashboard is a spreadsheet from last financial
 * year. This is the network as it stands right now, read out of the same
 * {@link GridService} model the public map draws - so a suburb that the equity
 * index says is underserved can be checked against whether its feeders are
 * actually under pressure this evening.
 *
 * <p>Reported per suburb rather than per feeder, because that is the unit the
 * rest of the dashboard uses. Each suburb is sampled at its seeded centre, and
 * the share of the day it spends in peak comes from shaping that one sample
 * across twenty-four hours rather than re-walking the field for each.
 */
@Service
public class GridStressService {

    private final GridService gridService;
    private final SuburbSeed seed;
    private final EquityIndexService equityIndex;

    public GridStressService(
            GridService gridService, SuburbSeed seed, EquityIndexService equityIndex) {
        this.gridService = gridService;
        this.seed = seed;
        this.equityIndex = equityIndex;
    }

    /** Every ranked suburb, worst first. */
    public GridStress report(ZonedDateTime now) {
        List<GridStressArea> areas = new ArrayList<>();
        int peakNow = 0;
        int constrainedNow = 0;

        // The same localities the rest of the dashboard ranks. Reading a
        // national park and a reservoir as "in peak demand" is true and
        // useless - nobody there is paying an evening tariff.
        Set<String> populated = equityIndex.suburbs().stream()
                .filter(suburb -> suburb.ranked())
                .map(suburb -> suburb.locality())
                .collect(Collectors.toSet());

        for (SuburbSeed.Locality place : seed.all()) {
            if (!place.hasPoint() || !populated.contains(place.name())) {
                continue;
            }
            Optional<GridStatus> current =
                    gridService.statusAt(place.lat(), place.lng(), now);
            if (current.isEmpty()) {
                // Bush and reservoir localities have no network to read.
                continue;
            }
            GridStatus status = current.get();
            double pctDayInPeak = pctDayInPeak(place, now);

            if (status.state() == GridStatus.State.PEAK) {
                peakNow++;
            }
            if (status.exportConstrained()) {
                constrainedNow++;
            }

            areas.add(new GridStressArea(
                    place.name().toLowerCase().replace(' ', '-'),
                    place.name(),
                    place.name(),
                    status.lat(),
                    status.lng(),
                    status.state().name(),
                    status.exportConstrained(),
                    pctDayInPeak,
                    status.supplyKw(),
                    status.demandKw(),
                    status.priceSignalCkwh(),
                    status.communityBatterySocPct()));
        }

        // Worst first, and within that biggest first: a suburb drawing 4 MW is
        // a bigger problem than a hamlet drawing 200 kW at the same state.
        areas.sort(Comparator
                .comparing((GridStressArea a) -> !"PEAK".equals(a.state()))
                .thenComparing(a -> -a.pctDayInPeak())
                .thenComparing(a -> -a.demandKw()));

        boolean afterDark = areas.size() > 0 && peakNow == areas.size();

        return new GridStress(
                now.toOffsetDateTime(),
                List.copyOf(areas),
                peakNow,
                constrainedNow,
                "Supply, demand and export headroom modelled by the Micro-Grid grid"
                        + " service - the same field the public hex map is drawn from -"
                        + " sampled within 2 km of each suburb centre."
                        + (afterDark
                                ? " Every suburb reads as peak right now because there is no"
                                        + " rooftop generation after dark; the share of the day"
                                        + " in peak is the figure to compare suburbs on."
                                : ""));
    }

    /** Suburbs whose network is in peak right now, keyed by locality. */
    public Map<String, GridStressArea> byLocality(ZonedDateTime now) {
        Map<String, GridStressArea> byName = new LinkedHashMap<>();
        for (GridStressArea area : report(now).areas()) {
            byName.put(area.name(), area);
        }
        return byName;
    }

    /** Suburbs the equity index flags, annotated with what the grid is doing. */
    public List<GridStressArea> hotspotAreas(ZonedDateTime now) {
        Map<String, GridStressArea> areas = byLocality(now);
        return equityIndex.hotspots().stream()
                .map(hotspot -> areas.get(hotspot.locality()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * How much of the day this suburb spends short of power, as a percentage.
     *
     * <p>One sample of the field shaped across every hour: the solar and load
     * curves are multipliers on a fixed neighbourhood, so the whole day comes
     * out of a single walk of the hexagons.
     */
    private double pctDayInPeak(SuburbSeed.Locality place, ZonedDateTime now) {
        List<GridStatus> day = gridService.dayAt(place.lat(), place.lng(), now).orElse(List.of());
        if (day.isEmpty()) {
            return 0;
        }
        long peakHours = day.stream()
                .filter(hour -> hour.state() == GridStatus.State.PEAK)
                .count();
        return Math.round(1000.0 * peakHours / day.size()) / 10.0;
    }
}
