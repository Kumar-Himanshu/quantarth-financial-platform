package com.fip.shared.events;

public enum EntityType {
    PAYMENT,
    TRANSFER,
    SALARY,
    REFUND,
    LOAN,
    LOAN_REPAYMENT,
    INVOICE,
    SUBSCRIPTION,
    TAX,
    AUTH,
    PLUGIN,
    SYSTEM,
    OTHER;

    public static EntityType fromName(String name) {
        if (name == null || name.isBlank()) {
            return OTHER;
        }
        try {
            return EntityType.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
