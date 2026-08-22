package com.mirco_grid.backend.service.council;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads Table 1 of the NSW Energy Social Programs trends workbook.
 *
 * <p>Table 1 is a stack of blocks, one per rebate program, each five rows
 * deep - accounts, dollars paid, unique customers, switching rate, eligible
 * customers - across FY2017-18 to FY2022-23. The program name appears only on
 * the first row of its block and the rest of the block leaves column A empty,
 * so the name is carried down. Both the program names and the metric names
 * have footnote digits welded onto them ("NSW Gas Rebate5"), which come off
 * here or nothing matches between blocks.
 *
 * <p>The pair worth having is accounts against eligible customers: the gap
 * between them is households entitled to a rebate that are not receiving one.
 */
final class RebateWorkbookReader {

    private RebateWorkbookReader() {}

    /** One cell of Table 1, unpivoted. */
    record Point(String program, String metric, String fy, double value) {}

    /** The sheet the trends workbook keeps Table 1 on. */
    private static final String SHEET = "Table 1";
    /** Column A of the header row, which is what locates the table. */
    private static final String HEADER_FIRST_CELL = "Rebate";
    private static final int COL_PROGRAM = 0;
    private static final int COL_METRIC = 1;
    private static final int FIRST_FY_COLUMN = 2;

    static List<Point> read() throws IOException {
        List<Point> points = new ArrayList<>();

        try (InputStream in = EnergyDataFiles.open(EnergyDataFiles.REBATE_TRENDS);
                Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheet(SHEET);
            if (sheet == null) {
                throw new IOException("The rebates workbook has no sheet named " + SHEET);
            }

            int headerRow = headerRow(sheet);
            List<String> years = financialYears(sheet.getRow(headerRow));
            String program = null;

            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String firstCell = text(row.getCell(COL_PROGRAM));
                String metric = DataValues.withoutFootnote(text(row.getCell(COL_METRIC)));

                if (metric.isEmpty()) {
                    // The notes block below the table starts here.
                    if (firstCell.equalsIgnoreCase("Notes")) {
                        break;
                    }
                    continue;
                }
                if (!firstCell.isEmpty()) {
                    program = DataValues.withoutFootnote(firstCell);
                }
                if (program == null) {
                    continue;
                }

                for (int i = 0; i < years.size(); i++) {
                    Double value = numeric(row.getCell(FIRST_FY_COLUMN + i));
                    // "n/a" is a real answer here - the Seniors rebate did not
                    // exist before 2019 - so it is left out rather than zeroed.
                    if (value != null) {
                        points.add(new Point(program, metric, years.get(i), value));
                    }
                }
            }
        }
        return points;
    }

    private static int headerRow(Sheet sheet) throws IOException {
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 20); r++) {
            Row row = sheet.getRow(r);
            if (row != null && text(row.getCell(COL_PROGRAM)).equalsIgnoreCase(HEADER_FIRST_CELL)) {
                return r;
            }
        }
        throw new IOException("Could not find the header row of " + SHEET);
    }

    /** The FY labels across the header row, left to right. */
    private static List<String> financialYears(Row header) {
        List<String> years = new ArrayList<>();
        if (header == null) {
            return years;
        }
        for (int c = FIRST_FY_COLUMN; c < header.getLastCellNum(); c++) {
            String label = text(header.getCell(c));
            if (label.isEmpty()) {
                break;
            }
            years.add(label);
        }
        return years;
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            default -> "";
        };
    }

    /** @return the number, or null for the sheet's "n/a" and for blanks. */
    private static Double numeric(Cell cell) {
        if (cell == null || cell.getCellType() != CellType.NUMERIC) {
            return null;
        }
        return cell.getNumericCellValue();
    }
}
