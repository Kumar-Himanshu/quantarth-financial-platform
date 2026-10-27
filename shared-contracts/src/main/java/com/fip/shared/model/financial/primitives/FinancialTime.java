package com.fip.shared.model.financial.primitives;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Immutable temporal representation for financial transactions.
 * Decouples physical event occurrence from accounting value date,
 * system processing time, and immutable ledger recording time.
 * All instants are UTC-normalized; business dates remain zone-invariant.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinancialTime(
        Instant occurredAt,
        LocalDate valueDate,
        Instant processedAt,
        Instant recordedAt
) implements Serializable {

    private static final long serialVersionUID = 1L;
    public static final ZoneId ZONE_IST = ZoneId.of("Asia/Kolkata");

    public FinancialTime {
        if (occurredAt == null) {
            throw new FinancialValidationException("occurredAt cannot be null");
        }
        if (valueDate == null) {
            // Derive default business value date from occurredAt in UTC if omitted
            valueDate = LocalDate.ofInstant(occurredAt, ZoneOffset.UTC);
        }
        if (processedAt == null) {
            processedAt = Instant.now();
        }
        if (recordedAt == null) {
            recordedAt = Instant.now();
        }
    }

    public static FinancialTime now() {
        Instant now = Instant.now();
        return new FinancialTime(now, LocalDate.ofInstant(now, ZoneOffset.UTC), now, now);
    }

    public static FinancialTime of(Instant occurredAt, LocalDate valueDate) {
        Instant now = Instant.now();
        return new FinancialTime(occurredAt, valueDate, now, now);
    }

    public static FinancialTime at(Instant occurredAt) {
        return new FinancialTime(occurredAt, LocalDate.ofInstant(occurredAt, ZoneOffset.UTC), Instant.now(), Instant.now());
    }

    public ZonedDateTime toUtc() {
        return occurredAt.atZone(ZoneOffset.UTC);
    }

    public ZonedDateTime toIst() {
        return occurredAt.atZone(ZONE_IST);
    }

    public ZonedDateTime toZonedDateTime(ZoneId zoneId) {
        if (zoneId == null) {
            throw new FinancialValidationException("ZoneId cannot be null");
        }
        return occurredAt.atZone(zoneId);
    }
}
