package com.fip.ingestion.parsing;

import com.fip.ingestion.domain.model.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CSV Stream Adapter & RFC 4180 Parsing Unit Tests")
class CsvStreamAdapterTest {

    private final CsvStreamAdapter adapter = new CsvStreamAdapter();

    @Test
    @DisplayName("Successfully parses RFC 4180 CSV with quotes, commas, and trims headers")
    void testStandardCsvParsing() throws Exception {
        String csv = """
                "Txn Id","Date","Description","Amount"
                "TXN-001","2026-10-20","Payment for invoice #100, approved","1500.50"
                "TXN-002","2026-10-21","Vendor payout","250.00"
                """;

        InputStream stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        List<SourceRecord> records = new ArrayList<>();

        adapter.parseStream(stream, ",", true, 0, records::add);

        assertEquals(2, records.size());
        assertEquals("TXN-001", records.get(0).sourceRecordId());
        assertEquals("1500.50", records.get(0).getValue("Amount"));
        assertEquals("Payment for invoice #100, approved", records.get(0).getValue("Description"));

        assertEquals("TXN-002", records.get(1).sourceRecordId());
        assertEquals("250.00", records.get(1).getValue("Amount"));
    }

    @Test
    @DisplayName("Financial Data Integrity: Preserves raw values, descriptions, and formulas without mutation during ingestion")
    void testPreservesOriginalValuesAndFormulasWithoutMutationDuringIngestion() throws Exception {
        String csv = """
                id,narration,amount
                1,=SUM(A1:A10),100.00
                2,+cmd|'/c calc'!A1,200.00
                3,-2+3,300.00
                4,@DDE("cmd";"calc"),400.00
                5,"\tTAB_VALUE",500.00
                """;

        InputStream stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        List<SourceRecord> records = new ArrayList<>();

        adapter.parseStream(stream, ",", true, 0, records::add);

        assertEquals(5, records.size());
        assertEquals("=SUM(A1:A10)", records.get(0).getValue("narration"), "Must not prefix with quote on ingestion");
        assertEquals("+cmd|'/c calc'!A1", records.get(1).getValue("narration"), "Must not mutate positive prefix on ingestion");
        assertEquals("-2+3", records.get(2).getValue("narration"), "Must not mutate negative prefix on ingestion");
        assertEquals("@DDE(\"cmd\";\"calc\")", records.get(3).getValue("narration"), "Must not mutate @ prefix on ingestion");
        assertEquals("TAB_VALUE", records.get(4).getValue("narration"), "Must trim leading whitespace without injecting quotes");

        // Verify that sanitization is applied at the EXPORT boundary
        assertEquals("'=SUM(A1:A10)", com.fip.ingestion.util.CsvExportSanitizer.sanitizeForExport(records.get(0).getValue("narration")));
        assertEquals("'+cmd|'/c calc'!A1", com.fip.ingestion.util.CsvExportSanitizer.sanitizeForExport(records.get(1).getValue("narration")));
        assertEquals("'-2+3", com.fip.ingestion.util.CsvExportSanitizer.sanitizeForExport(records.get(2).getValue("narration")));
        assertEquals("'@DDE(\"cmd\";\"calc\")", com.fip.ingestion.util.CsvExportSanitizer.sanitizeForExport(records.get(3).getValue("narration")));
        assertEquals("Clean Text", com.fip.ingestion.util.CsvExportSanitizer.sanitizeForExport("Clean Text"));
    }

    @Test
    @DisplayName("Resumable processing: skipToRowIndex skips previously committed rows")
    void testSkipToRowIndex() throws Exception {
        String csv = """
                id,val
                1,one
                2,two
                3,three
                4,four
                5,five
                """;

        InputStream stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        List<Integer> visitedRows = new ArrayList<>();

        // Skip rows 1 and 2, start processing from row 3
        adapter.parseStream(stream, ",", true, 2, r -> visitedRows.add(r.recordIndex()));

        assertEquals(List.of(3, 4, 5), visitedRows);
    }

    @Test
    @DisplayName("Empty or whitespace lines in CSV are gracefully skipped")
    void testBlankLinesSkipped() throws Exception {
        String csv = """
                id,val

                1,one

                2,two

                """;

        InputStream stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        List<SourceRecord> records = new ArrayList<>();

        adapter.parseStream(stream, ",", true, 0, records::add);

        assertEquals(2, records.size());
        assertEquals("1", records.get(0).sourceRecordId());
        assertEquals("2", records.get(1).sourceRecordId());
    }
}
