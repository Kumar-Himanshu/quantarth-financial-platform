package com.fip.ingestion.parsing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.ingestion.domain.model.SourceRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JSON Stream Adapter Unit Tests")
class JsonStreamAdapterTest {

    private final JsonStreamAdapter adapter = new JsonStreamAdapter(new ObjectMapper());

    @Test
    @DisplayName("Streams items from bare JSON array: [ {...}, {...} ]")
    void testBareJsonArray() throws Exception {
        String json = """
                [
                  {"id": "REC-1", "amount": "100.00", "currency": "INR"},
                  {"id": "REC-2", "amount": "200.00", "currency": "INR"}
                ]
                """;

        InputStream stream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        List<SourceRecord> records = new ArrayList<>();

        adapter.parseStream(stream, 0, records::add);

        assertEquals(2, records.size());
        assertEquals("REC-1", records.get(0).sourceRecordId());
        assertEquals("100.00", records.get(0).getValue("amount"));
        assertEquals("REC-2", records.get(1).sourceRecordId());
        assertEquals("200.00", records.get(1).getValue("amount"));
    }

    @Test
    @DisplayName("Streams items from envelope object: { 'records': [ {...} ] }")
    void testEnvelopedJsonArray() throws Exception {
        String json = """
                {
                  "sourceSystem": "FINTECH_APP",
                  "batchId": "BATCH-999",
                  "records": [
                    {"transactionId": "TXN-881", "amount": "999.99"},
                    {"transactionId": "TXN-882", "amount": "123.45"}
                  ]
                }
                """;

        InputStream stream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        List<SourceRecord> records = new ArrayList<>();

        adapter.parseStream(stream, 0, records::add);

        assertEquals(2, records.size());
        assertEquals("TXN-881", records.get(0).sourceRecordId());
        assertEquals("TXN-882", records.get(1).sourceRecordId());
    }

    @Test
    @DisplayName("Resumable processing: skipToRowIndex skips records already processed")
    void testSkipToRowIndex() throws Exception {
        String json = """
                [
                  {"id": "1"},
                  {"id": "2"},
                  {"id": "3"},
                  {"id": "4"}
                ]
                """;

        InputStream stream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        List<Integer> indices = new ArrayList<>();

        adapter.parseStream(stream, 2, r -> indices.add(r.recordIndex()));

        assertEquals(List.of(3, 4), indices);
    }
}
