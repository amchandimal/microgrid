package com.mirco_grid.backend.service.council;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads either of the two "Installations+Capacity by suburb" CSVs.
 *
 * <p>Both files open with the same fifteen-line LGA summary, a blank line and
 * three stacked header rows - a group row, a period row and the column names -
 * before the suburb rows start. The two differ only in what those columns are:
 * the FY file reports since-2001 and financial-year figures side by side
 * (thirteen columns), the all-time file reports since-2001 alone (seven). So
 * the preamble is read once here and the rows are handed back as raw cells for
 * the caller to map to its own shape.
 *
 * <p>Nothing keys off a line number: the summary runs until the first blank
 * line and the suburb rows start after the row whose first cell is
 * {@code Locality}. A re-export with one more headline figure still loads.
 */
final class SuburbSolarCsvReader {

    private SuburbSolarCsvReader() {}

    /** The preamble and the suburb rows of one file. */
    record Parsed(Map<String, String> lgaSummary, List<String[]> rows) {}

    private static final String LOCALITY_HEADER = "Locality";

    /**
     * The aggregate row the source appends for installations it could not
     * attribute to a locality. Real for the LGA total, meaningless as a
     * suburb, so it never reaches the league table or the equity index.
     */
    static final String UNATTRIBUTED_ROW = "Other";

    static Parsed read(String classpathFile) throws IOException {
        Map<String, String> summary = new LinkedHashMap<>();
        List<String[]> rows = new ArrayList<>();

        try (Reader in = new InputStreamReader(EnergyDataFiles.open(classpathFile), StandardCharsets.UTF_8);
                CSVReader csv = new CSVReader(in)) {

            boolean inSummary = true;
            boolean pastHeader = false;
            String[] cells;
            while ((cells = readNext(csv)) != null) {
                String first = cells.length > 0 ? cells[0].trim() : "";

                if (inSummary) {
                    // The summary ends at the blank line before the headers.
                    if (first.isEmpty()) {
                        inSummary = false;
                        continue;
                    }
                    if (cells.length >= 2) {
                        summary.put(first, cells[1].trim());
                    }
                    continue;
                }

                if (!pastHeader) {
                    pastHeader = first.equalsIgnoreCase(LOCALITY_HEADER);
                    continue;
                }

                String locality = DataValues.locality(first);
                if (locality == null || locality.isEmpty()) {
                    continue;
                }
                rows.add(cells);
            }
        }
        return new Parsed(summary, rows);
    }

    private static String[] readNext(CSVReader csv) throws IOException {
        try {
            return csv.readNext();
        } catch (CsvValidationException e) {
            throw new IOException("Malformed row in a suburb CSV", e);
        }
    }

    /** A cell by index, or empty when the row is shorter than the header. */
    static String cell(String[] row, int index) {
        return index < row.length ? row[index] : "";
    }
}
