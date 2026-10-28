package com.fip.ingestion.domain.model;

public enum JobStatus {
    RECEIVED,
    VALIDATING,
    PROCESSING,
    COMPLETED,
    PARTIALLY_COMPLETED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIALLY_COMPLETED || this == FAILED;
    }
}
