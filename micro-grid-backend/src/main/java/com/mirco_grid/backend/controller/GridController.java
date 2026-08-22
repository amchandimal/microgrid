package com.mirco_grid.backend.controller;

import com.mirco_grid.backend.service.GridService;
import com.mirco_grid.backend.service.GridSite;
import com.mirco_grid.backend.service.IllawarraRegion;
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

    private final GridService gridService;

    public GridController(GridService gridService) {
        this.gridService = gridService;
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
            double referenceLat) {}

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
                GridService.REFERENCE_LAT);
    }

    @GetMapping("/sites")
    public List<GridSite> sites() {
        return gridService.sites();
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
