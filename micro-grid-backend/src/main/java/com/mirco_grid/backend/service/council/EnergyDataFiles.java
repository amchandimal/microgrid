package com.mirco_grid.backend.service.council;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;

/**
 * The Energy Equity Challenge files, as shipped.
 *
 * <p>They live on the classpath under {@code data/} under their original
 * names, so a refreshed drop from Council can be dropped straight in without
 * renaming anything.
 */
final class EnergyDataFiles {

    private EnergyDataFiles() {}

    static final String SUBURB_SOLAR_FY = "data/Installations+Capacity by Suburb - FY 24-25.csv";
    static final String SUBURB_SOLAR_ALL_TIME = "data/Installations+Capacity by suburb - All Time.csv";
    static final String MONTHLY_CAPACITY = "data/Total System Capacity - Wollongong - All Time.csv";
    static final String MONTHLY_INSTALLATIONS = "data/Total System Installations - Wollongong - All Time.csv";
    static final String CONSUMPTION = "data/Wollongong LGA electricity consumption 21-22.xlsx";
    static final String REBATE_TRENDS =
            "data/NSW_Energy_Social_Programs_Annual_Report_2022_2023_Trends_Analysis.xlsx";

    static boolean exists(String path) {
        return new ClassPathResource(path).exists();
    }

    static InputStream open(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IOException("Missing data file on the classpath: " + path);
        }
        return resource.getInputStream();
    }
}
