package com.mirco_grid.backend.service;

/**
 * The patch of coast this dashboard covers.
 *
 * <p>North and south are the suburb boundaries asked for - the top of
 * Helensburgh and the bottom of Kiama, both taken from OpenStreetMap rather
 * than eyeballed. East and west frame the Illawarra either side of that: far
 * enough west to take in Jamberoo, Albion Park and the escarpment while
 * leaving Picton and Robertson out, and far enough east to clear the coast
 * everywhere, which reaches 151.129 where the Royal National Park juts out
 * above Helensburgh.
 */
public final class IllawarraRegion {

    /** Northern boundary of Helensburgh. */
    public static final double NORTH = -34.1458870;
    /** Southern boundary of Kiama. */
    public static final double SOUTH = -34.6947658;
    public static final double WEST = 150.6500;
    public static final double EAST = 151.1400;

    private IllawarraRegion() {}

    public static boolean contains(double lat, double lng) {
        return lat <= NORTH && lat >= SOUTH && lng >= WEST && lng <= EAST;
    }

    /** [[south, west], [north, east]] - the shape Leaflet wants. */
    public static double[][] asBounds() {
        return new double[][] {{SOUTH, WEST}, {NORTH, EAST}};
    }
}
