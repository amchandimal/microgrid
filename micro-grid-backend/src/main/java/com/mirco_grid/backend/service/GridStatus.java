package com.mirco_grid.backend.service;

import java.time.OffsetDateTime;

/**
 * Live supply and demand around one address.
 *
 * <p>The spec keys this on an {@code areaId}; here the caller passes the
 * coordinates its address geocoded to instead, and the area is whatever the
 * grid model covers around that point. Everything below is aggregated from the
 * same cells the map draws, so a change to the field shows up here too.
 */
public record GridStatus(
        /** Human label for the area sampled, e.g. "within 2.0 km of -34.45, 150.85". */
        String area,
        double lat,
        double lng,
        OffsetDateTime timestamp,
        /** Cells aggregated to produce these numbers. */
        int cellCount,
        double supplyKw,
        double demandKw,
        double surplusKw,
        State state,
        /** Local network cannot absorb the backflow - export is capped. */
        boolean exportConstrained,
        /** Indicative local peer-to-peer price, cents per kWh. */
        double priceSignalCkwh,
        /** Charge of the nearest community battery, or null if none is close. */
        Integer communityBatterySocPct,
        /** The battery those numbers refer to, or null. */
        CommunityBattery communityBattery) {

    public enum State {
        /** Supply comfortably exceeds demand - cheap power, run the big loads. */
        SURPLUS,
        BALANCED,
        /** Demand exceeds supply - dearest power, defer what you can. */
        PEAK
    }

    /** One of Endeavour Energy's neighbourhood batteries. */
    public record CommunityBattery(String name, String suburb, double lat, double lng, int capacityKwh) {}
}
