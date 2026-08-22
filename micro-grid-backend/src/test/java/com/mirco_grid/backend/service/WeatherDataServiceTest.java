package com.mirco_grid.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

/**
 * Drives the service against a stub HTTP server rather than the real Open-Meteo
 * and PVWatts endpoints, so the suite stays offline and deterministic. The
 * canned bodies below are trimmed copies of real responses.
 */
class WeatherDataServiceTest {

    /** A real Open-Meteo body for Wollongong, fields unchanged. */
    private static final String OPEN_METEO_BODY = """
            {"latitude":-34.411247,"longitude":150.90567,"timezone":"Australia/Sydney",
             "current_units":{"temperature_2m":"°C"},
             "current":{"time":"2026-08-22T17:15","interval":900,"temperature_2m":16.6,
             "relative_humidity_2m":76,"wind_speed_10m":5.1,"cloud_cover":0,
             "shortwave_radiation":40.0,"direct_radiation":25.0,"is_day":1}}""";

    private static final String PVWATTS_BODY = """
            {"outputs":{"ac_annual":9542.1,"capacity_factor":16.5,"solrad_annual":4.9,
             "ac_monthly":[1,2,3,4,5,6,7,8,9,10,11,12],
             "poa_monthly":[10,20,30,40,50,60,70,80,90,100,110,120]},
             "station_info":{"lat":-34.5,"lon":150.9,"elev":30.0,"tz":10.0,
             "location":"Wollongong","city":"Wollongong","state":"NSW","distance":1200.0}}""";

    private HttpServer server;
    private String baseUrl;
    private final Map<String, String> routes = new ConcurrentHashMap<>();
    private final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    private final Map<String, URI> lastRequest = new ConcurrentHashMap<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        routes.put("/weather", OPEN_METEO_BODY);
        routes.put("/pvwatts", PVWATTS_BODY);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        lastRequest.put(path, exchange.getRequestURI());
        int status = statuses.getOrDefault(path, 200);
        byte[] body = routes.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private WeatherDataService service(String apiKey) {
        return new WeatherDataService(apiKey, baseUrl + "/weather", baseUrl + "/pvwatts");
    }

    // --- postcode lookup ---------------------------------------------------

    @Test
    void mapsKnownPostcodesToCoordinates() {
        WeatherDataService service = service("");
        assertThat(service.coordinatesFor("2500"))
                .contains(new WeatherDataService.Coordinates(-34.4278, 150.8931));
        assertThat(service.coordinatesFor(" 2529 "))
                .contains(new WeatherDataService.Coordinates(-34.5717, 150.8680));
        assertThat(service.knownPostcodes()).hasSize(10);
    }

    @Test
    void returnsEmptyForPostcodesOutsideTheIllawarra() {
        WeatherDataService service = service("");
        assertThat(service.coordinatesFor("2000")).isEmpty();
        assertThat(service.coordinatesFor("")).isEmpty();
        assertThat(service.coordinatesFor(null)).isEmpty();
    }

    // --- Open-Meteo --------------------------------------------------------

    @Test
    void readsEveryFieldOffTheOpenMeteoResponse() {
        SolarSnapshot.Weather weather = service("").getWeatherData(-34.4278, 150.8931);

        assertThat(weather.temperatureC()).isEqualTo(16.6);
        assertThat(weather.humidityPct()).isEqualTo(76);
        assertThat(weather.windSpeedKmh()).isEqualTo(5.1);
        assertThat(weather.cloudCoverPct()).isZero();
        assertThat(weather.solarRadiationWm2()).isEqualTo(40.0);
        assertThat(weather.directRadiationWm2()).isEqualTo(25.0);
        assertThat(weather.isDay()).isTrue();
    }

    @Test
    void asksOpenMeteoForTheFieldsAndTimezoneItNeeds() {
        service("").getWeatherData(-34.4278, 150.8931);

        String query = lastRequest.get("/weather").getQuery();
        assertThat(query)
                .contains("latitude=-34.4278")
                .contains("longitude=150.8931")
                .contains("Australia/Sydney");
        for (String field : List.of("temperature_2m", "relative_humidity_2m", "wind_speed_10m",
                "cloud_cover", "shortwave_radiation", "direct_radiation", "is_day")) {
            assertThat(query).contains(field);
        }
    }

    @Test
    void survivesAnOpenMeteoBodyWithNoCurrentBlock() {
        routes.put("/weather", "{}");
        SolarSnapshot.Weather weather = service("").getWeatherData(-34.4, 150.9);

        assertThat(weather.temperatureC()).isNull();
        assertThat(weather.isDay()).isFalse();
    }

    @Test
    void propagatesAnOpenMeteoFailure() {
        statuses.put("/weather", 500);
        WeatherDataService service = service("");

        assertThatThrownBy(() -> service.getWeatherData(-34.4, 150.9))
                .isInstanceOf(RestClientException.class);
    }

    // --- PVWatts -----------------------------------------------------------

    @Test
    void readsThePvWattsModelAndItsStation() {
        SolarSnapshot.PvWattsModel model = service("test-key").getPvWattsEstimate(-34.4, 150.9);

        assertThat(model.error()).isNull();
        assertThat(model.acAnnualKwh()).isEqualTo(9542.1);
        assertThat(model.capacityFactorPct()).isEqualTo(16.5);
        assertThat(model.solradAnnualKwhPerM2PerDay()).isEqualTo(4.9);
        assertThat(model.acMonthlyKwh()).hasSize(12);
        assertThat(model.poaMonthly()).hasSize(12);
        assertThat(model.stationInfo().city()).isEqualTo("Wollongong");
        assertThat(model.stationInfo().distanceM()).isEqualTo(1200.0);
        assertThat(model.stationInfo().elevationM()).isEqualTo(30.0);
    }

    @Test
    void sendsTheSystemParametersPvWattsNeeds() {
        service("test-key").getPvWattsEstimate(-34.4, 150.9, 8.0, 25, 0, 12);

        String query = lastRequest.get("/pvwatts").getQuery();
        assertThat(query)
                .contains("api_key=test-key")
                .contains("system_capacity=8.0")
                .contains("tilt=25")
                .contains("azimuth=0")
                .contains("losses=12")
                .contains("array_type=1")
                .contains("timeframe=monthly");
    }

    @Test
    void reportsAMissingKeyWithoutCallingPvWatts() {
        SolarSnapshot.PvWattsModel model = service("  ").getPvWattsEstimate(-34.4, 150.9);

        assertThat(model.error()).contains("NREL_API_KEY");
        assertThat(model.acAnnualKwh()).isNull();
        assertThat(lastRequest).doesNotContainKey("/pvwatts");
    }

    @Test
    void turnsAPvWattsOutageIntoAnErrorRatherThanAnException() {
        statuses.put("/pvwatts", 503);
        SolarSnapshot.PvWattsModel model = service("test-key").getPvWattsEstimate(-34.4, 150.9);

        assertThat(model.error()).isNotNull();
        assertThat(model.acAnnualKwh()).isNull();
    }

    @Test
    void surfacesPvWattsValidationErrors() {
        routes.put("/pvwatts", "{\"errors\":[\"azimuth is out of range\"]}");
        SolarSnapshot.PvWattsModel model = service("test-key").getPvWattsEstimate(-34.4, 150.9);

        assertThat(model.error()).isEqualTo("azimuth is out of range");
    }

    // --- combined ----------------------------------------------------------

    @Test
    void combinesBothSourcesForAKnownPostcode() {
        Optional<SolarSnapshot> maybe = service("test-key").getCombinedSolarData("2500");

        assertThat(maybe).isPresent();
        SolarSnapshot snapshot = maybe.get();
        assertThat(snapshot.postcode()).isEqualTo("2500");
        assertThat(snapshot.weather().temperatureC()).isEqualTo(16.6);
        assertThat(snapshot.pvWattsModel().acAnnualKwh()).isEqualTo(9542.1);
        assertThat(snapshot.liveSolarEstimate().assumedSystemCapacityKw())
                .isEqualTo(WeatherDataService.DEFAULT_SYSTEM_CAPACITY_KW);
    }

    @Test
    void derivesLiveOutputFromIrradianceAndCapacity() {
        // 40 W/m^2 of 1000, on 6.6 kW, at the 0.18 derate -> 0.05 kW
        SolarSnapshot snapshot = service("test-key").getCombinedSolarData("2500").orElseThrow();
        assertThat(snapshot.liveSolarEstimate().pvPowerKw()).isEqualTo(0.05);

        // Full sun on the same system
        routes.put("/weather", OPEN_METEO_BODY.replace("\"shortwave_radiation\":40.0",
                "\"shortwave_radiation\":1000.0"));
        SolarSnapshot bright = service("test-key").getCombinedSolarData("2500").orElseThrow();
        assertThat(bright.liveSolarEstimate().pvPowerKw()).isEqualTo(1.19);
    }

    @Test
    void scalesLiveOutputWithTheRequestedSystemSize() {
        routes.put("/weather", OPEN_METEO_BODY.replace("\"shortwave_radiation\":40.0",
                "\"shortwave_radiation\":1000.0"));
        SolarSnapshot snapshot =
                service("test-key").getCombinedSolarData("2500", 13.2, 10).orElseThrow();

        assertThat(snapshot.liveSolarEstimate().pvPowerKw()).isEqualTo(2.38);
    }

    @Test
    void projectsBatteryFillTimeFromLiveOutput() {
        routes.put("/weather", OPEN_METEO_BODY.replace("\"shortwave_radiation\":40.0",
                "\"shortwave_radiation\":1000.0"));
        SolarSnapshot snapshot =
                service("test-key").getCombinedSolarData("2500", 6.6, 10).orElseThrow();

        // 10 kWh at 1.19 kW
        assertThat(snapshot.batteryProjection().estimatedHoursToFill()).isEqualTo(8.40);
        assertThat(snapshot.batteryProjection().assumedCapacityKwh()).isEqualTo(10);
    }

    @Test
    void leavesFillTimeUnknownAfterDark() {
        routes.put("/weather", OPEN_METEO_BODY
                .replace("\"shortwave_radiation\":40.0", "\"shortwave_radiation\":0.0")
                .replace("\"is_day\":1", "\"is_day\":0"));
        SolarSnapshot snapshot = service("test-key").getCombinedSolarData("2500").orElseThrow();

        assertThat(snapshot.weather().isDay()).isFalse();
        assertThat(snapshot.liveSolarEstimate().pvPowerKw()).isZero();
        assertThat(snapshot.batteryProjection().estimatedHoursToFill()).isNull();
    }

    @Test
    void leavesLiveOutputUnknownWhenIrradianceIsMissing() {
        routes.put("/weather", "{\"current\":{\"temperature_2m\":16.6}}");
        SolarSnapshot snapshot = service("test-key").getCombinedSolarData("2500").orElseThrow();

        assertThat(snapshot.liveSolarEstimate().pvPowerKw()).isNull();
        assertThat(snapshot.batteryProjection().estimatedHoursToFill()).isNull();
    }

    @Test
    void stillAnswersWhenPvWattsIsUnavailable() {
        statuses.put("/pvwatts", 500);
        SolarSnapshot snapshot = service("test-key").getCombinedSolarData("2500").orElseThrow();

        assertThat(snapshot.weather().temperatureC()).isEqualTo(16.6);
        assertThat(snapshot.liveSolarEstimate().pvPowerKw()).isEqualTo(0.05);
        assertThat(snapshot.pvWattsModel().error()).isNotNull();
    }

    @Test
    void returnsEmptyForAnUnknownPostcode() {
        assertThat(service("test-key").getCombinedSolarData("9999")).isEmpty();
    }
}
