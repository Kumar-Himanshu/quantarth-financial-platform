package com.fip.ingestion.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldMapping(
        String sourceColumn,
        String strategy,          // DIRECT, SPLIT_DR_CR, SIGNED_AMOUNT, STATIC_DEFAULT, DATE_PARSE, REGEX_EXTRACT
        String debitColumn,
        String creditColumn,
        String amountColumn,
        String typeColumn,
        String creditIndicator,   // e.g. "CR"
        String debitIndicator,    // e.g. "DR"
        String staticValue,
        String regex,
        Integer scale,
        String dateFormat,
        String transform
) implements Serializable {
    public static FieldMapping column(String sourceColumn) {
        return new FieldMapping(sourceColumn, "DIRECT", null, null, null, null, null, null, null, null, null, null, null);
    }

    public static FieldMapping constant(String staticValue) {
        return new FieldMapping(null, "STATIC_DEFAULT", null, null, null, null, null, null, staticValue, null, null, null, null);
    }

    public static FieldMapping splitDrCr(String debitColumn, String creditColumn, Integer scale) {
        return new FieldMapping(null, "SPLIT_DR_CR", debitColumn, creditColumn, null, null, null, null, null, null, scale, null, null);
    }
}
