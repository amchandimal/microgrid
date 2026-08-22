package com.mirco_grid.backend.service.council;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The council store, as plain DDL.
 *
 * <p>The Energy Equity Challenge ships spreadsheets, not an API, so the shape
 * of every table here is dictated by the file it is loaded from. That is also
 * why this is straight JDBC rather than JPA: there is no domain model to map,
 * only five files that have to land in SQLite in a form the dashboard can
 * query.
 */
final class EnergyDataSchema {

    private EnergyDataSchema() {}

    /** The key/value block at the top of both suburb CSVs. */
    static final String LGA_SUMMARY = """
            CREATE TABLE IF NOT EXISTS lga_summary (
              key   TEXT PRIMARY KEY,
              value TEXT
            )""";

    /** "Installations+Capacity by Suburb - FY 24-25.csv", one row per locality. */
    static final String SUBURB_SOLAR = """
            CREATE TABLE IF NOT EXISTS suburb_solar (
              locality             TEXT PRIMARY KEY,
              res_installs_alltime INTEGER,
              res_kw_alltime       REAL,
              res_installs_fy      INTEGER,
              res_kw_fy            REAL,
              res_yoy_pct          REAL,
              com_installs_alltime INTEGER,
              com_kw_alltime       REAL,
              com_installs_fy      INTEGER,
              com_kw_fy            REAL,
              com_yoy_pct          REAL,
              ps_installs_fy       INTEGER,
              ps_kw_fy             REAL
            )""";

    /**
     * "Installations+Capacity by suburb - All Time.csv".
     *
     * <p>Not a duplicate of the all-time columns above: the FY workbook counts
     * up to the end of FY24-25, this one to the end of the series (Dec 2025).
     * Austinmer reads 427 in one and 452 in the other for that reason, so both
     * are kept and each is labelled with its as-at date in the UI.
     */
    static final String SUBURB_SOLAR_CURRENT = """
            CREATE TABLE IF NOT EXISTS suburb_solar_current (
              locality     TEXT PRIMARY KEY,
              res_installs INTEGER,
              res_kw       REAL,
              com_installs INTEGER,
              com_kw       REAL,
              ps_installs  INTEGER,
              ps_kw        REAL
            )""";

    /** kW added per month, "Total System Capacity - Wollongong - All Time.csv". */
    static final String MONTHLY_CAPACITY = """
            CREATE TABLE IF NOT EXISTS monthly_capacity (
              month          TEXT PRIMARY KEY,
              residential    REAL,
              commercial     REAL,
              power_stations REAL
            )""";

    /** Systems added per month, "Total System Installations - ... .csv". */
    static final String MONTHLY_INSTALLATIONS = """
            CREATE TABLE IF NOT EXISTS monthly_installations (
              month          TEXT PRIMARY KEY,
              residential    INTEGER,
              commercial     INTEGER,
              power_stations INTEGER
            )""";

    /**
     * "Wollongong LGA electricity consumption 21-22.xlsx".
     *
     * <p>The sheet carries a second block of customer-account counts beside
     * the energy figures; those are kept because they are the only dwelling
     * count in the whole dataset below LGA level, which is what turns raw MWh
     * into MWh per dwelling for the equity index.
     */
    static final String POSTCODE_CONSUMPTION = """
            CREATE TABLE IF NOT EXISTS postcode_consumption (
              postcode                TEXT PRIMARY KEY,
              total_mwh               REAL,
              domestic_controlled_mwh REAL,
              domestic_mwh            REAL,
              controlled_load_mwh     REAL,
              commercial_mwh          REAL,
              industrial_mwh          REAL,
              total_accounts          INTEGER,
              domestic_accounts       INTEGER
            )""";

    /** Table 1 of the NSW Energy Social Programs trends workbook, unpivoted. */
    static final String REBATE_TREND = """
            CREATE TABLE IF NOT EXISTS rebate_trend (
              program TEXT,
              metric  TEXT,
              fy      TEXT,
              value   REAL,
              PRIMARY KEY (program, metric, fy)
            )""";

    /** Seed: solar is reported by suburb, consumption by postcode. */
    static final String SUBURB_POSTCODE = """
            CREATE TABLE IF NOT EXISTS suburb_postcode (
              locality TEXT PRIMARY KEY,
              postcode TEXT
            )""";

    private static final List<String> ALL = List.of(
            LGA_SUMMARY,
            SUBURB_SOLAR,
            SUBURB_SOLAR_CURRENT,
            MONTHLY_CAPACITY,
            MONTHLY_INSTALLATIONS,
            POSTCODE_CONSUMPTION,
            REBATE_TREND,
            SUBURB_POSTCODE);

    static void create(JdbcClient jdbc) {
        ALL.forEach(ddl -> jdbc.sql(ddl).update());
    }
}
