package com.fip.ingestion.parsing;

import com.fip.ingestion.domain.model.SourceRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class CsvStreamAdapter {

    private static final Logger log = LoggerFactory.getLogger(CsvStreamAdapter.class);

    public interface RecordConsumer {
        void accept(SourceRecord record) throws Exception;
    }

    public int parseStream(
            InputStream inputStream,
            String delimiter,
            boolean hasHeader,
            int skipToRowIndex,
            RecordConsumer consumer) throws IOException {

        char delim = (delimiter != null && !delimiter.isEmpty()) ? delimiter.charAt(0) : ',';

        CSVFormat.Builder formatBuilder = CSVFormat.Builder.create()
                .setDelimiter(delim)
                .setIgnoreSurroundingSpaces(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true);

        if (hasHeader) {
            formatBuilder.setHeader();
            formatBuilder.setSkipHeaderRecord(true);
        }

        CSVFormat csvFormat = formatBuilder.build();

        // Strip UTF-8 BOM if present
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        reader.mark(1);
        if (reader.read() != 0xFEFF) {
            reader.reset();
        }

        int processedCount = 0;
        int rowIndex = 0;

        try (CSVParser parser = csvFormat.parse(reader)) {
            Map<String, Integer> headerMap = parser.getHeaderMap();

            for (CSVRecord record : parser) {
                rowIndex++; // 1-based data row index

                if (rowIndex <= skipToRowIndex) {
                    continue; // Skip already committed rows during restart recovery
                }

                Map<String, String> values = new HashMap<>();
                if (headerMap != null && !headerMap.isEmpty()) {
                    for (Map.Entry<String, Integer> entry : headerMap.entrySet()) {
                        String colName = entry.getKey();
                        int colIdx = entry.getValue();
                        if (colIdx < record.size()) {
                            String rawVal = record.get(colIdx);
                            values.put(colName, cleanValue(rawVal));
                        }
                    }
                } else {
                    for (int i = 0; i < record.size(); i++) {
                        values.put("col_" + i, cleanValue(record.get(i)));
                    }
                }

                // Extract or construct sourceRecordId
                String sourceRecordId = null;
                for (String candidate : List.of("id", "txn_id", "txnid", "txn id", "transaction_id", "transactionid", "transaction id", "reference no", "reference_no", "ref_no", "chq./ref.no.")) {
                    for (Map.Entry<String, String> e : values.entrySet()) {
                        if (e.getKey().trim().equalsIgnoreCase(candidate) && e.getValue() != null && !e.getValue().isBlank()) {
                            sourceRecordId = e.getValue();
                            break;
                        }
                    }
                    if (sourceRecordId != null) break;
                }
                if (sourceRecordId == null) {
                    sourceRecordId = "ROW_" + rowIndex;
                }

                SourceRecord sourceRecord = new SourceRecord(rowIndex, sourceRecordId, values, record.toString());

                try {
                    consumer.accept(sourceRecord);
                    processedCount++;
                } catch (Exception e) {
                    log.warn("Error processing CSV row [{}]: {}", rowIndex, e.getMessage());
                    throw new RuntimeException("Error processing CSV row " + rowIndex, e);
                }
            }
        }

        return processedCount;
    }

    private String cleanValue(String value) {
        if (value == null) return null;
        return value.trim();
    }
}
