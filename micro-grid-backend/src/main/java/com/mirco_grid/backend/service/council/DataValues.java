package com.mirco_grid.backend.service.council;

/**
 * The house style of the Energy Equity Challenge files.
 *
 * <p>Every number arrives as a quoted string with thousands separators
 * ({@code "27,776"}), a missing value is a hyphen rather than an empty cell,
 * percentages carry their sign, headline figures are prefixed with a tilde or
 * a dollar sign and sometimes suffixed with a unit ({@code "154,000 tonnes"}),
 * and a handful of localities carry a footnote marker ({@code "Avon *"}).
 * Everything that has to be undone before a value can be stored lives here so
 * the loaders read as the shape of their file rather than as string surgery.
 */
final class DataValues {

    private DataValues() {}

    /**
     * A number out of a cell, or null where the file means "no data".
     *
     * <p>Keeps the digits, one decimal point and a leading minus; drops the
     * separators, currency, tilde, percent sign and any trailing unit. A cell
     * with no digits at all - {@code "-"}, {@code "n/a"}, blank - is null,
     * which is the distinction the equity index needs: a suburb with no
     * reported growth is not a suburb with zero growth.
     */
    static Double number(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equals("-") || trimmed.equalsIgnoreCase("n/a")) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (c == '.' && digits.indexOf(".") < 0) {
                digits.append(c);
            } else if (c == '-' && digits.isEmpty()) {
                digits.append(c);
            }
        }
        String cleaned = digits.toString();
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".") || cleaned.equals("-.")) {
            return null;
        }
        try {
            return Double.valueOf(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** As {@link #number}, rounded - installation counts are never fractional. */
    static Integer integer(String raw) {
        Double value = number(raw);
        return value == null ? null : (int) Math.round(value);
    }

    /** {@code number} with the file's "no data" hyphen read as zero instead. */
    static double numberOrZero(String raw) {
        Double value = number(raw);
        return value == null ? 0 : value;
    }

    /** As {@link #numberOrZero}, for counts. */
    static int integerOrZero(String raw) {
        Integer value = integer(raw);
        return value == null ? 0 : value;
    }

    /**
     * A locality name without its footnote marker.
     *
     * <p>{@code "Avon *"} and {@code "Woronora Dam *"} are footnoted in the
     * source as localities that straddle the LGA boundary. The marker has to
     * come off or they will not join to anything else keyed on the name.
     */
    static String locality(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        while (name.endsWith("*")) {
            name = name.substring(0, name.length() - 1).trim();
        }
        return name;
    }

    /**
     * A label without the superscript footnote the spreadsheet glued onto it.
     *
     * <p>Table 1 of the rebates workbook writes "NSW Gas Rebate5" and "Total
     * customer accounts1" - the trailing digits are footnote markers, not part
     * of the name, and they differ between rows that mean the same thing.
     * Digits that belong to the label (a year, say) are kept by only stripping
     * when what is left still ends in a letter or a bracket.
     */
    static String withoutFootnote(String raw) {
        if (raw == null) {
            return null;
        }
        String label = raw.trim();
        int end = label.length();
        while (end > 0 && Character.isDigit(label.charAt(end - 1))) {
            end--;
        }
        if (end == label.length() || end == 0) {
            return label;
        }
        char preceding = label.charAt(end - 1);
        if (Character.isLetter(preceding) || preceding == ')') {
            return label.substring(0, end);
        }
        return label;
    }
}
