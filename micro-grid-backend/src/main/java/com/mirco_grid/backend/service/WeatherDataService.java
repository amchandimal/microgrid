package com.mirco_grid.backend.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Combines two free data sources for the Wollongong solar dashboard.
 *
 * <ol>
 *   <li><b>Open-Meteo</b> (<a href="https://open-meteo.com/">open-meteo.com</a>) -
 *       no key required. Live weather plus solar irradiance.
 *   <li><b>NREL PVWatts</b>
 *       (<a href="https://developer.nrel.gov/docs/solar/pvwatts/">developer.nrel.gov</a>) -
 *       needs a free key, no card. Modelled PV output from long-term solar
 *       resource data.
 * </ol>
 *
 * Get a PVWatts key at <a href="https://developer.nrel.gov/signup/">developer.nrel.gov/signup</a>
 * and set {@code NREL_API_KEY} in the environment. Without it the PVWatts half
 * of the payload reports an error and the rest still works.
 */
@Service
public class WeatherDataService {

    private static final Logger log = LoggerFactory.getLogger(WeatherDataService.class);

    static final String OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast";
    static final String PVWATTS_URL = "https://developer.nrel.gov/api/pvwatts/v8.json";

    private static final String OPEN_METEO_SOURCE = "Calculated from Open-Meteo irradiance";
    private static final String PVWATTS_SOURCE =
            "NREL PVWatts (long-term modelled averages, not live)";

    private static final String CURRENT_FIELDS = String.join(",",
            "temperature_2m",
            "relative_humidity_2m",
            "wind_speed_10m",
            "cloud_cover",
            "shortwave_radiation",
            "direct_radiation",
            "is_day");

    private static final String TIMEZONE = "Australia/Sydney";

    /** Irradiance at standard test conditions, W/m^2. */
    private static final double STC_IRRADIANCE = 1000.0;

    /**
     * Derate applied to the nameplate rating when converting live irradiance to
     * live output.
     *
     * <p>Carried over from the Python service as-is. Note that a nameplate kW
     * rating already accounts for module efficiency, so the only derate needed
     * here is the performance ratio - inverter, wiring, soiling and heat - which
     * is normally around 0.75 to 0.85. At 0.18 a 6.6 kW array reports 1.19 kW in
     * full sun rather than roughly 5.3 kW.
     */
    private static final double PERFORMANCE_RATIO = 0.18;

    public static final double DEFAULT_SYSTEM_CAPACITY_KW = 6.6;
    public static final double DEFAULT_BATTERY_CAPACITY_KWH = 10.0;
    public static final int DEFAULT_TILT_DEGREES = 20;
    /** PVWatts measures azimuth clockwise from north, so 0 faces the equator here. */
    public static final int DEFAULT_AZIMUTH_DEGREES = 0;
    public static final int DEFAULT_LOSSES_PERCENT = 14;

    /** Postcode to coordinates for the Wollongong / Illawarra area. */
    private static final Map<String, Coordinates> POSTCODE_COORDS = Map.ofEntries(
            Map.entry("2500", new Coordinates(-34.4278, 150.8931)), // Wollongong
            Map.entry("2502", new Coordinates(-34.4437, 150.8945)), // Coniston / Mount Keira
            Map.entry("2505", new Coordinates(-34.4746, 150.8788)), // Port Kembla
            Map.entry("2515", new Coordinates(-34.2683, 150.8894)), // Helensburgh
            Map.entry("2516", new Coordinates(-34.3364, 150.9081)), // Austinmer / Thirroul
            Map.entry("2517", new Coordinates(-34.3550, 150.9020)), // Bulli / Woonona
            Map.entry("2518", new Coordinates(-34.4136, 150.8994)), // Corrimal
            Map.entry("2519", new Coordinates(-34.4064, 150.8988)), // Fairy Meadow
            Map.entry("2526", new Coordinates(-34.5590, 150.8560)), // Lake Illawarra
            Map.entry("2529", new Coordinates(-34.5717, 150.8680))  // Shellharbour
    );

    private final RestClient openMeteoClient;
    private final RestClient pvWattsClient;
    private final String nrelApiKey;

    /**
     * Builds its own clients rather than taking an injected
     * {@code RestClient.Builder}: Spring Boot 4 moved that auto-configuration
     * into a separate module which this project does not depend on, and both
     * clients are fully configured here anyway.
     */
    public WeatherDataService(
            @Value("${nrel.api.key:}") String nrelApiKey,
            @Value("${open-meteo.url:" + OPEN_METEO_URL + "}") String openMeteoUrl,
            @Value("${pvwatts.url:" + PVWATTS_URL + "}") String pvWattsUrl) {
        this.nrelApiKey = nrelApiKey == null ? "" : nrelApiKey.trim();
        this.openMeteoClient = RestClient.builder()
                .baseUrl(openMeteoUrl)
                .requestFactory(requestFactory(Duration.ofSeconds(10)))
                .build();
        this.pvWattsClient = RestClient.builder()
                .baseUrl(pvWattsUrl)
                .requestFactory(requestFactory(Duration.ofSeconds(15)))
                .build();
    }

    /** A point on the ground. */
    public record Coordinates(double latitude, double longitude) {}

    /** Coordinates for a known Illawarra postcode, or empty if it is not one. */
    public Optional<Coordinates> coordinatesFor(String postcode) {
        return Optional.ofNullable(postcode)
                .map(String::trim)
                .map(POSTCODE_COORDS::get);
    }

    /** Every postcode this service knows about. */
    public java.util.Set<String> knownPostcodes() {
        return POSTCODE_COORDS.keySet();
    }

    // -----------------------------------------------------------------------
    // Source 1 - Open-Meteo (live weather and irradiance, no key required)
    // -----------------------------------------------------------------------

    /**
     * Current weather and solar irradiance for a point.
     *
     * @throws RestClientException if Open-Meteo cannot be reached or refuses
     */
    public SolarSnapshot.Weather getWeatherData(double latitude, double longitude) {
        OpenMeteoResponse response = openMeteoClient.get()
                .uri(uri -> uri
                        .queryParam("latitude", latitude)
                        .queryParam("longitude", longitude)
                        .queryParam("current", CURRENT_FIELDS)
                        .queryParam("timezone", TIMEZONE)
                        .build())
                .retrieve()
                .body(OpenMeteoResponse.class);

        OpenMeteoResponse.Current current =
                response == null ? null : response.current();
        if (current == null) {
            return new SolarSnapshot.Weather(null, null, null, null, null, null, false);
        }
        return new SolarSnapshot.Weather(
                current.temperature(),
                current.humidity(),
                current.windSpeed(),
                current.cloudCover(),
                current.shortwaveRadiation(),
                current.directRadiation(),
                current.isDay() != null && current.isDay() == 1);
    }

    // -----------------------------------------------------------------------
    // Source 2 - NREL PVWatts (free key required, modelled system output)
    // -----------------------------------------------------------------------

    /** PVWatts estimate for a typical residential rooftop system. */
    public SolarSnapshot.PvWattsModel getPvWattsEstimate(double latitude, double longitude) {
        return getPvWattsEstimate(
                latitude,
                longitude,
                DEFAULT_SYSTEM_CAPACITY_KW,
                DEFAULT_TILT_DEGREES,
                DEFAULT_AZIMUTH_DEGREES,
                DEFAULT_LOSSES_PERCENT);
    }

    /**
     * PVWatts estimate for a system the caller describes.
     *
     * <p>Never throws: a missing key, an upstream failure or an empty response
     * all come back as a model carrying {@code error}, so one flaky third party
     * cannot take out the whole snapshot.
     *
     * @param systemCapacityKw nameplate DC rating
     * @param tilt             array tilt in degrees
     * @param azimuth          degrees clockwise from north; 0 faces north
     * @param losses           system losses as a percentage
     */
    public SolarSnapshot.PvWattsModel getPvWattsEstimate(
            double latitude,
            double longitude,
            double systemCapacityKw,
            int tilt,
            int azimuth,
            int losses) {

        if (nrelApiKey.isEmpty()) {
            return SolarSnapshot.PvWattsModel.failed(
                    PVWATTS_SOURCE, "NREL_API_KEY is not configured on the server");
        }

        PvWattsResponse response;
        try {
            response = pvWattsClient.get()
                    .uri(uri -> uri
                            .queryParam("api_key", nrelApiKey)
                            .queryParam("lat", latitude)
                            .queryParam("lon", longitude)
                            .queryParam("system_capacity", systemCapacityKw)
                            .queryParam("module_type", 0)   // 0 standard, 1 premium, 2 thin film
                            .queryParam("losses", losses)
                            .queryParam("array_type", 1)    // fixed roof mount
                            .queryParam("tilt", tilt)
                            .queryParam("azimuth", azimuth)
                            .queryParam("timeframe", "monthly")
                            .build())
                    .retrieve()
                    .body(PvWattsResponse.class);
        } catch (RestClientException e) {
            log.warn("PVWatts lookup failed for {},{}", latitude, longitude, e);
            return SolarSnapshot.PvWattsModel.failed(
                    PVWATTS_SOURCE, "PVWatts request failed: " + e.getMessage());
        }

        if (response == null || response.outputs() == null) {
            String detail = response == null || response.errors() == null || response.errors().isEmpty()
                    ? "No data returned from PVWatts"
                    : String.join("; ", response.errors());
            return SolarSnapshot.PvWattsModel.failed(PVWATTS_SOURCE, detail);
        }

        PvWattsResponse.Outputs outputs = response.outputs();
        return new SolarSnapshot.PvWattsModel(
                PVWATTS_SOURCE,
                null,
                outputs.acAnnual(),
                outputs.capacityFactor(),
                outputs.solradAnnual(),
                outputs.acMonthly(),
                outputs.poaMonthly(),
                toStationInfo(response.stationInfo()));
    }

    // -----------------------------------------------------------------------
    // Combined lookup
    // -----------------------------------------------------------------------

    /** Combined snapshot for a postcode, using the default system and battery. */
    public Optional<SolarSnapshot> getCombinedSolarData(String postcode) {
        return getCombinedSolarData(
                postcode, DEFAULT_SYSTEM_CAPACITY_KW, DEFAULT_BATTERY_CAPACITY_KWH);
    }

    /**
     * Combined snapshot for a postcode.
     *
     * @return empty if the postcode is not one of the Illawarra postcodes
     * @throws RestClientException if Open-Meteo cannot be reached
     */
    public Optional<SolarSnapshot> getCombinedSolarData(
            String postcode, double systemCapacityKw, double batteryCapacityKwh) {

        return coordinatesFor(postcode).map(coords -> {
            SolarSnapshot.Weather weather =
                    getWeatherData(coords.latitude(), coords.longitude());
            SolarSnapshot.PvWattsModel pvWatts = getPvWattsEstimate(
                    coords.latitude(),
                    coords.longitude(),
                    systemCapacityKw,
                    DEFAULT_TILT_DEGREES,
                    DEFAULT_AZIMUTH_DEGREES,
                    DEFAULT_LOSSES_PERCENT);

            Double livePowerKw = liveOutputKw(weather.solarRadiationWm2(), systemCapacityKw);

            return new SolarSnapshot(
                    postcode.trim(),
                    weather,
                    new SolarSnapshot.LiveSolarEstimate(
                            OPEN_METEO_SOURCE, livePowerKw, systemCapacityKw, PERFORMANCE_RATIO),
                    pvWatts,
                    new SolarSnapshot.BatteryProjection(
                            batteryCapacityKwh,
                            hoursToFill(batteryCapacityKwh, livePowerKw),
                            "Projection only, not real battery telemetry. "
                                    + "Assumes constant output and no household load."));
        });
    }

    /**
     * Instantaneous output implied by an irradiance reading. PVWatts cannot
     * answer this - it models long-term monthly and annual averages.
     */
    private static Double liveOutputKw(Double irradianceWm2, double systemCapacityKw) {
        if (irradianceWm2 == null) {
            return null;
        }
        return round((irradianceWm2 / STC_IRRADIANCE) * systemCapacityKw * PERFORMANCE_RATIO, 2);
    }

    private static Double hoursToFill(double batteryCapacityKwh, Double livePowerKw) {
        if (livePowerKw == null || livePowerKw <= 0) {
            return null;
        }
        return round(batteryCapacityKwh / livePowerKw, 2);
    }

    private static SolarSnapshot.StationInfo toStationInfo(PvWattsResponse.Station station) {
        if (station == null) {
            return null;
        }
        return new SolarSnapshot.StationInfo(
                station.location(),
                station.city(),
                station.state(),
                station.distance(),
                station.lat(),
                station.lon(),
                station.elev(),
                station.tz());
    }

    private static double round(double value, int places) {
        return BigDecimal.valueOf(value)
                .setScale(places, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static ClientHttpRequestFactory requestFactory(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    // -----------------------------------------------------------------------
    // Upstream wire shapes - snake_case, and only the fields we use
    // -----------------------------------------------------------------------

    record OpenMeteoResponse(@JsonProperty("current") Current current) {
        record Current(
                @JsonProperty("temperature_2m") Double temperature,
                @JsonProperty("relative_humidity_2m") Integer humidity,
                @JsonProperty("wind_speed_10m") Double windSpeed,
                @JsonProperty("cloud_cover") Integer cloudCover,
                @JsonProperty("shortwave_radiation") Double shortwaveRadiation,
                @JsonProperty("direct_radiation") Double directRadiation,
                @JsonProperty("is_day") Integer isDay) {}
    }

    record PvWattsResponse(
            @JsonProperty("outputs") Outputs outputs,
            @JsonProperty("station_info") Station stationInfo,
            @JsonProperty("errors") List<String> errors) {

        record Outputs(
                @JsonProperty("ac_annual") Double acAnnual,
                @JsonProperty("capacity_factor") Double capacityFactor,
                @JsonProperty("solrad_annual") Double solradAnnual,
                @JsonProperty("ac_monthly") List<Double> acMonthly,
                @JsonProperty("poa_monthly") List<Double> poaMonthly) {}

        record Station(
                @JsonProperty("location") String location,
                @JsonProperty("city") String city,
                @JsonProperty("state") String state,
                @JsonProperty("distance") Double distance,
                @JsonProperty("lat") Double lat,
                @JsonProperty("lon") Double lon,
                @JsonProperty("elev") Double elev,
                @JsonProperty("tz") Double tz) {}
    }
}
