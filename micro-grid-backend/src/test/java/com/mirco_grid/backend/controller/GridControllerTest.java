package com.mirco_grid.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mirco_grid.backend.service.BuiltUpDensity;
import com.mirco_grid.backend.service.GridAllocationService;
import com.mirco_grid.backend.service.GridService;
import com.mirco_grid.backend.service.IllawarraRegion;
import com.mirco_grid.backend.service.council.CouncilAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GridController.class)
// CouncilAuth comes in because WebConfig registers the council interceptor on
// every MVC context; this slice does not exercise it, it just has to build.
@Import({GridService.class, BuiltUpDensity.class, CouncilAuth.class})
class GridControllerTest {

    @Autowired
    private MockMvc mvc;

    // The controller now also serves the installed fleet, which is read out of
    // SQLite. None of these tests touch it, and a slice has no datasource.
    @MockitoBean
    private GridAllocationService allocation;

    @Test
    void servesTheRegionTheMapShouldCover() throws Exception {
        mvc.perform(get("/api/grid/region"))
                .andExpect(status().isOk())
                // Top of Waterfall down to the bottom of Jervis Bay.
                .andExpect(jsonPath("$.north").value(-34.115))
                .andExpect(jsonPath("$.south").value(-35.175))
                .andExpect(jsonPath("$.west").value(150.65))
                .andExpect(jsonPath("$.east").value(151.14))
                // Leaflet wants [[south, west], [north, east]]
                .andExpect(jsonPath("$.bounds[0][0]").value(-35.175))
                .andExpect(jsonPath("$.bounds[0][1]").value(150.65))
                .andExpect(jsonPath("$.bounds[1][0]").value(-34.115))
                .andExpect(jsonPath("$.bounds[1][1]").value(151.14))
                .andExpect(jsonPath("$.finestMetres").value(50))
                .andExpect(jsonPath("$.referenceLat").value(GridService.REFERENCE_LAT));
    }

    /**
     * The camera reaches further than the model does, and opens somewhere else
     * again. All three are the client's business, so all three are sent.
     */
    @Test
    void tellsTheMapWhereToOpenAndWhereTheDataStops() throws Exception {
        mvc.perform(get("/api/grid/region"))
                .andExpect(status().isOk())
                // Wollongong, not the middle of a 115 km strip of coast.
                .andExpect(jsonPath("$.focus.lat").value(-34.4278))
                .andExpect(jsonPath("$.focus.lng").value(150.8931))
                .andExpect(jsonPath("$.focus.label").value("Wollongong"))
                // The overlay covers Helensburgh to Kiama, inside the region.
                .andExpect(jsonPath("$.surveyedBounds[0][0]").value(-34.6947658))
                .andExpect(jsonPath("$.surveyedBounds[1][0]").value(-34.145887))
                .andExpect(jsonPath("$.coverageNote").isNotEmpty());
    }

    /** The opening point has to be somewhere the camera is allowed to go. */
    @Test
    void opensInsideItsOwnBounds() {
        assertThat(IllawarraRegion.contains(
                        IllawarraRegion.FOCUS_LAT, IllawarraRegion.FOCUS_LNG))
                .isTrue();
        assertThat(IllawarraRegion.isSurveyed(
                        IllawarraRegion.FOCUS_LAT, IllawarraRegion.FOCUS_LNG))
                .isTrue();
    }

    @Test
    void servesEverySite() throws Exception {
        mvc.perform(get("/api/grid/sites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(14)))
                .andExpect(jsonPath("$[0].id").value("S-01"))
                .andExpect(jsonPath("$[0].type").value("SUPPLIER"))
                .andExpect(jsonPath("$[*].lat", everyItem(greaterThan(IllawarraRegion.SOUTH))));
    }

    @Test
    void servesCellsForAViewport() throws Exception {
        mvc.perform(get("/api/grid/cells")
                        .param("south", "-34.6947658").param("west", "150.65")
                        .param("north", "-34.145887").param("east", "151.14")
                        .param("zoom", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sizeMetres").value(1600))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.count", greaterThan(100)))
                .andExpect(jsonPath("$.cells[0].id").exists())
                .andExpect(jsonPath("$.cells[*].balance", everyItem(lessThanOrEqualTo(1.0))));
    }

    @Test
    void clipsAViewportLargerThanTheRegion() throws Exception {
        mvc.perform(get("/api/grid/cells")
                        .param("south", "-40").param("west", "145")
                        .param("north", "-30").param("east", "155")
                        .param("zoom", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", greaterThan(100)));
    }

    @Test
    void returnsNothingForAViewportOffTheRegion() throws Exception {
        mvc.perform(get("/api/grid/cells")
                        .param("south", "-33.9").param("west", "151.1")
                        .param("north", "-33.8").param("east", "151.3")
                        .param("zoom", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.cells", hasSize(0)));
    }

    @Test
    void rejectsAnInsideOutViewport() throws Exception {
        mvc.perform(get("/api/grid/cells")
                        .param("south", "-34.2").param("west", "150.65")
                        .param("north", "-34.6").param("east", "151.14")
                        .param("zoom", "11"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnImpossibleZoom() throws Exception {
        mvc.perform(get("/api/grid/cells")
                        .param("south", "-34.6").param("west", "150.65")
                        .param("north", "-34.2").param("east", "151.14")
                        .param("zoom", "40"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAMissingParameter() throws Exception {
        mvc.perform(get("/api/grid/cells").param("south", "-34.6"))
                .andExpect(status().isBadRequest());
    }
}
