package com.mirco_grid.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mirco_grid.backend.service.GridService.GridCells;
import java.util.List;
import org.junit.jupiter.api.Test;

class GridServiceTest {

    private final BuiltUpDensity density = new BuiltUpDensity();
    private final GridService service = new GridService(density);

    private GridCells wholeRegion(double zoom) {
        return service.cellsForView(
                IllawarraRegion.SOUTH, IllawarraRegion.WEST,
                IllawarraRegion.NORTH, IllawarraRegion.EAST, zoom);
    }

    // --- resolution ----------------------------------------------------------

    @Test
    void bottomsOutAtFiftyMetresFromZoomSixteenIn() {
        assertThat(service.cellMetresForZoom(16)).isEqualTo(50);
        assertThat(service.cellMetresForZoom(17)).isEqualTo(50);
        assertThat(service.cellMetresForZoom(19.5)).isEqualTo(50);
    }

    @Test
    void doublesTheCellForEveryZoomLevelOut() {
        assertThat(service.cellMetresForZoom(15)).isEqualTo(100);
        assertThat(service.cellMetresForZoom(14)).isEqualTo(200);
        assertThat(service.cellMetresForZoom(12)).isEqualTo(800);
        assertThat(service.cellMetresForZoom(11)).isEqualTo(1600);
    }

    @Test
    void neverGoesFinerThanFiftyMetresOnAFractionalZoom() {
        for (double z = 10; z <= 19; z += 0.25) {
            assertThat(service.cellMetresForZoom(z)).isGreaterThanOrEqualTo(50);
        }
    }

    // --- extent --------------------------------------------------------------

    @Test
    void keepsEveryCellInsideTheRegion() {
        for (GridCell cell : wholeRegion(11).cells()) {
            assertThat(IllawarraRegion.contains(cell.lat(), cell.lng()))
                    .as("cell %s at %s,%s", cell.id(), cell.lat(), cell.lng())
                    .isTrue();
        }
    }

    @Test
    void putsNoCellOnWater() {
        for (GridCell cell : wholeRegion(11).cells()) {
            assertThat(IllawarraWater.isWater(cell.lat(), cell.lng()))
                    .as("cell %s", cell.id())
                    .isFalse();
        }
    }

    @Test
    void leavesTheBushBare() {
        // The national parks and the escarpment carry no network, so no cell
        // should land within a couple of kilometres of them.
        List<double[]> bush = List.of(
                new double[] {-34.25, 150.90},  // Dharawal National Park
                new double[] {-34.43, 150.78},  // escarpment above Figtree
                new double[] {-34.67, 150.70}); // Barren Grounds
        GridCells page = wholeRegion(11);
        // A spot is bare when no cell covers it - further than half a cell
        // width from every centre. Anything tighter than that just asserts the
        // cell size.
        double halfCellKm = page.sizeMetres() / 2000.0;
        for (GridCell cell : page.cells()) {
            for (double[] spot : bush) {
                double km = Math.hypot(
                        (cell.lat() - spot[0]) * 110.95,
                        (cell.lng() - spot[1]) * 91.83);
                assertThat(km).as("cell %s covers bush", cell.id()).isGreaterThan(halfCellKm);
            }
        }
    }

    // --- the field -----------------------------------------------------------

    @Test
    void greensTheTownsAndRedensTheirFringes() {
        List<GridCell> cells = wholeRegion(11).cells();
        for (var town : List.of(
                new Object[] {"Wollongong", -34.4244, 150.8938},
                new Object[] {"Dapto", -34.5000, 150.7940},
                new Object[] {"Thirroul", -34.3153, 150.9236},
                new Object[] {"Shellharbour", -34.5789, 150.8672})) {
            GridCell nearest = nearest(cells, (double) town[1], (double) town[2]);
            assertThat(nearest.balance()).as("%s should read as supply", town[0])
                    .isGreaterThan(0.4);
            assertThat(nearest.builtUp()).as("%s should be built up", town[0])
                    .isGreaterThan(0.5);
        }
        // And the overlay must still carry a red end, or the ramp is wasted.
        assertThat(cells.stream().filter(c -> c.balance() < -0.3).count()).isGreaterThan(20);
    }

    @Test
    void keepsDemandSupplyAndBalanceConsistent() {
        for (GridCell cell : wholeRegion(12).cells()) {
            assertThat(cell.demandKw()).isGreaterThan(0);
            assertThat(cell.supplyKw()).isGreaterThan(0);
            assertThat(cell.balance()).isBetween(-1.0, 1.0);
            assertThat(cell.builtUp()).isBetween(0.0, 1.0);
            if (cell.netKw() != 0) {
                assertThat(Math.signum(cell.netKw())).isEqualTo(Math.signum(cell.balance()));
            }
            assertThat(cell.netKw()).isNotEqualTo(-0.0);
        }
    }

    @Test
    void answersTheSameWayTwice() {
        List<GridCell> first = wholeRegion(11).cells();
        List<GridCell> again = wholeRegion(11).cells();
        assertThat(again).hasSameSizeAs(first);
        assertThat(again.get(0)).isEqualTo(first.get(0));
        assertThat(again.get(again.size() - 1)).isEqualTo(first.get(first.size() - 1));
    }

    @Test
    void staysUnderTheCapOnAWholeRegionRequest() {
        GridCells page = wholeRegion(11);
        assertThat(page.truncated()).isFalse();
        assertThat(page.count()).isEqualTo(page.cells().size());
        assertThat(page.count()).isBetween(100, GridService.MAX_CELLS);
        assertThat(page.referenceLat()).isEqualTo(GridService.REFERENCE_LAT);
    }

    @Test
    void reportsWhenTheCapCutsItShort() {
        // The whole region at 50 m is far more than the cap allows.
        GridCells page = wholeRegion(16);
        assertThat(page.truncated()).isTrue();
        assertThat(page.count()).isEqualTo(GridService.MAX_CELLS);
    }

    @Test
    void namesTheSitesSittingInsideACell() {
        // Port Kembla Solar Farm, in a cell big enough to swallow it.
        GridCells page = service.cellsForView(-34.50, 150.87, -34.45, 150.94, 12);
        assertThat(page.cells().stream().flatMap(c -> c.siteIds().stream()))
                .contains("S-01");
    }

    @Test
    void readsUnanderraAsDemandDespiteBeingBuiltUp() {
        // Industrial load, not rooftops. The built-up field on its own would
        // call this a strong exporter, so the load centre has to win.
        GridCells page = service.cellsForView(-34.52, 150.78, -34.38, 150.94, 13);
        GridCell centre = nearest(page.cells(), -34.4496, 150.8501);

        assertThat(centre.builtUp()).isGreaterThan(0.9);
        assertThat(centre.balance()).isLessThan(-0.5);
        assertThat(centre.demandKw()).isGreaterThan(centre.supplyKw() * 5);
        assertThat(centre.netKw()).isNegative();
    }

    @Test
    void keepsTheUnanderraDemandLocal() {
        GridCells page = service.cellsForView(-34.52, 150.78, -34.38, 150.94, 13);
        // Wollongong CBD is under 5 km away and must still read as supply.
        assertThat(nearest(page.cells(), -34.4244, 150.8938).balance()).isGreaterThan(0.5);
        // And it fades with distance rather than cutting off.
        double atCentre = nearest(page.cells(), -34.4496, 150.8501).balance();
        double at1km = nearest(page.cells(), -34.4586, 150.8501).balance();
        double at3km = nearest(page.cells(), -34.4766, 150.8501).balance();
        assertThat(atCentre).isLessThan(at1km);
        assertThat(at1km).isLessThan(at3km);
    }

    // --- sites ---------------------------------------------------------------

    @Test
    void servesEverySiteAndKeepsThemAllInTheRegion() {
        assertThat(service.sites()).hasSize(14);
        for (GridSite site : service.sites()) {
            assertThat(IllawarraRegion.contains(site.lat(), site.lng()))
                    .as("%s at %s,%s", site.id(), site.lat(), site.lng())
                    .isTrue();
            assertThat(IllawarraWater.isWater(site.lat(), site.lng()))
                    .as("%s should be on land", site.id())
                    .isFalse();
        }
    }

    @Test
    void deratesStandbyAndZeroesOfflineSites() {
        GridSite standby = find("S-04");
        GridSite offline = find("S-06");
        GridSite online = find("S-01");
        assertThat(online.effectiveKw()).isEqualTo(4200);
        assertThat(standby.effectiveKw()).isEqualTo(3100 * 0.35);
        assertThat(offline.effectiveKw()).isZero();
    }

    private GridSite find(String id) {
        return service.sites().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static GridCell nearest(List<GridCell> cells, double lat, double lng) {
        return cells.stream()
                .min((a, b) -> Double.compare(
                        Math.hypot(a.lat() - lat, a.lng() - lng),
                        Math.hypot(b.lat() - lat, b.lng() - lng)))
                .orElseThrow();
    }
}
