package com.mirco_grid.backend.service;

import java.util.List;

/**
 * One hexagon of the demand/supply overlay.
 *
 * <p>Carries no geometry beyond its centre. The client rebuilds the six
 * corners from {@code q}, {@code r} and {@code sizeMetres}, which keeps the
 * payload to a fraction of what shipping rings would cost.
 */
public record GridCell(
        String id,
        int q,
        int r,
        int sizeMetres,
        double areaHectares,
        double lat,
        double lng,
        double demandKw,
        double supplyKw,
        /** supplyKw - demandKw. Positive means the cell exports. */
        double netKw,
        /** -1 all demand, 0 balanced, +1 all supply. Drives the colour. */
        double balance,
        /** How built-up the cell is, 0..1. The field everything else follows. */
        double builtUp,
        List<String> siteIds) {}
