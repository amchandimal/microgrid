package com.mirco_grid.backend.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mirco_grid.backend.service.council.CouncilAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The door, end to end.
 *
 * <p>A full context rather than a controller slice: the interceptor is
 * registered by {@code WebConfig} and the point of these tests is that it is
 * actually wired to the path it is meant to guard, which a slice with a mocked
 * service would not prove.
 */
@SpringBootTest
class CouncilAccessTest {

    private static final String TOKEN = "Bearer council-demo-token";

    private final MockMvc mvc;

    @Autowired
    CouncilAccessTest(WebApplicationContext context) {
        this.mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    // --- login ---------------------------------------------------------------

    @Test
    void issuesATokenForTheCouncilAccount() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"council\",\"password\":\"Wollongong2026!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("council-demo-token"))
                .andExpect(jsonPath("$.role").value(CouncilAuth.ROLE))
                .andExpect(jsonPath("$.displayName").value("Wollongong City Council"));
    }

    @Test
    void refusesTheWrongPassword() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"council\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- the guarded path ----------------------------------------------------

    @Test
    void refusesCouncilDataWithoutAToken() throws Exception {
        for (String path : new String[] {
            "/api/council/summary",
            "/api/council/suburbs",
            "/api/council/trend",
            "/api/council/consumption",
            "/api/council/rebates",
            "/api/council/equity-map",
            "/api/council/grid-stress",
        }) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void refusesAWrongToken() throws Exception {
        mvc.perform(get("/api/council/summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-the-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/council/summary")
                        .header(HttpHeaders.AUTHORIZATION, "council-demo-token"))
                .andExpect(status().isUnauthorized());
    }

    /** The rest of the API is public on purpose; the guard must not spread. */
    @Test
    void leavesThePublicEndpointsOpen() throws Exception {
        mvc.perform(get("/api/grid/region")).andExpect(status().isOk());
        mvc.perform(get("/api/grid/sites")).andExpect(status().isOk());
    }

    // --- the data itself -----------------------------------------------------

    @Test
    void servesTheHeadlineFiguresOffTheLoadedFiles() throws Exception {
        mvc.perform(get("/api/council/summary").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalInstallations").value(27776))
                .andExpect(jsonPath("$.housesInLga").value(73390))
                .andExpect(jsonPath("$.pvDensityPct").value(36.4))
                .andExpect(jsonPath("$.avgAnnualBillAud").value(1521.0))
                .andExpect(jsonPath("$.avgGridCostCkwh").value(35.8))
                .andExpect(jsonPath("$.rebateGap.accounts").value(911200.0))
                .andExpect(jsonPath("$.rebateGap.eligible").value(1181300.0))
                .andExpect(jsonPath("$.sources").isNotEmpty());
    }

    @Test
    void ranksEveryLocalityAndShowsItsWorking() throws Exception {
        mvc.perform(get("/api/council/suburbs").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suburbs.length()").value(66))
                .andExpect(jsonPath("$.suburbs[0].rank").value(1))
                .andExpect(jsonPath("$.methodology.terms.length()").value(3))
                .andExpect(jsonPath("$.methodology.formula").isNotEmpty());
    }

    @Test
    void servesOneLocalityWithItsPostcodesConsumption() throws Exception {
        mvc.perform(get("/api/council/suburbs/Warrawong").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suburb.locality").value("Warrawong"))
                .andExpect(jsonPath("$.suburb.postcode").value("2502"))
                .andExpect(jsonPath("$.consumption.postcode").value("2502"))
                .andExpect(jsonPath("$.postcodePeers.length()").value(3));
    }

    @Test
    void answersNotFoundForALocalityThatIsNotInTheLga() throws Exception {
        mvc.perform(get("/api/council/suburbs/Narnia").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void servesBothMonthlySeriesOnOneTimeline() throws Exception {
        mvc.perform(get("/api/council/trend").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(300))
                .andExpect(jsonPath("$.points[0].month").value("2001-01"))
                .andExpect(jsonPath("$.points[299].month").value("2025-12"))
                .andExpect(jsonPath("$.points[299].installsResidential").value(107))
                .andExpect(jsonPath("$.points[299].capacityResidentialKw").value(859.0));
    }

    @Test
    void chartsOnlyThePostcodesThisCouncilCovers() throws Exception {
        mvc.perform(get("/api/council/consumption").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postcodes[?(@.postcode == '2500')]").isNotEmpty())
                // Shellharbour, and a postcode Wollongong barely touches.
                .andExpect(jsonPath("$.postcodes[?(@.postcode == '2527')]").isEmpty())
                .andExpect(jsonPath("$.postcodes[?(@.postcode == '2529')]").isEmpty())
                .andExpect(jsonPath("$.postcodes[?(@.postcode == '2528')]").isEmpty())
                // No load reported at all.
                .andExpect(jsonPath("$.postcodes[?(@.postcode == '2520')]").isEmpty())
                .andExpect(jsonPath("$.note").isNotEmpty());
    }

    @Test
    void servesTheRebateTrendAndTheTakeUpGap() throws Exception {
        mvc.perform(get("/api/council/rebates").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financialYears.length()").value(6))
                .andExpect(jsonPath("$.headlineGap.takeUpPct").value(77.1))
                .andExpect(jsonPath("$.eapa.electricityApplications").value(55947));
    }

    @Test
    void servesOnlyTheLocalitiesItCanPlaceOnAMap() throws Exception {
        mvc.perform(get("/api/council/equity-map").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.localityCount").value(66))
                .andExpect(jsonPath("$.localities[0].lat").isNumber())
                .andExpect(jsonPath("$.localities[0].lng").isNumber());
    }

    @Test
    void readsTheLiveGridForEveryRankedSuburb() throws Exception {
        mvc.perform(get("/api/council/grid-stress").header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areas").isNotEmpty())
                .andExpect(jsonPath("$.areas[0].state").isNotEmpty())
                .andExpect(jsonPath("$.areas[0].pctDayInPeak").isNumber())
                .andExpect(jsonPath("$.note").isNotEmpty());
    }
}
