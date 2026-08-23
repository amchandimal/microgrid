package com.mirco_grid.backend.service;

/**
 * The patch of coast this dashboard covers.
 *
 * <p>North and south are the ends of the Illawarra as the brief sets them:
 * the top of Waterfall, where the Illawarra line leaves the Sutherland Shire
 * and enters the Royal National Park, down to the bottom of Jervis Bay. East
 * and west frame the coastal strip between them - far enough west to take in
 * Jamberoo, Albion Park and the escarpment while leaving Picton and Robertson
 * out, and far enough east to clear the coast everywhere, which reaches
 * 151.129 where the Royal National Park juts out above Helensburgh.
 *
 * <p>That is 115 km of coast against 45 km of width, so the region is a tall
 * strip. Fitted to a landscape window it is the height that decides the zoom.
 *
 * <p>The camera covers all of this. The modelled overlay does not: see
 * {@link #SURVEYED_NORTH} below.
 */
public final class IllawarraRegion {

    /** Northern boundary of Waterfall. */
    public static final double NORTH = -34.1150;
    /** Southern shore of Jervis Bay. */
    public static final double SOUTH = -35.1750;
    public static final double WEST = 150.6500;
    public static final double EAST = 151.1400;

    /**
     * The part of the region the grid model actually has data for.
     *
     * <p>The built-up density lattice and the traced shoreline were both built
     * for the original Helensburgh-to-Kiama extent and stop there. Everything
     * that reads them - the hex overlay, the live status readings, the area
     * allocation - is therefore blank south of Kiama and for the few kilometres
     * north of Helensburgh, and says so rather than extrapolating: a lattice
     * asked for a point off its edge would repeat its edge row for fifty
     * kilometres, and a shoreline asked the same question would run straight
     * south through the middle of Jervis Bay.
     *
     * <p>To cover the whole region, regenerate
     * {@code illawarra/built-up-density.properties} and
     * {@link IllawarraWater}'s coast over the new bounds; nothing else has to
     * change, because both carry their own extent and everything else reads it
     * from here.
     */
    public static final double SURVEYED_NORTH = -34.1458870;
    /** Southern boundary of Kiama - the end of the surveyed strip. */
    public static final double SURVEYED_SOUTH = -34.6947658;

    /**
     * Where the map opens: the centre of Wollongong.
     *
     * <p>The region is a hundred and fifteen kilometres of coast, so fitting
     * all of it puts the camera out over Gerringong with its top edge in the
     * southern edge of Sydney - a long way from anything this app is about.
     * The city is what the map is for, so that is what it opens on. The region
     * is still the limit of where it can be taken: this only decides the first
     * frame.
     */
    public static final double FOCUS_LAT = -34.4278;
    public static final double FOCUS_LNG = 150.8931;
    public static final String FOCUS_LABEL = "Wollongong";

    private IllawarraRegion() {}

    public static boolean contains(double lat, double lng) {
        return lat <= NORTH && lat >= SOUTH && lng >= WEST && lng <= EAST;
    }

    /** Is this point somewhere the grid model has been surveyed? */
    public static boolean isSurveyed(double lat, double lng) {
        return lat <= SURVEYED_NORTH && lat >= SURVEYED_SOUTH && lng >= WEST && lng <= EAST;
    }

    /** [[south, west], [north, east]] - the shape Leaflet wants. */
    public static double[][] asBounds() {
        return new double[][] {{SOUTH, WEST}, {NORTH, EAST}};
    }

    /** The surveyed strip, in the same shape. */
    public static double[][] surveyedBounds() {
        return new double[][] {{SURVEYED_SOUTH, WEST}, {SURVEYED_NORTH, EAST}};
    }
}
