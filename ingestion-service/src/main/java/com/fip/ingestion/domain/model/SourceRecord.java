package com.fip.ingestion.domain.model;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

public record SourceRecord(
        int recordIndex,
        String sourceRecordId,
        Map<String, String> values,
        String rawSnippet
) implements Serializable {

    public SourceRecord {
        if (values == null) {
            values = Collections.emptyMap();
        }
    }

    public String getValue(String key) {
        if (key == null) return null;
        return values.get(key);
    }
}
