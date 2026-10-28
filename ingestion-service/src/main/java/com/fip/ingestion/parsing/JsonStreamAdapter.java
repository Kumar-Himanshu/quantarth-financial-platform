package com.fip.ingestion.parsing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.ingestion.domain.model.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Component
public class JsonStreamAdapter {

    private static final Logger log = LoggerFactory.getLogger(JsonStreamAdapter.class);
    private final ObjectMapper objectMapper;

    public JsonStreamAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public interface RecordConsumer {
        void accept(SourceRecord record) throws Exception;
    }

    public int parseStream(InputStream inputStream, RecordConsumer consumer) throws IOException {
        return parseStream(inputStream, 0, consumer);
    }

    public int parseStream(InputStream inputStream, int skipToRowIndex, RecordConsumer consumer) throws IOException {
        int index = 0;
        int processedCount = 0;

        try (JsonParser parser = objectMapper.getFactory().createParser(inputStream)) {
            // Find start array: [ or { "records": [ ... ] }
            JsonToken token = parser.nextToken();
            if (token == JsonToken.START_OBJECT) {
                while (parser.nextToken() != null) {
                    String fieldName = parser.getCurrentName();
                    if ("records".equalsIgnoreCase(fieldName)) {
                        token = parser.nextToken(); // Move to START_ARRAY
                        break;
                    }
                }
            }

            if (token != JsonToken.START_ARRAY) {
                throw new IllegalArgumentException("JSON stream must contain a root array or a 'records' array field");
            }

            while (parser.nextToken() == JsonToken.START_OBJECT) {
                index++;
                if (index <= skipToRowIndex) {
                    parser.skipChildren();
                    continue;
                }
                JsonNode node = objectMapper.readTree(parser);
                Map<String, String> values = new HashMap<>();

                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String key = entry.getKey();
                    JsonNode valNode = entry.getValue();
                    if (valNode.isValueNode()) {
                        values.put(key, valNode.asText());
                    } else {
                        values.put(key, valNode.toString());
                    }
                }

                String sourceRecordId = null;
                for (String candidate : List.of("id", "sourceRecordId", "transactionId", "transaction_id", "txn_id", "txnid", "referenceNo")) {
                    for (Map.Entry<String, String> e : values.entrySet()) {
                        if (e.getKey().trim().equalsIgnoreCase(candidate) && e.getValue() != null && !e.getValue().isBlank()) {
                            sourceRecordId = e.getValue();
                            break;
                        }
                    }
                    if (sourceRecordId != null) break;
                }
                if (sourceRecordId == null) {
                    sourceRecordId = "REC_" + index;
                }

                SourceRecord sourceRecord = new SourceRecord(index, sourceRecordId, values, node.toString());

                try {
                    consumer.accept(sourceRecord);
                    processedCount++;
                } catch (Exception e) {
                    log.warn("Error processing JSON record [{}]: {}", index, e.getMessage());
                    throw new RuntimeException("Error processing JSON record " + index, e);
                }
            }
        }

        return processedCount;
    }
}
