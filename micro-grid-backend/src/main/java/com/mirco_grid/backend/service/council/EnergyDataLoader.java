package com.mirco_grid.backend.service.council;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the Energy Equity Challenge files into SQLite on startup.
 *
 * <p>Idempotent by table: the schema is created if it is not there and each
 * table is filled only when it is empty, so a restart costs nothing and
 * deleting {@code microgrid.db} is all it takes to reload. The seeded
 * suburb-to-postcode mapping is the exception - it is rewritten every time,
 * because it is code rather than data and a change to it should take effect
 * without anyone having to remember to drop the database first.
 */
@Component
@Order(0)
public class EnergyDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EnergyDataLoader.class);

    private final JdbcClient jdbc;
    private final JdbcTemplate template;
    private final SuburbSeed seed;

    public EnergyDataLoader(JdbcClient jdbc, JdbcTemplate template, SuburbSeed seed) {
        this.jdbc = jdbc;
        this.template = template;
        this.seed = seed;
    }

    @Override
    @Transactional
    public void run(String... args) throws IOException {
        EnergyDataSchema.create(jdbc);

        loadSuburbPostcode();
        loadSuburbSolar();
        loadSuburbSolarCurrent();
        loadMonthly("monthly_capacity", EnergyDataFiles.MONTHLY_CAPACITY, false);
        loadMonthly("monthly_installations", EnergyDataFiles.MONTHLY_INSTALLATIONS, true);
        loadPostcodeConsumption();
        loadRebateTrend();
    }

    private boolean isEmpty(String table) {
        Integer rows = jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
        return rows == null || rows == 0;
    }

    // --- seeds ---------------------------------------------------------------

    private void loadSuburbPostcode() {
        template.update("DELETE FROM suburb_postcode");
        List<Object[]> rows = new ArrayList<>();
        for (SuburbSeed.Locality locality : seed.all()) {
            if (locality.postcode() != null) {
                rows.add(new Object[] {locality.name(), locality.postcode()});
            }
        }
        template.batchUpdate(
                "INSERT OR REPLACE INTO suburb_postcode (locality, postcode) VALUES (?, ?)", rows);
        log.info("Council data: seeded {} suburb-to-postcode mappings", rows.size());
    }

    // --- suburb solar --------------------------------------------------------

    private void loadSuburbSolar() throws IOException {
        if (!isEmpty("suburb_solar")) {
            return;
        }
        SuburbSolarCsvReader.Parsed parsed =
                SuburbSolarCsvReader.read(EnergyDataFiles.SUBURB_SOLAR_FY);

        loadLgaSummary(parsed.lgaSummary());

        List<Object[]> rows = new ArrayList<>();
        for (String[] cells : parsed.rows()) {
            rows.add(new Object[] {
                DataValues.locality(SuburbSolarCsvReader.cell(cells, 0)),
                DataValues.integer(SuburbSolarCsvReader.cell(cells, 1)),
                DataValues.number(SuburbSolarCsvReader.cell(cells, 2)),
                DataValues.integer(SuburbSolarCsvReader.cell(cells, 3)),
                DataValues.number(SuburbSolarCsvReader.cell(cells, 4)),
                DataValues.number(SuburbSolarCsvReader.cell(cells, 5)),
                DataValues.integer(SuburbSolarCsvReader.cell(cells, 6)),
                DataValues.number(SuburbSolarCsvReader.cell(cells, 7)),
                DataValues.integer(SuburbSolarCsvReader.cell(cells, 8)),
                DataValues.number(SuburbSolarCsvReader.cell(cells, 9)),
                DataValues.number(SuburbSolarCsvReader.cell(cells, 10)),
                DataValues.integer(SuburbSolarCsvReader.cell(cells, 11)),
                DataValues.number(SuburbSolarCsvReader.cell(cells, 12)),
            });
        }
        template.batchUpdate("""
                INSERT OR REPLACE INTO suburb_solar (
                  locality, res_installs_alltime, res_kw_alltime,
                  res_installs_fy, res_kw_fy, res_yoy_pct,
                  com_installs_alltime, com_kw_alltime,
                  com_installs_fy, com_kw_fy, com_yoy_pct,
                  ps_installs_fy, ps_kw_fy
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
        log.info("Council data: loaded {} suburbs from the FY 24-25 solar CSV", rows.size());
    }

    private void loadSuburbSolarCurrent() throws IOException {
        if (!isEmpty("suburb_solar_current")) {
            return;
        }
        SuburbSolarCsvReader.Parsed parsed =
                SuburbSolarCsvReader.read(EnergyDataFiles.SUBURB_SOLAR_ALL_TIME);

        List<Object[]> rows = new ArrayList<>();
        for (String[] cells : parsed.rows()) {
            rows.add(new Object[] {
                DataValues.locality(SuburbSolarCsvReader.cell(cells, 0)),
                DataValues.integer(SuburbSolarCsvReader.cell(cells, 1)),
                DataValues.number(SuburbSolarCsvReader.cell(cells, 2)),
                DataValues.integer(SuburbSolarCsvReader.cell(cells, 3)),
                DataValues.number(SuburbSolarCsvReader.cell(cells, 4)),
                DataValues.integer(SuburbSolarCsvReader.cell(cells, 5)),
                DataValues.number(SuburbSolarCsvReader.cell(cells, 6)),
            });
        }
        template.batchUpdate("""
                INSERT OR REPLACE INTO suburb_solar_current (
                  locality, res_installs, res_kw, com_installs, com_kw, ps_installs, ps_kw
                ) VALUES (?, ?, ?, ?, ?, ?, ?)""", rows);
        log.info("Council data: loaded {} suburbs from the all-time solar CSV", rows.size());
    }

    private void loadLgaSummary(Map<String, String> summary) {
        if (summary.isEmpty()) {
            return;
        }
        List<Object[]> rows = new ArrayList<>();
        summary.forEach((key, value) -> rows.add(new Object[] {key, value}));
        template.batchUpdate(
                "INSERT OR REPLACE INTO lga_summary (key, value) VALUES (?, ?)", rows);
        log.info("Council data: loaded {} LGA headline figures", rows.size());
    }

    // --- monthly series ------------------------------------------------------

    /** @param counts true for the installations file, whose values are whole systems */
    private void loadMonthly(String table, String file, boolean counts) throws IOException {
        if (!isEmpty(table)) {
            return;
        }
        List<MonthlySeriesCsvReader.Point> points = MonthlySeriesCsvReader.read(file);

        List<Object[]> rows = new ArrayList<>();
        for (MonthlySeriesCsvReader.Point point : points) {
            rows.add(new Object[] {
                point.month(),
                counts ? (Object) (int) Math.round(point.residential()) : point.residential(),
                counts ? (Object) (int) Math.round(point.commercial()) : point.commercial(),
                counts ? (Object) (int) Math.round(point.powerStations()) : point.powerStations(),
            });
        }
        template.batchUpdate("INSERT OR REPLACE INTO " + table
                + " (month, residential, commercial, power_stations) VALUES (?, ?, ?, ?)", rows);
        log.info("Council data: loaded {} months into {}", rows.size(), table);
    }

    // --- workbooks -----------------------------------------------------------

    private void loadPostcodeConsumption() throws IOException {
        if (!isEmpty("postcode_consumption")) {
            return;
        }
        List<ConsumptionWorkbookReader.Row2122> read = ConsumptionWorkbookReader.read();

        List<Object[]> rows = new ArrayList<>();
        for (ConsumptionWorkbookReader.Row2122 row : read) {
            rows.add(new Object[] {
                row.postcode(), row.totalMwh(), row.domesticControlledMwh(), row.domesticMwh(),
                row.controlledLoadMwh(), row.commercialMwh(), row.industrialMwh(),
                row.totalAccounts(), row.domesticAccounts(),
            });
        }
        template.batchUpdate("""
                INSERT OR REPLACE INTO postcode_consumption (
                  postcode, total_mwh, domestic_controlled_mwh, domestic_mwh,
                  controlled_load_mwh, commercial_mwh, industrial_mwh,
                  total_accounts, domestic_accounts
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", rows);
        log.info("Council data: loaded consumption for {} postcodes", rows.size());
    }

    private void loadRebateTrend() throws IOException {
        if (!isEmpty("rebate_trend")) {
            return;
        }
        List<RebateWorkbookReader.Point> read = RebateWorkbookReader.read();

        List<Object[]> rows = new ArrayList<>();
        for (RebateWorkbookReader.Point point : read) {
            rows.add(new Object[] {point.program(), point.metric(), point.fy(), point.value()});
        }
        template.batchUpdate(
                "INSERT OR REPLACE INTO rebate_trend (program, metric, fy, value)"
                        + " VALUES (?, ?, ?, ?)", rows);
        log.info("Council data: loaded {} rebate figures", rows.size());
    }
}
