package com.mirco_grid.backend.controller;

import com.mirco_grid.backend.service.council.CouncilApi.ConsumptionReport;
import com.mirco_grid.backend.service.council.CouncilApi.EquityMap;
import com.mirco_grid.backend.service.council.CouncilApi.GridStress;
import com.mirco_grid.backend.service.council.CouncilApi.Rebates;
import com.mirco_grid.backend.service.council.CouncilApi.SourceNote;
import com.mirco_grid.backend.service.council.CouncilApi.Suburb;
import com.mirco_grid.backend.service.council.CouncilApi.SuburbDetail;
import com.mirco_grid.backend.service.council.CouncilApi.SuburbLeague;
import com.mirco_grid.backend.service.council.CouncilApi.Summary;
import com.mirco_grid.backend.service.council.CouncilApi.Trend;
import com.mirco_grid.backend.service.council.CouncilDashboardService;
import com.mirco_grid.backend.service.council.GridStressService;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The council dashboard's data.
 *
 * <p>Everything under this path needs the bearer token from
 * {@code POST /api/auth/login} - see {@code CouncilAuthInterceptor}. The
 * numbers come from the Energy Equity Challenge spreadsheets, loaded into
 * SQLite on startup, joined and ranked in the services behind this; the only
 * live endpoint is {@code /grid-stress}, which reads the same grid model the
 * public map draws.
 */
@RestController
@RequestMapping("/api/council")
public class CouncilController {

    private static final ZoneId ILLAWARRA = ZoneId.of("Australia/Sydney");

    private final CouncilDashboardService dashboard;
    private final GridStressService gridStress;

    public CouncilController(CouncilDashboardService dashboard, GridStressService gridStress) {
        this.dashboard = dashboard;
        this.gridStress = gridStress;
    }

    /** The KPI strip: installations, PV density, savings, CO2, bills, rebate gap. */
    @GetMapping("/summary")
    public Summary summary() {
        return dashboard.summary();
    }

    /** The league table: every locality with its equity score and hotspot flag. */
    @GetMapping("/suburbs")
    public SuburbLeague suburbs() {
        return dashboard.suburbs();
    }

    /** One locality, with the consumption of the postcode it sits in. */
    @GetMapping("/suburbs/{name}")
    public SuburbDetail suburb(@PathVariable String name) {
        SuburbDetail detail = dashboard.suburb(name);
        if (detail == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No locality called \"" + name + "\" in the LGA data.");
        }
        return detail;
    }

    /** Only the localities the equity index flags, worst first. */
    @GetMapping("/hotspots")
    public List<Suburb> hotspots() {
        return dashboard.hotspots();
    }

    /** Capacity and installations per month, Jan 2001 to Dec 2025. */
    @GetMapping("/trend")
    public Trend trend() {
        return dashboard.trend();
    }

    /** MWh by postcode, split domestic, commercial and industrial. */
    @GetMapping("/consumption")
    public ConsumptionReport consumption() {
        return dashboard.consumption();
    }

    /** Rebate programs over six years, and the take-up gap. */
    @GetMapping("/rebates")
    public Rebates rebates() {
        return dashboard.rebates();
    }

    /** Locality, point, equity score and the layers the map can colour by. */
    @GetMapping("/equity-map")
    public EquityMap equityMap() {
        return dashboard.equityMap();
    }

    /** What the network is doing right now, suburb by suburb. */
    @GetMapping("/grid-stress")
    public GridStress gridStress() {
        return gridStress.report(ZonedDateTime.now(ILLAWARRA));
    }

    /** Every file and model behind the dashboard, for the provenance footer. */
    @GetMapping("/sources")
    public List<SourceNote> sources() {
        return dashboard.sources();
    }
}
