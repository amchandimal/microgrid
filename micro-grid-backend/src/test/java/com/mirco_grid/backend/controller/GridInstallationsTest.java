package com.mirco_grid.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mirco_grid.backend.service.GridAllocationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The public dashboard's figures, end to end.
 *
 * <p>A full context because the point is the whole path: the two Total System
 * exports parsed into SQLite at startup, apportioned across the map's areas,
 * and read back out by the endpoint the Angular dashboard calls. The expected
 * totals were summed from the CSVs by hand.
 */
@SpringBootTest
class GridInstallationsTest {

    private final MockMvc mvc;
    private final GridAllocationService allocation;

    @Autowired
    GridInstallationsTest(WebApplicationContext context, GridAllocationService allocation) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
        this.allocation = allocation;
    }

    /** No token: this is the front page, not the council view. */
    @Test
    void servesTheFleetWithoutASignIn() throws Exception {
        mvc.perform(get("/api/grid/installations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("2001-01 to 2025-12"))
                .andExpect(jsonPath("$.method").isNotEmpty())
                .andExpect(jsonPath("$.sources.length()").value(3));
    }

    @Test
    void totalsMatchTheTwoTotalSystemExports() throws Exception {
        mvc.perform(get("/api/grid/installations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.residentialInstalls").value(26678))
                .andExpect(jsonPath("$.totals.commercialInstalls").value(1076))
                .andExpect(jsonPath("$.totals.powerStationInstalls").value(19))
                .andExpect(jsonPath("$.totals.totalInstalls").value(27773))
                .andExpect(jsonPath("$.totals.residentialKw").value(152252.0))
                .andExpect(jsonPath("$.totals.commercialKw").value(32122.0))
                .andExpect(jsonPath("$.totals.powerStationKw").value(5363.0))
                .andExpect(jsonPath("$.totals.totalKw").value(189737.0));
    }

    /** The headline and the list have to agree, to the system. */
    @Test
    void theAreasAddBackUpToTheLgaTotal() {
        GridAllocationService.Totals totals = allocation.totals();
        List<GridAllocationService.Area> areas = allocation.areas();

        assertThat(areas).isNotEmpty();
        assertThat(areas.stream().mapToInt(GridAllocationService.Area::residentialInstalls).sum())
                .isEqualTo(totals.residentialInstalls());
        assertThat(areas.stream().mapToInt(GridAllocationService.Area::commercialInstalls).sum())
                .isEqualTo(totals.commercialInstalls());
        assertThat(areas.stream().mapToInt(GridAllocationService.Area::powerStationInstalls).sum())
                .isEqualTo(totals.powerStationInstalls());
    }

    @Test
    void sharesAddUpToOneHundredPerCent() {
        List<GridAllocationService.Area> areas = allocation.areas();

        assertThat(areas.stream().mapToDouble(GridAllocationService.Area::supplySharePct).sum())
                .isCloseTo(100, org.assertj.core.data.Offset.offset(0.5));
        assertThat(areas.stream().mapToDouble(GridAllocationService.Area::demandSharePct).sum())
                .isCloseTo(100, org.assertj.core.data.Offset.offset(0.5));
    }

    /**
     * Households follow supply and commercial follows demand, so the suburb
     * the model loads hardest - the Unanderra industrial pocket - has to come
     * out ahead on commercial rather than on households.
     */
    @Test
    void putsCommercialWhereTheModelPutsTheLoad() {
        List<GridAllocationService.Area> areas = allocation.areas();

        GridAllocationService.Area busiest = areas.stream()
                .max((a, b) -> Double.compare(a.demandSharePct(), b.demandSharePct()))
                .orElseThrow();
        assertThat(busiest.name()).isEqualTo("Unanderra");
        assertThat(busiest.commercialInstalls())
                .isEqualTo(areas.stream()
                        .mapToInt(GridAllocationService.Area::commercialInstalls)
                        .max()
                        .orElseThrow());
    }

    /** Bush and reservoir localities carry no network, so they carry no systems. */
    @Test
    void givesNothingToAnAreaWithNoField() {
        assertThat(allocation.areas())
                .filteredOn(area -> area.cellCount() == 0)
                .allSatisfy(area -> {
                    assertThat(area.residentialInstalls()).isZero();
                    assertThat(area.commercialInstalls()).isZero();
                });
    }
}
