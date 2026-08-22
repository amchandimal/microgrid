package com.mirco_grid.backend.service.council;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads "Wollongong LGA electricity consumption 21-22.xlsx".
 *
 * <p>One sheet, two blocks side by side: MWh of energy in columns A-G and the
 * matching customer-account counts in I-O, both keyed on postcode and both
 * under the same two header rows. Only the postcodes are named, so the second
 * block is read positionally off the first.
 *
 * <p>The account counts matter as much as the energy: they are the only
 * dwelling count anywhere below LGA level, and domestic MWh over domestic
 * accounts is what makes consumption comparable between a postcode of three
 * thousand homes and one of twenty thousand.
 */
final class ConsumptionWorkbookReader {

    private ConsumptionWorkbookReader() {}

    /** One postcode. Energy in MWh for 2021/22, accounts as at the same year. */
    record Row2122(
            String postcode,
            double totalMwh,
            double domesticControlledMwh,
            double domesticMwh,
            double controlledLoadMwh,
            double commercialMwh,
            double industrialMwh,
            int totalAccounts,
            int domesticAccounts) {}

    /** Rows below the table (blanks, notes) are skipped by failing this. */
    private static final Pattern POSTCODE =
            Pattern.compile("[0-9]{4}");

    // Energy block.
    private static final int COL_POSTCODE = 0;
    private static final int COL_TOTAL = 1;
    private static final int COL_DOMESTIC_CONTROLLED = 2;
    private static final int COL_DOMESTIC = 3;
    private static final int COL_CONTROLLED_LOAD = 4;
    private static final int COL_COMMERCIAL = 5;
    private static final int COL_INDUSTRIAL = 6;
    // Customer-numbers block, same column order after the spacer column H.
    private static final int COL_ACCOUNTS_TOTAL = 9;
    private static final int COL_ACCOUNTS_DOMESTIC = 11;

    static List<Row2122> read() throws IOException {
        List<Row2122> rows = new ArrayList<>();

        try (InputStream in = EnergyDataFiles.open(EnergyDataFiles.CONSUMPTION);
                Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheetAt(0);
            int firstData = firstDataRow(sheet);

            for (int r = firstData; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String postcode = text(row.getCell(COL_POSTCODE));
                if (!POSTCODE.matcher(postcode).matches()) {
                    continue;
                }
                rows.add(new Row2122(
                        postcode,
                        numeric(row.getCell(COL_TOTAL)),
                        numeric(row.getCell(COL_DOMESTIC_CONTROLLED)),
                        numeric(row.getCell(COL_DOMESTIC)),
                        numeric(row.getCell(COL_CONTROLLED_LOAD)),
                        numeric(row.getCell(COL_COMMERCIAL)),
                        numeric(row.getCell(COL_INDUSTRIAL)),
                        (int) Math.round(numeric(row.getCell(COL_ACCOUNTS_TOTAL))),
                        (int) Math.round(numeric(row.getCell(COL_ACCOUNTS_DOMESTIC)))));
            }
        }
        return rows;
    }

    /** The row after the one labelled "Postcode", rather than a fixed offset. */
    private static int firstDataRow(Sheet sheet) {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 10); r++) {
            Row row = sheet.getRow(r);
            if (row != null && text(row.getCell(COL_POSTCODE)).equalsIgnoreCase("Postcode")) {
                return r + 1;
            }
        }
        // Two header rows, as shipped.
        return 2;
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            // A postcode typed as a number still has to join to the CSV's text.
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default -> "";
        };
    }

    private static double numeric(Cell cell) {
        if (cell == null) {
            return 0;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        Double parsed = DataValues.number(text(cell));
        return parsed == null ? 0 : parsed;
    }
}
