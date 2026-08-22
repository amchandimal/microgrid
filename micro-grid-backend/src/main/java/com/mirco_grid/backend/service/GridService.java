package com.mirco_grid.backend.service;

import com.mirco_grid.backend.service.GridSite.SiteStatus;
import com.mirco_grid.backend.service.GridSite.SiteType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Builds the hexagon overlay for a viewport.
 *
 * <p>The finest cell is 50 m across the flats, but the region is roughly
 * 45 x 61 km: tiling all of it at 50 m would take about two million hexagons,
 * and below zoom 16 a 50 m hexagon is under a pixel wide. So cells are built
 * only for the viewport asked for, and the resolution halves with every zoom
 * level out until it bottoms out at 50 m from zoom 16 in. That keeps roughly a
 * thousand hexagons on screen at any zoom.
 *
 * <p>Cell values follow how built-up the ground is. Generation and headroom
 * rise faster than load through the suburbs, so the overlay greens over the
 * towns and reddens over the escarpment, the national parks and the farmland.
 */
@Service
public class GridService {

    /** Finest cell, flat-to-flat, in metres. */
    public static final int FINEST_METRES = 50;
    /** Zoom at which cells reach {@link #FINEST_METRES}. */
    public static final int FINEST_ZOOM = 16;
    /** Ceiling on one request, so a bad viewport cannot ask for the world. */
    public static final int MAX_CELLS = 9000;

    /**
     * Hexagon width is worked out once at this latitude rather than per
     * viewport, so the lattice is identical for every request and the client
     * can rebuild the same hexagon from a cell centre.
     */
    public static final double REFERENCE_LAT = (IllawarraRegion.NORTH + IllawarraRegion.SOUTH) / 2;

    /**
     * Below this much built-up ground there is no network worth drawing, so no
     * cell is emitted at all. That is what leaves the national parks, the
     * escarpment and the farmland as bare basemap instead of a red wash.
     */
    private static final double MIN_BUILT_UP = 0.04;
    /** Where the ramp crosses from red to green, as a built-up fraction. */
    private static final double BALANCE_MIDPOINT = 0.35;
    /** How sharply it crosses. Gentle, so there is a wide amber shoulder. */
    private static final double BALANCE_GAIN = 2.2;
    /** Total kW per hectare moving through a cell: bush floor, urban range. */
    private static final double BASE_LOAD_KW_PER_HA = 2.0;
    private static final double URBAN_LOAD_KW_PER_HA = 12.0;
    /** How far a supplier tilts its surroundings green, and over what radius. */
    private static final double SUPPLIER_TILT = 0.5;
    private static final double SUPPLIER_SIGMA_M = 2200;

    private static final double EARTH_RADIUS_M = 6378137;

    private final BuiltUpDensity density;

    public GridService(BuiltUpDensity density) {
        this.density = density;
    }

    /** One page of the overlay. */
    public record GridCells(
            double zoom,
            int sizeMetres,
            /** Latitude the hexagon width was computed at; clients need it to draw. */
            double referenceLat,
            int count,
            /** True when {@link #MAX_CELLS} cut the response short. */
            boolean truncated,
            List<GridCell> cells) {}

    /** Ground metres across the flats at a given zoom. */
    public int cellMetresForZoom(double zoom) {
        int steps = (int) Math.max(0, Math.ceil(FINEST_ZOOM - zoom));
        return FINEST_METRES * (1 << Math.min(steps, 20));
    }

    /**
     * Every land cell whose centre falls in the box, at the resolution implied
     * by {@code zoom}. Cells are laid out in the Web Mercator plane so they
     * stay regular on screen.
     */
    public GridCells cellsForView(
            double south, double west, double north, double east, double zoom) {

        int metres = cellMetresForZoom(zoom);
        double width = metres / Math.cos(Math.toRadians(REFERENCE_LAT));
        double radius = width / Math.sqrt(3);
        double rowStep = 1.5 * radius;

        double swX = projectX(west), swY = projectY(south);
        double neX = projectX(east), neY = projectY(north);

        int rMin = (int) Math.floor(swY / rowStep) - 1;
        int rMax = (int) Math.ceil(neY / rowStep) + 1;

        List<GridCell> cells = new ArrayList<>();
        boolean truncated = false;

        outer:
        for (int r = rMin; r <= rMax; r++) {
            double y = r * rowStep;
            double xOffset = (r & 1) == 0 ? 0 : width / 2;
            int qMin = (int) Math.floor((swX - xOffset) / width) - 1;
            int qMax = (int) Math.ceil((neX - xOffset) / width) + 1;
            for (int q = qMin; q <= qMax; q++) {
                if (cells.size() >= MAX_CELLS) {
                    truncated = true;
                    break outer;
                }
                double x = q * width + xOffset;
                double lat = unprojectLat(y);
                double lng = unprojectLng(x);

                // Outside the region, or on water - no cell either way.
                if (!IllawarraRegion.contains(lat, lng)) continue;
                if (IllawarraWater.isWater(lat, lng)) continue;

                double builtUp = density.at(lat, lng);
                if (builtUp < MIN_BUILT_UP) continue;

                cells.add(buildCell(q, r, metres, lat, lng, builtUp, x, y, radius));
            }
        }
        return new GridCells(zoom, metres, REFERENCE_LAT, cells.size(), truncated, cells);
    }

    private GridCell buildCell(
            int q, int r, int metres, double lat, double lng, double builtUp,
            double x, double y, double radius) {
        double areaHectares = (Math.sqrt(3) / 2) * metres * metres / 10_000.0;

        double balance = Math.tanh(
                BALANCE_GAIN * (builtUp - BALANCE_MIDPOINT)
                        + supplierTilt(lat, lng)
                        - loadTilt(lat, lng));
        double totalKw = (BASE_LOAD_KW_PER_HA + URBAN_LOAD_KW_PER_HA * builtUp) * areaHectares;
        double supplyKw = totalKw * (1 + balance) / 2;
        double demandKw = totalKw * (1 - balance) / 2;

        List<String> siteIds = new ArrayList<>();
        for (int i = 0; i < SITES.size(); i++) {
            if (inHexagon(SITE_X[i] - x, SITE_Y[i] - y, radius)) {
                siteIds.add(SITES.get(i).id());
            }
        }

        return new GridCell(
                metres + ":" + q + ":" + r,
                q,
                r,
                metres,
                round(areaHectares, 3),
                round(lat, 6),
                round(lng, 6),
                round(demandKw, 1),
                round(supplyKw, 1),
                round(supplyKw - demandKw, 1),
                round(balance, 3),
                round(builtUp, 3),
                siteIds);
    }

    /**
     * Is an offset from a cell centre inside that hexagon?
     *
     * <p>Pointy-top, circumradius {@code radius}, all in Mercator units - the
     * same units the lattice is laid out in. Testing the inscribed circle
     * instead would drop anything sitting in one of the six corners, which is
     * a tenth of the area.
     */
    static boolean inHexagon(double dx, double dy, double radius) {
        double ax = Math.abs(dx), ay = Math.abs(dy);
        if (ax > Math.sqrt(3) / 2 * radius) return false;
        return ay <= radius - ax / Math.sqrt(3);
    }

    /**
     * A place that draws far more than it makes.
     *
     * <p>The built-up field alone reads every dense suburb as a net exporter,
     * which is wrong wherever the load is industrial rather than residential -
     * plenty of demand, little roof to put panels on. These pull those pockets
     * back to the red end.
     *
     * @param tilt   how hard it pulls at the centre, before the tanh
     * @param sigmaM the radius it pulls over
     */
    private record LoadCentre(String name, double lat, double lng, double tilt, double sigmaM) {}

    private static final List<LoadCentre> LOAD_CENTRES = List.of(
            new LoadCentre("Unanderra industrial", -34.4496, 150.8501, 2.6, 2000));

    /** Heavy loads push their surroundings toward the red end. */
    private static double loadTilt(double lat, double lng) {
        double tilt = 0;
        for (LoadCentre centre : LOAD_CENTRES) {
            double d = groundDistanceM(centre.lat(), centre.lng(), lat, lng);
            double weight = Math.exp(-Math.pow(d / centre.sigmaM(), 2));
            if (weight < 0.002) continue;
            tilt += centre.tilt() * weight;
        }
        return tilt;
    }

    /** Known generators push their surroundings toward the green end. */
    private static double supplierTilt(double lat, double lng) {
        double tilt = 0;
        for (GridSite site : SITES) {
            if (!site.isSupplier() || site.effectiveKw() == 0) continue;
            double d = groundDistanceM(site.lat(), site.lng(), lat, lng);
            double weight = Math.exp(-Math.pow(d / SUPPLIER_SIGMA_M, 2));
            if (weight < 0.002) continue;
            tilt += SUPPLIER_TILT * weight * (site.effectiveKw() / 4200.0);
        }
        return tilt;
    }

    // --- sites ---------------------------------------------------------------

    private static final List<GridSite> SITES = List.of(
            new GridSite("S-01", "Port Kembla Solar Farm", SiteType.SUPPLIER, "Port Kembla",
                    -34.4783, 150.9022, 4200, SiteStatus.ONLINE),
            new GridSite("S-02", "Illawarra Wind Co-op", SiteType.SUPPLIER, "Dapto",
                    -34.4986, 150.7947, 2650, SiteStatus.ONLINE),
            new GridSite("S-03", "Wollongong Rooftop Pool", SiteType.SUPPLIER, "Wollongong",
                    -34.4278, 150.8931, 1875, SiteStatus.ONLINE),
            new GridSite("S-04", "Shellharbour Battery Hub", SiteType.SUPPLIER, "Shellharbour",
                    -34.5806, 150.8697, 3100, SiteStatus.STANDBY),
            new GridSite("S-05", "Kiama Community Energy", SiteType.SUPPLIER, "Kiama",
                    -34.6708, 150.8542, 980, SiteStatus.ONLINE),
            new GridSite("S-06", "Bulli Hydro Micro-Plant", SiteType.SUPPLIER, "Bulli",
                    -34.3383, 150.9161, 640, SiteStatus.OFFLINE),
            new GridSite("H-101", "Fairy Meadow Cluster", SiteType.HOUSEHOLD, "Fairy Meadow",
                    -34.3944, 150.8983, 42, SiteStatus.ONLINE),
            new GridSite("H-102", "Figtree Estate", SiteType.HOUSEHOLD, "Figtree",
                    -34.4383, 150.8536, 65, SiteStatus.ONLINE),
            new GridSite("H-103", "Corrimal Terraces", SiteType.HOUSEHOLD, "Corrimal",
                    -34.3778, 150.9044, 38, SiteStatus.ONLINE),
            new GridSite("H-104", "Albion Park Rail Homes", SiteType.HOUSEHOLD, "Albion Park Rail",
                    -34.5697, 150.7906, 54, SiteStatus.STANDBY),
            new GridSite("H-105", "Thirroul Beachside", SiteType.HOUSEHOLD, "Thirroul",
                    -34.3153, 150.9236, 29, SiteStatus.ONLINE),
            new GridSite("H-106", "Warrawong Village", SiteType.HOUSEHOLD, "Warrawong",
                    -34.4881, 150.8869, 47, SiteStatus.ONLINE),
            new GridSite("H-107", "Helensburgh North", SiteType.HOUSEHOLD, "Helensburgh",
                    -34.1786, 150.9964, 22, SiteStatus.OFFLINE),
            new GridSite("H-108", "Berkeley Green", SiteType.HOUSEHOLD, "Berkeley",
                    -34.4667, 150.8492, 51, SiteStatus.ONLINE));

    /** Site positions in the Mercator plane, so containment is a subtraction. */
    private static final double[] SITE_X = new double[SITES.size()];
    private static final double[] SITE_Y = new double[SITES.size()];

    static {
        for (int i = 0; i < SITES.size(); i++) {
            SITE_X[i] = projectX(SITES.get(i).lng());
            SITE_Y[i] = projectY(SITES.get(i).lat());
        }
    }

    public List<GridSite> sites() {
        return SITES;
    }

    // --- geometry ------------------------------------------------------------

    /** Matches Leaflet's spherical Mercator, so both ends share one lattice. */
    static double projectX(double lng) {
        return EARTH_RADIUS_M * Math.toRadians(lng);
    }

    static double projectY(double lat) {
        double sin = Math.sin(Math.toRadians(lat));
        return EARTH_RADIUS_M * Math.log((1 + sin) / (1 - sin)) / 2;
    }

    static double unprojectLng(double x) {
        return Math.toDegrees(x / EARTH_RADIUS_M);
    }

    static double unprojectLat(double y) {
        return Math.toDegrees(2 * Math.atan(Math.exp(y / EARTH_RADIUS_M)) - Math.PI / 2);
    }

    private static double groundDistanceM(double lat1, double lng1, double lat2, double lng2) {
        double dLat = (lat1 - lat2) * 110950;
        double dLng = (lng1 - lng2) * 111320 * Math.cos(Math.toRadians(lat2));
        return Math.hypot(dLat, dLng);
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        double rounded = Math.round(value * factor) / factor;
        return rounded == 0 ? 0 : rounded; // Math.round can hand back -0
    }
}
