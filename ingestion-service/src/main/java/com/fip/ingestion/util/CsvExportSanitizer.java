package com.fip.ingestion.util;

import java.util.Set;

/**
 * Utility for sanitizing financial data at the CSV/Spreadsheet EXPORT boundary.
 * <p>
 * In accordance with financial data integrity principles:
 * - Data MUST NOT be mutated or prepended with quote characters on ingestion/import.
 * - Formula-injection escaping ('=', '+', '-', '@', '\t', '\r') is applied exclusively
 *   when formatting output for spreadsheet exports to prevent client spreadsheet execution.
 */
public final class CsvExportSanitizer {

    private static final Set<Character> FORMULA_INJECTION_CHARS = Set.of('=', '+', '-', '@', '\t', '\r');

    private CsvExportSanitizer() {
        // Utility class
    }

    /**
     * Sanitizes a cell value for spreadsheet export by prefixing formula triggers with a single quote.
     *
     * @param value the raw financial cell value
     * @return the sanitized cell string for CSV/Excel export
     */
    public static String sanitizeForExport(String value) {
        if (value == null) {
            return null;
        }
        if (value.isEmpty()) {
            return "";
        }
        char firstChar = value.charAt(0);
        if (FORMULA_INJECTION_CHARS.contains(firstChar)) {
            return "'" + value;
        }
        return value;
    }
}
