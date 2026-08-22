package com.mirco_grid.backend.service.council;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads the council store back out.
 *
 * <p>One row record per table, matching the file it was loaded from rather
 * than anything the dashboard shows - the joining and the arithmetic belong
 * further up, in {@link EquityIndexService} and {@link CouncilDashboardService}.
 * Nulls are preserved on the way out: a suburb with no reported year-on-year
 * growth is not a suburb that grew by zero, and the index has to be able to
 * tell those apart.
 */
@Repository
public class CouncilDataRepository {

    private final JdbcClient jdbc;

    public CouncilDataRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One locality of "Installations+Capacity by Suburb - FY 24-25.csv". */
    public record SolarRow(
            String locality,
            Integer resInstallsAlltime,
            Double resKwAlltime,
            Integer resInstallsFy,
            Double resKwFy,
            Double resYoyPct,
            Integer comInstallsAlltime,
            Double comKwAlltime,
            Integer comInstallsFy,
            Double comKwFy,
            Double comYoyPct,
            Integer psInstallsFy,
            Double psKwFy) {}

    /** One locality of the all-time CSV, which runs to the end of the series. */
    public record CurrentSolarRow(
            String locality,
            Integer resInstalls,
            Double resKw,
            Integer comInstalls,
            Double comKw,
            Integer psInstalls,
            Double psKw) {}

    public record MonthlyRow(
            String month, double residential, double commercial, double powerStations) {}

    public record ConsumptionRow(
            String postcode,
            double totalMwh,
            double domesticControlledMwh,
            double domesticMwh,
            double controlledLoadMwh,
            double commercialMwh,
            double industrialMwh,
            int totalAccounts,
            int domesticAccounts) {}

    public record RebateRow(String program, String metric, String fy, double value) {}

    public Map<String, String> lgaSummary() {
        Map<String, String> summary = new LinkedHashMap<>();
        jdbc.sql("SELECT key, value FROM lga_summary")
                .query((RowCallbackHandler) rs ->
                        summary.put(rs.getString("key"), rs.getString("value")));
        return summary;
    }

    public List<SolarRow> suburbSolar() {
        return jdbc.sql("""
                SELECT locality, res_installs_alltime, res_kw_alltime,
                       res_installs_fy, res_kw_fy, res_yoy_pct,
                       com_installs_alltime, com_kw_alltime,
                       com_installs_fy, com_kw_fy, com_yoy_pct,
                       ps_installs_fy, ps_kw_fy
                  FROM suburb_solar
                 ORDER BY locality""")
                .query((rs, rowNum) -> new SolarRow(
                        rs.getString("locality"),
                        intOrNull(rs, "res_installs_alltime"),
                        doubleOrNull(rs, "res_kw_alltime"),
                        intOrNull(rs, "res_installs_fy"),
                        doubleOrNull(rs, "res_kw_fy"),
                        doubleOrNull(rs, "res_yoy_pct"),
                        intOrNull(rs, "com_installs_alltime"),
                        doubleOrNull(rs, "com_kw_alltime"),
                        intOrNull(rs, "com_installs_fy"),
                        doubleOrNull(rs, "com_kw_fy"),
                        doubleOrNull(rs, "com_yoy_pct"),
                        intOrNull(rs, "ps_installs_fy"),
                        doubleOrNull(rs, "ps_kw_fy")))
                .list();
    }

    public Map<String, CurrentSolarRow> suburbSolarCurrentByLocality() {
        Map<String, CurrentSolarRow> byLocality = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT locality, res_installs, res_kw, com_installs, com_kw, ps_installs, ps_kw
                  FROM suburb_solar_current
                 ORDER BY locality""")
                .query((rs, rowNum) -> new CurrentSolarRow(
                        rs.getString("locality"),
                        intOrNull(rs, "res_installs"),
                        doubleOrNull(rs, "res_kw"),
                        intOrNull(rs, "com_installs"),
                        doubleOrNull(rs, "com_kw"),
                        intOrNull(rs, "ps_installs"),
                        doubleOrNull(rs, "ps_kw")))
                .list()
                .forEach(row -> byLocality.put(row.locality(), row));
        return byLocality;
    }

    public List<MonthlyRow> monthlyCapacity() {
        return monthly("monthly_capacity");
    }

    public List<MonthlyRow> monthlyInstallations() {
        return monthly("monthly_installations");
    }

    private List<MonthlyRow> monthly(String table) {
        return jdbc.sql("SELECT month, residential, commercial, power_stations FROM " + table
                        + " ORDER BY month")
                .query((rs, rowNum) -> new MonthlyRow(
                        rs.getString("month"),
                        rs.getDouble("residential"),
                        rs.getDouble("commercial"),
                        rs.getDouble("power_stations")))
                .list();
    }

    public List<ConsumptionRow> postcodeConsumption() {
        return jdbc.sql("""
                SELECT postcode, total_mwh, domestic_controlled_mwh, domestic_mwh,
                       controlled_load_mwh, commercial_mwh, industrial_mwh,
                       total_accounts, domestic_accounts
                  FROM postcode_consumption
                 ORDER BY postcode""")
                .query((rs, rowNum) -> new ConsumptionRow(
                        rs.getString("postcode"),
                        rs.getDouble("total_mwh"),
                        rs.getDouble("domestic_controlled_mwh"),
                        rs.getDouble("domestic_mwh"),
                        rs.getDouble("controlled_load_mwh"),
                        rs.getDouble("commercial_mwh"),
                        rs.getDouble("industrial_mwh"),
                        rs.getInt("total_accounts"),
                        rs.getInt("domestic_accounts")))
                .list();
    }

    /** Postcodes with no reported load at all are dropped - 2520-2522 read zero. */
    public Map<String, ConsumptionRow> consumptionByPostcode() {
        Map<String, ConsumptionRow> byPostcode = new LinkedHashMap<>();
        for (ConsumptionRow row : postcodeConsumption()) {
            if (row.totalMwh() > 0) {
                byPostcode.put(row.postcode(), row);
            }
        }
        return byPostcode;
    }

    /**
     * SQLite is dynamically typed and the driver decides between Integer and
     * Long by magnitude, so every nullable column is read through these rather
     * than cast out of getObject.
     */
    private static Integer intOrNull(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double doubleOrNull(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    public List<RebateRow> rebateTrend() {
        return jdbc.sql("SELECT program, metric, fy, value FROM rebate_trend ORDER BY program, fy")
                .query((rs, rowNum) -> new RebateRow(
                        rs.getString("program"),
                        rs.getString("metric"),
                        rs.getString("fy"),
                        rs.getDouble("value")))
                .list();
    }
}
