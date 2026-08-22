package com.mirco_grid.backend.controller;

import com.mirco_grid.backend.service.GridAllocationService;
import com.mirco_grid.backend.service.GridService;
import com.mirco_grid.backend.service.GridStatus;
import com.mirco_grid.backend.service.GridSite;
import com.mirco_grid.backend.service.IllawarraRegion;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** The map's data source: what the region is, what is on it, and the overlay. */
@RestController
@RequestMapping("/api/grid")
public class GridController {

    private static final ZoneId ILLAWARRA = ZoneId.of("Australia/Sydney");

    private final GridService gridService;
    private final GridAllocationService allocation;

    public GridController(GridService gridService, GridAllocationService allocation) {
        this.gridService = gridService;
        this.allocation = allocation;
    }

    /**
     * Wollongong's real rooftop fleet, and where the model puts it.
     *
     * <p>Read straight out of SQLite - the two Total System exports were parsed
     * into it at startup and the areas were apportioned then, so this touches
     * no file and rebuilds no grid.
     */
    @GetMapping("/installations")
    public GridAllocationService.GridInstallations installations() {
        return allocation.installations();
    }

    /** Extent and resolution limits, so the client does not hard-code them. */
    public record RegionResponse(
            double[][] bounds,
            double north,
            double south,
            double west,
            double east,
            int finestMetres,
            int finestZoom,
            int maxCells,
            double referenceLat,
            /**
             * The part of {@code bounds} the overlay has data for.
             *
             * <p>The camera covers the whole region; the modelled field covers
             * the surveyed strip. Sent so the map can say which is which
             * instead of leaving the difference looking like a loading failure.
             */
            double[][] surveyedBounds,
            String coverageNote) {}

    @GetMapping("/region")
    public RegionResponse region() {
        return new RegionResponse(
                IllawarraRegion.asBounds(),
                IllawarraRegion.NORTH,
                IllawarraRegion.SOUTH,
                IllawarraRegion.WEST,
                IllawarraRegion.EAST,
                GridService.FINEST_METRES,
                GridService.FINEST_ZOOM,
                GridService.MAX_CELLS,
                GridService.REFERENCE_LAT,
                IllawarraRegion.surveyedBounds(),
                "The supply and demand overlay is modelled from Helensburgh to Kiama."
                        + " The rest of the region is mapped but not yet surveyed.");
    }

    @GetMapping("/sites")
    public List<GridSite> sites() {
        return gridService.sites();
    }

    /**
     * Live supply and demand around one address.
     *
     * <p>The spec keys this on an areaId; the client geocodes what the user
     * typed and sends coordinates instead, so an address anywhere in the
     * region works without a lookup table of area names. The numbers are
     * aggregated out of {@link GridService}, so they track the map.
     */
    @GetMapping("/status")
    public GridStatus status(@RequestParam double lat, @RequestParam double lng) {
        if (!IllawarraRegion.contains(lat, lng)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "That address is outside the Illawarra region.");
        }
        return gridService.statusAt(lat, lng, ZonedDateTime.now(ILLAWARRA))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No network data close enough to that address."));
    }

    /** Neighbourhood batteries the wizard can point people at. */
    @GetMapping("/community-batteries")
    public List<GridStatus.CommunityBattery> communityBatteries() {
        return gridService.communityBatteries();
    }

    /**
     * The overlay for one viewport. Coordinates are the visible box; {@code
     * zoom} picks the cell size.
     */
    @GetMapping("/cells")
    public ResponseEntity<GridService.GridCells> cells(
            @RequestParam double south,
            @RequestParam double west,
            @RequestParam double north,
            @RequestParam double east,
            @RequestParam double zoom) {

        if (north <= south || east <= west) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "north must exceed south and east must exceed west");
        }
        if (zoom < 0 || zoom > 22) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zoom must be 0..22");
        }

        // Clip to the region rather than refusing - the client's viewport is
        // allowed to be larger than the area we cover.
        double s = Math.max(south, IllawarraRegion.SOUTH);
        double w = Math.max(west, IllawarraRegion.WEST);
        double n = Math.min(north, IllawarraRegion.NORTH);
        double e = Math.min(east, IllawarraRegion.EAST);

        if (n <= s || e <= w) {
            return ResponseEntity.ok(new GridService.GridCells(
                    zoom, gridService.cellMetresForZoom(zoom),
                    GridService.REFERENCE_LAT, 0, false, List.of()));
        }
        return ResponseEntity.ok(gridService.cellsForView(s, w, n, e, zoom));
    }
}
