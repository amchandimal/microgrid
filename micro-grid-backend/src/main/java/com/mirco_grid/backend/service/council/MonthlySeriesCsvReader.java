package com.mirco_grid.backend.service.council;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the two monthly series, Jan 2001 to Dec 2025.
 *
 * <p>They are the same table twice - residential, commercial and power-station
 * totals per month - but exported by different hands. The capacity file writes
 * its dates as {@code Jan 2001} and stops at the third column; the
 * installations file writes {@code Jan-01}, leaves a trailing empty column on
 * every row, and finishes with an unlabelled totals row. Both are handled by
 * trying each date format and ignoring any row whose first cell is not a
 * month, which drops that totals row without having to know it is last.
 */
final class MonthlySeriesCsvReader {

    private MonthlySeriesCsvReader() {}

    /** One month of a series. {@code month} is ISO {@code yyyy-MM}, so it sorts. */
    record Point(String month, double residential, double commercial, double powerStations) {}

    /** "Jan 2001" - the capacity export. */
    private static final DateTimeFormatter LONG_YEAR =
            DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    /** "Jan-01" - the installations export. Two-digit years are 2000s. */
    private static final DateTimeFormatter SHORT_YEAR =
            DateTimeFormatter.ofPattern("MMM-yy", Locale.ENGLISH);

    static List<Point> read(String classpathFile) throws IOException {
        List<Point> points = new ArrayList<>();

        try (Reader in = new InputStreamReader(EnergyDataFiles.open(classpathFile), StandardCharsets.UTF_8);
                CSVReader csv = new CSVReader(in)) {

            String[] cells;
            while ((cells = readNext(csv)) != null) {
                if (cells.length < 4) {
                    continue;
                }
                YearMonth month = parseMonth(cells[0]);
                if (month == null) {
                    // The header row, and the installations file's totals row.
                    continue;
                }
                points.add(new Point(
                        month.toString(),
                        DataValues.numberOrZero(cells[1]),
                        DataValues.numberOrZero(cells[2]),
                        DataValues.numberOrZero(cells[3])));
            }
        }
        return points;
    }

    /** @return the month, or null when the cell is not one. */
    private static YearMonth parseMonth(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter format : new DateTimeFormatter[] {LONG_YEAR, SHORT_YEAR}) {
            try {
                return YearMonth.parse(text, format);
            } catch (DateTimeParseException ignored) {
                // Try the other layout before giving up on the row.
            }
        }
        return null;
    }

    private static String[] readNext(CSVReader csv) throws IOException {
        try {
            return csv.readNext();
        } catch (CsvValidationException e) {
            throw new IOException("Malformed row in a monthly series CSV", e);
        }
    }
}
