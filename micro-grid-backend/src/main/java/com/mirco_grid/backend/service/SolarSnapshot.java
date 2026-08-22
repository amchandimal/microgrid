package com.mirco_grid.backend.service;

import java.util.List;

/**
 * Everything the dashboard needs for one postcode: live weather, a live PV
 * estimate derived from it, the long-term PVWatts model, and a naive battery
 * projection.
 *
 * <p>Nullable fields are genuinely optional - an upstream API can answer
 * without them, and PVWatts is skipped entirely when no key is configured.
 */
public record SolarSnapshot(
        String postcode,
        Weather weather,
        LiveSolarEstimate liveSolarEstimate,
        PvWattsModel pvWattsModel,
        BatteryProjection batteryProjection) {

    /** Current conditions from Open-Meteo. */
    public record Weather(
            Double temperatureC,
            Integer humidityPct,
            Double windSpeedKmh,
            Integer cloudCoverPct,
            /** Global horizontal irradiance, W/m^2. */
            Double solarRadiationWm2,
            /** Direct normal irradiance, W/m^2. */
            Double directRadiationWm2,
            boolean isDay) {}

    /** Instantaneous output implied by the irradiance reading above. */
    public record LiveSolarEstimate(
            String source,
            Double pvPowerKw,
            double assumedSystemCapacityKw,
            double assumedPerformanceRatio) {}

    /**
     * Long-term modelled output from NREL PVWatts. When {@code error} is set
     * the remaining fields are null - the model was not reached.
     */
    public record PvWattsModel(
            String source,
            String error,
            Double acAnnualKwh,
            Double capacityFactorPct,
            Double solradAnnualKwhPerM2PerDay,
            List<Double> acMonthlyKwh,
            List<Double> poaMonthly,
            StationInfo stationInfo) {

        static PvWattsModel failed(String source, String error) {
            return new PvWattsModel(source, error, null, null, null, null, null, null);
        }
    }

    /** The solar resource station PVWatts drew its data from. */
    public record StationInfo(
            String location,
            String city,
            String state,
            /** Distance from the requested point, in metres. */
            Double distanceM,
            Double latitude,
            Double longitude,
            Double elevationM,
            Double timezoneOffset) {}

    /** Straight-line fill time. Ignores household load entirely. */
    public record BatteryProjection(
            double assumedCapacityKwh,
            Double estimatedHoursToFill,
            String note) {}
}
