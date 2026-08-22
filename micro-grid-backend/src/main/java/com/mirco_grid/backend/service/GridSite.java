package com.mirco_grid.backend.service;

/** A generator or a load cluster on the network. */
public record GridSite(
        String id,
        String name,
        SiteType type,
        String suburb,
        double lat,
        double lng,
        /** kW of capacity for a supplier, average kW draw for a household cluster. */
        int capacityKw,
        SiteStatus status) {

    public enum SiteType { SUPPLIER, HOUSEHOLD }

    public enum SiteStatus { ONLINE, OFFLINE, STANDBY }

    public boolean isSupplier() {
        return type == SiteType.SUPPLIER;
    }

    /** Capacity actually contributing right now. */
    public double effectiveKw() {
        return switch (status) {
            case ONLINE -> capacityKw;
            case STANDBY -> capacityKw * 0.35;
            case OFFLINE -> 0;
        };
    }
}
