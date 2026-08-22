package com.mirco_grid.backend.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * How built-up each point in the region is, on a 0..1 scale.
 *
 * <p>Reads the lattice described in
 * {@code illawarra/built-up-density.properties} and interpolates it. That
 * field is what shapes the map: generation and headroom follow the suburbs,
 * so the overlay greens over the towns and reddens over the escarpment and
 * the national parks - the same pattern as the reference map.
 */
@Component
public class BuiltUpDensity {

    private static final String RESOURCE = "illawarra/built-up-density.properties";

    private final int rows;
    private final int cols;
    private final double north;
    private final double south;
    private final double west;
    private final double east;
    private final byte[] samples;

    public BuiltUpDensity() {
        Properties props = new Properties();
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
        this.rows = Integer.parseInt(props.getProperty("rows").trim());
        this.cols = Integer.parseInt(props.getProperty("cols").trim());
        this.north = Double.parseDouble(props.getProperty("north").trim());
        this.south = Double.parseDouble(props.getProperty("south").trim());
        this.west = Double.parseDouble(props.getProperty("west").trim());
        this.east = Double.parseDouble(props.getProperty("east").trim());
        this.samples = Base64.getDecoder()
                .decode(props.getProperty("data").trim().getBytes(StandardCharsets.US_ASCII));

        if (samples.length != rows * cols) {
            throw new IllegalStateException(
                    "Density lattice is " + samples.length + " bytes but the header says "
                            + rows + "x" + cols + " = " + (rows * cols));
        }
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    /**
     * Built-up fraction at a point, 0 (bush or sea) to 1 (solidly urban).
     * Bilinear, and clamped to the lattice edge outside the region.
     */
    public double at(double lat, double lng) {
        double fr = clamp((north - lat) / (north - south) * (rows - 1), 0, rows - 1.0);
        double fc = clamp((lng - west) / (east - west) * (cols - 1), 0, cols - 1.0);

        int r0 = (int) Math.floor(fr), c0 = (int) Math.floor(fc);
        int r1 = Math.min(rows - 1, r0 + 1), c1 = Math.min(cols - 1, c0 + 1);
        double tr = fr - r0, tc = fc - c0;

        double top = sample(r0, c0) * (1 - tc) + sample(r0, c1) * tc;
        double bottom = sample(r1, c0) * (1 - tc) + sample(r1, c1) * tc;
        return (top * (1 - tr) + bottom * tr) / 255.0;
    }

    private int sample(int row, int col) {
        return samples[row * cols + col] & 0xFF;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
