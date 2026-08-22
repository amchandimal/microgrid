package com.mirco_grid.backend.service;

import com.mirco_grid.backend.service.council.SuburbSeed;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Puts Wollongong's real rooftop fleet onto the map's areas.
 *
 * <p>The two "Total System" exports say how many systems the LGA has and how
 * many kilowatts they add up to - 27,773 systems and 189,737 kW between
 * January 2001 and December 2025 - but they are a single monthly total for the
 * whole LGA, with nothing in them about where any of it is. The grid model
 * knows where supply and demand sit but has no idea how many real systems are
 * behind them. This joins the two: the real totals are shared out across the
 * map's areas in proportion to what the grid model already says about each.
 *
 * <p>Households follow supply and commercial follows demand, because that is
 * what each one is: a rooftop system is generation, so residential systems go
 * where the field carries supply; a commercial system sits on a business, and
 * businesses are where the field carries load. Power stations follow supply
 * for the same reason as households.
 *
 * <p>Nothing here writes back to the grid model. The supply and demand figures
 * are read from the very cells {@link GridService#cellsForView} hands the map
 * and used as weights; not one of them is altered, and the overlay looks
 * exactly the same as it did before this class existed.
 *
 * <p>The result is written to SQLite on startup and read back from there, so
 * no request ever touches a CSV.
 */
@Service
@Order(1)
public class GridAllocationService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(GridAllocationService.class);

    /**
     * Cell size the field is read at - 400 m.
     *
     * <p>Fine enough that a suburb's neighbourhood is around ninety cells
     * rather than a handful. The region does not fit in one pass at this
     * resolution, so it is read in quadrants - see {@link #regionCells()}.
     */
    private static final double ALLOCATION_ZOOM = 13;

    /**
     * Tiles per side.
     *
     * <p>The region is 115 km of coast now, so it is read in sixteen passes
     * rather than four - each one has to stay well inside {@link
     * GridService#MAX_CELLS} or the shares would be computed from a partial
     * field. The southern tiles cost almost nothing: nothing is surveyed there
     * yet, so no cells come back.
     */
    private static final int TILES = 4;

    private final GridService gridService;
    private final SuburbSeed seed;
    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public GridAllocationService(
            GridService gridService, SuburbSeed seed, JdbcClient jdbc, JdbcTemplate template) {
        this.gridService = gridService;
        this.seed = seed;
        this.jdbc = jdbc;
        this.template = template;
    }

    // --- the wire shapes -----------------------------------------------------

    /** The LGA's fleet, and where this puts it. */
    public record GridInstallations(
            String period, Totals totals, List<Area> areas, String method, List<String> sources) {}

    /** Straight out of the two monthly series, summed. */
    public record Totals(
            int residentialInstalls,
            int commercialInstalls,
            int powerStationInstalls,
            int totalInstalls,
            double residentialKw,
            double commercialKw,
            double powerStationKw,
            double totalKw,
            double avgResidentialSystemKw,
            int areaCount) {}

    /** One area's share of the field, and the systems that share earns it. */
    public record Area(
            String id,
            String name,
            String postcode,
            double lat,
            double lng,
            int cellCount,
            double supplyKw,
            double demandKw,
            double supplySharePct,
            double demandSharePct,
            int residentialInstalls,
            int commercialInstalls,
            int powerStationInstalls,
            double residentialKw,
            double commercialKw,
            double powerStationKw) {}

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS grid_area_allocation (
              area_id                TEXT PRIMARY KEY,
              name                   TEXT NOT NULL,
              postcode               TEXT,
              lat                    REAL NOT NULL,
              lng                    REAL NOT NULL,
              cell_count             INTEGER,
              supply_kw              REAL,
              demand_kw              REAL,
              supply_share           REAL,
              demand_share           REAL,
              residential_installs   INTEGER,
              commercial_installs    INTEGER,
              power_station_installs INTEGER,
              residential_kw         REAL,
              commercial_kw          REAL,
              power_station_kw       REAL
            )""";

    // --- startup -------------------------------------------------------------

    /**
     * Rebuilt on every start rather than only when empty.
     *
     * <p>Unlike the loaded spreadsheets, this table is derived from code - the
     * seeded localities and the grid model - so a change to either has to show
     * up without anyone remembering to delete the database. It costs one pass
     * over the region, which is milliseconds.
     */
    @Override
    public void run(String... args) {
        jdbc.sql(CREATE_TABLE).update();

        List<Area> areas = allocate();
        template.update("DELETE FROM grid_area_allocation");

        List<Object[]> rows = new ArrayList<>();
        for (Area area : areas) {
            rows.add(new Object[] {
                area.id(), area.name(), area.postcode(), area.lat(), area.lng(),
                area.cellCount(), area.supplyKw(), area.demandKw(),
                area.supplySharePct(), area.demandSharePct(),
                area.residentialInstalls(), area.commercialInstalls(),
                area.powerStationInstalls(),
                area.residentialKw(), area.commercialKw(), area.powerStationKw(),
            });
        }
        template.batchUpdate("""
                INSERT INTO grid_area_allocation (
                  area_id, name, postcode, lat, lng, cell_count,
                  supply_kw, demand_kw, supply_share, demand_share,
                  residential_installs, commercial_installs, power_station_installs,
                  residential_kw, commercial_kw, power_station_kw
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);

        log.info("Grid allocation: {} systems spread over {} areas",
                areas.stream().mapToInt(Area::residentialInstalls).sum()
                        + areas.stream().mapToInt(Area::commercialInstalls).sum()
                        + areas.stream().mapToInt(Area::powerStationInstalls).sum(),
                areas.size());
    }

    // --- reads ---------------------------------------------------------------

    /** Everything the public dashboard shows, straight out of SQLite. */
    public GridInstallations installations() {
        Totals totals = totals();
        return new GridInstallations(
                period(),
                totals,
                areas(),
                "Totals come from the two Total System exports, which report the LGA as a"
                        + " single monthly figure. They are shared across areas in proportion"
                        + " to what the grid model already carries: households and power"
                        + " stations follow each area's share of supply, commercial follows"
                        + " its share of demand. Whole systems are apportioned by largest"
                        + " remainder, so the areas add back up to the LGA total exactly."
                        + " No supply or demand value is changed by any of this.",
                List.of(
                        "Total System Installations - Wollongong - All Time.csv",
                        "Total System Capacity - Wollongong - All Time.csv",
                        "Micro-Grid GridService, the field the hex overlay is drawn from"));
    }

    public Totals totals() {
        InstallTotals installs = jdbc.sql("""
                SELECT COALESCE(SUM(residential), 0)    AS residential,
                       COALESCE(SUM(commercial), 0)     AS commercial,
                       COALESCE(SUM(power_stations), 0) AS power_stations
                  FROM monthly_installations""")
                .query((rs, rowNum) -> new InstallTotals(
                        rs.getInt("residential"),
                        rs.getInt("commercial"),
                        rs.getInt("power_stations")))
                .single();

        CapacityTotals capacity = jdbc.sql("""
                SELECT COALESCE(SUM(residential), 0)    AS residential,
                       COALESCE(SUM(commercial), 0)     AS commercial,
                       COALESCE(SUM(power_stations), 0) AS power_stations
                  FROM monthly_capacity""")
                .query((rs, rowNum) -> new CapacityTotals(
                        rs.getDouble("residential"),
                        rs.getDouble("commercial"),
                        rs.getDouble("power_stations")))
                .single();

        Integer areaCount = jdbc.sql("SELECT COUNT(*) FROM grid_area_allocation")
                .query(Integer.class)
                .single();

        return new Totals(
                installs.residential(),
                installs.commercial(),
                installs.powerStations(),
                installs.residential() + installs.commercial() + installs.powerStations(),
                round(capacity.residential(), 1),
                round(capacity.commercial(), 1),
                round(capacity.powerStations(), 1),
                round(capacity.residential() + capacity.commercial() + capacity.powerStations(), 1),
                installs.residential() == 0
                        ? 0
                        : round(capacity.residential() / installs.residential(), 2),
                areaCount == null ? 0 : areaCount);
    }

    /** Areas, busiest first. */
    public List<Area> areas() {
        return jdbc.sql("""
                SELECT area_id, name, postcode, lat, lng, cell_count,
                       supply_kw, demand_kw, supply_share, demand_share,
                       residential_installs, commercial_installs, power_station_installs,
                       residential_kw, commercial_kw, power_station_kw
                  FROM grid_area_allocation
                 ORDER BY residential_installs DESC, name""")
                .query((rs, rowNum) -> new Area(
                        rs.getString("area_id"),
                        rs.getString("name"),
                        rs.getString("postcode"),
                        rs.getDouble("lat"),
                        rs.getDouble("lng"),
                        rs.getInt("cell_count"),
                        rs.getDouble("supply_kw"),
                        rs.getDouble("demand_kw"),
                        rs.getDouble("supply_share"),
                        rs.getDouble("demand_share"),
                        rs.getInt("residential_installs"),
                        rs.getInt("commercial_installs"),
                        rs.getInt("power_station_installs"),
                        rs.getDouble("residential_kw"),
                        rs.getDouble("commercial_kw"),
                        rs.getDouble("power_station_kw")))
                .list();
    }

    private String period() {
        return jdbc.sql("SELECT MIN(month) || ' to ' || MAX(month) FROM monthly_installations")
                .query(String.class)
                .optional()
                .orElse("");
    }

    private record InstallTotals(int residential, int commercial, int powerStations) {}

    private record CapacityTotals(double residential, double commercial, double powerStations) {}

    // --- the allocation ------------------------------------------------------

    /** An area under construction: its cells summed, before any apportioning. */
    private static final class Bucket {
        final SuburbSeed.Locality place;
        double supplyKw;
        double demandKw;
        int cellCount;

        Bucket(SuburbSeed.Locality place) {
            this.place = place;
        }
    }

    private List<Area> allocate() {
        List<Bucket> buckets = seed.all().stream()
                .filter(SuburbSeed.Locality::hasPoint)
                .map(Bucket::new)
                .toList();
        if (buckets.isEmpty()) {
            return List.of();
        }

        // Each area is the field within a fixed radius of its centre - the same
        // radius for every one of them, which is the whole point.
        //
        // Handing each cell to the single nearest centre instead was tried and
        // is worse: the seeded centres are close together through the suburbs
        // and far apart out west, so that hands a paddock ten times the ground
        // of a city block and Marshall Mount ends up with more households than
        // Wollongong. A fixed radius cannot be gamed by how far apart the
        // centres happen to sit. Neighbouring suburbs do share ground, so a
        // suburb in a tight cluster counts the same cells its neighbours do -
        // which is the opposite bias, and the right one: what that measures is
        // how densely built the place is, not how much land it was given.
        for (GridCell cell : regionCells()) {
            for (Bucket bucket : buckets) {
                double distance = groundDistanceM(
                        cell.lat(), cell.lng(), bucket.place.lat(), bucket.place.lng());
                if (distance > GridService.NEIGHBOURHOOD_RADIUS_M) {
                    continue;
                }
                bucket.supplyKw += cell.supplyKw();
                bucket.demandKw += cell.demandKw();
                bucket.cellCount++;
            }
        }

        double totalSupply = buckets.stream().mapToDouble(b -> b.supplyKw).sum();
        double totalDemand = buckets.stream().mapToDouble(b -> b.demandKw).sum();

        double[] supplyShares = buckets.stream()
                .mapToDouble(b -> totalSupply <= 0 ? 0 : b.supplyKw / totalSupply)
                .toArray();
        double[] demandShares = buckets.stream()
                .mapToDouble(b -> totalDemand <= 0 ? 0 : b.demandKw / totalDemand)
                .toArray();

        Totals totals = totals();
        int[] residential = apportion(totals.residentialInstalls(), supplyShares);
        int[] powerStations = apportion(totals.powerStationInstalls(), supplyShares);
        int[] commercial = apportion(totals.commercialInstalls(), demandShares);

        List<Area> areas = new ArrayList<>(buckets.size());
        for (int i = 0; i < buckets.size(); i++) {
            Bucket bucket = buckets.get(i);
            areas.add(new Area(
                    slug(bucket.place.name()),
                    bucket.place.name(),
                    bucket.place.postcode(),
                    bucket.place.lat(),
                    bucket.place.lng(),
                    bucket.cellCount,
                    round(bucket.supplyKw, 1),
                    round(bucket.demandKw, 1),
                    round(100 * supplyShares[i], 3),
                    round(100 * demandShares[i], 3),
                    residential[i],
                    commercial[i],
                    powerStations[i],
                    round(totals.residentialKw() * supplyShares[i], 1),
                    round(totals.commercialKw() * demandShares[i], 1),
                    round(totals.powerStationKw() * supplyShares[i], 1)));
        }
        return areas;
    }

    /**
     * The whole region's cells, read in quadrants and stitched back together.
     *
     * <p>{@link GridService#cellsForView} caps one response at
     * {@link GridService#MAX_CELLS}, which 400 m cells across the Illawarra
     * would blow through. It also pads its box by a row and a column, so the
     * quadrants overlap at their seams; cell ids are stable for a given
     * resolution, so the map keyed on them drops the duplicates.
     */
    private List<GridCell> regionCells() {
        Map<String, GridCell> byId = new LinkedHashMap<>();
        double latStep = (IllawarraRegion.NORTH - IllawarraRegion.SOUTH) / TILES;
        double lngStep = (IllawarraRegion.EAST - IllawarraRegion.WEST) / TILES;

        for (int row = 0; row < TILES; row++) {
            for (int col = 0; col < TILES; col++) {
                GridService.GridCells page = gridService.cellsForView(
                        IllawarraRegion.SOUTH + row * latStep,
                        IllawarraRegion.WEST + col * lngStep,
                        IllawarraRegion.SOUTH + (row + 1) * latStep,
                        IllawarraRegion.WEST + (col + 1) * lngStep,
                        ALLOCATION_ZOOM);
                if (page.truncated()) {
                    log.warn("Grid allocation: quadrant {},{} hit the cell cap at zoom {} -"
                            + " shares are computed from a partial field", row, col,
                            ALLOCATION_ZOOM);
                }
                page.cells().forEach(cell -> byId.putIfAbsent(cell.id(), cell));
            }
        }
        return List.copyOf(byId.values());
    }

    /**
     * Whole systems shared out by share, adding up to the total exactly.
     *
     * <p>Largest remainder: everyone gets their floor, and the systems left
     * over go one each to whoever was rounded down hardest. Rounding each area
     * on its own would lose or invent a few dozen systems across sixty-odd
     * areas, and a dashboard whose parts do not sum to its headline is a
     * dashboard nobody trusts twice.
     */
    static int[] apportion(int total, double[] shares) {
        int n = shares.length;
        int[] whole = new int[n];
        if (n == 0 || total <= 0) {
            return whole;
        }

        // Normalise first, so the result cannot depend on whether the caller
        // handed over shares that happened to sum to one. A field with no
        // supply anywhere has no basis for an allocation and gets none, rather
        // than having systems sprinkled over it at random.
        double sum = 0;
        for (double share : shares) {
            sum += Math.max(0, share);
        }
        if (sum <= 0) {
            return whole;
        }

        double[] remainder = new double[n];
        int assigned = 0;
        for (int i = 0; i < n; i++) {
            double exact = total * Math.max(0, shares[i]) / sum;
            whole[i] = (int) Math.floor(exact);
            remainder[i] = exact - whole[i];
            assigned += whole[i];
        }

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> -remainder[i]));

        // Every remainder is below 1, so there are fewer left over than areas -
        // except where the shares do not sum to 1 at all, which the min() guards.
        int leftOver = Math.min(total - assigned, n);
        for (int k = 0; k < leftOver; k++) {
            whole[order[k]]++;
        }
        return whole;
    }

    private static String slug(String name) {
        return name.toLowerCase().replace(' ', '-');
    }

    /** Flat-earth metres; over a few kilometres in the Illawarra that is exact enough. */
    private static double groundDistanceM(double lat1, double lng1, double lat2, double lng2) {
        double dLat = (lat1 - lat2) * 110950;
        double dLng = (lng1 - lng2) * 111320 * Math.cos(Math.toRadians(lat2));
        return Math.hypot(dLat, dLng);
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}
