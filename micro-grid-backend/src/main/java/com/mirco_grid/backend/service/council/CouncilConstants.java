package com.mirco_grid.backend.service.council;

import com.mirco_grid.backend.service.council.CouncilApi.EapaVouchers;
import com.mirco_grid.backend.service.council.CouncilApi.SourceNote;

/**
 * Figures the dashboard quotes that are not in the files it loads.
 *
 * <p>All of these come from the NSW Energy Social Programs Annual Report
 * 2022-23 Data Workbook, which is a nine-megabyte file whose bulk is chart
 * images. Rather than ship it to extract eleven numbers, the numbers are
 * written down here with the table they came from, and every one of them is
 * sent to the browser with that citation attached so the provenance shows on
 * screen rather than living only in this comment.
 */
final class CouncilConstants {

    private CouncilConstants() {}

    static final String DATA_WORKBOOK =
            "NSW Energy Social Programs Annual Report 2022-23, Data Workbook";

    /** Table 24-A, flat tariff: the tariff most Wollongong households are on. */
    static final double AVG_ANNUAL_BILL_AUD = 1521;
    /** Table 24-A, flat tariff, excluding solar feed-in credit and export. */
    static final double AVG_GRID_COST_C_KWH = 35.8;

    /** Table 15: EAPA applications approved statewide across FY2022-23. */
    static final EapaVouchers EAPA_FY_2022_23 = new EapaVouchers(
            "FY2022-23",
            36462 + 19485,
            10216 + 3999,
            21_962_950,
            DATA_WORKBOOK + ", Table 15");

    static final SourceNote BILL_SOURCE = new SourceNote(
            "Average annualised electricity bill $1,521/yr and grid cost 35.8 c/kWh",
            DATA_WORKBOOK + ", Table 24-A (flat tariff, NSW average)");

    static final SourceNote SOLAR_SOURCE = new SourceNote(
            "Installations, capacity, savings and CO2 offsets",
            "Wollongong City Council, Installations+Capacity by Suburb FY 24-25 "
                    + "and Total System Capacity/Installations, Jan 2001 - Dec 2025");

    static final SourceNote CONSUMPTION_SOURCE = new SourceNote(
            "Electricity consumption and customer accounts by postcode, 2021/22",
            "Wollongong LGA electricity consumption 21-22.xlsx");

    static final SourceNote REBATE_SOURCE = new SourceNote(
            "Rebate accounts, amounts paid and eligible customers, FY2017-18 to FY2022-23",
            "NSW Energy Social Programs Annual Report 2022-23, "
                    + "Trends Analysis workbook, Table 1 (NSW-wide)");

    static final SourceNote GRID_SOURCE = new SourceNote(
            "Live supply, demand and export headroom",
            "Micro-Grid GridService, the same model the public map draws");
}
