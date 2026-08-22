package com.mirco_grid.backend.service.council;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The bridge between the two halves of the council data.
 *
 * <p>Solar installations are reported by suburb and electricity consumption by
 * postcode, so nothing joins until every locality has a postcode against it.
 * The same seed carries an approximate centre for each locality, which is what
 * lets the equity layer be drawn on the existing Leaflet map - the source
 * files have no geometry of any kind.
 *
 * <p>See {@code illawarra/suburb-seed.csv} for where those numbers come from
 * and how accurate they are.
 */
@Component
public class SuburbSeed {

    private static final String RESOURCE = "illawarra/suburb-seed.csv";

    /**
     * Postcodes the Wollongong LGA only partly covers.
     *
     * <p>2528 is mostly Shellharbour - Warilla, Barrack Heights, Blackbutt -
     * and Windang is the only Wollongong locality in it. Its consumption and
     * its ten thousand accounts belong overwhelmingly to another council, so
     * anything that treats a postcode as a Wollongong unit has to leave it out:
     * a density of Windang's installations over the whole postcode's accounts
     * would read as 3%, and its load on a Wollongong chart is not Wollongong's.
     * Per-dwelling averages are unaffected, because an average does not care
     * which side of the boundary the dwellings are on.
     */
    public static final Set<String> PARTIAL_POSTCODES = Set.of("2528");

    /** One seeded locality. {@code lat}/{@code lng} are null when unmapped. */
    public record Locality(String name, String postcode, Double lat, Double lng) {
        public boolean hasPoint() {
            return lat != null && lng != null;
        }
    }

    private final Map<String, Locality> byName = new LinkedHashMap<>();

    public SuburbSeed() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] cells = trimmed.split(",", -1);
                if (cells.length < 2) {
                    continue;
                }
                String name = cells[0].trim();
                String postcode = cells[1].trim();
                Double lat = cells.length > 2 ? DataValues.number(cells[2]) : null;
                Double lng = cells.length > 3 ? DataValues.number(cells[3]) : null;
                byName.put(key(name), new Locality(
                        name, postcode.isEmpty() ? null : postcode, lat, lng));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + RESOURCE, e);
        }
    }

    /** Every seeded locality, in the order the file lists them. */
    public List<Locality> all() {
        return List.copyOf(byName.values());
    }

    /** @return the seed for a locality, or null when it is not in the file. */
    public Locality find(String locality) {
        return locality == null ? null : byName.get(key(locality));
    }

    public String postcodeOf(String locality) {
        Locality seed = find(locality);
        return seed == null ? null : seed.postcode();
    }

    /** Every locality sharing a postcode, in seed order. */
    public List<String> localitiesIn(String postcode) {
        List<String> names = new ArrayList<>();
        for (Locality seed : byName.values()) {
            if (postcode != null && postcode.equals(seed.postcode())) {
                names.add(seed.name());
            }
        }
        return names;
    }

    /** Names differ in case and spacing between the source files. */
    private static String key(String name) {
        return name.trim().toLowerCase();
    }
}
