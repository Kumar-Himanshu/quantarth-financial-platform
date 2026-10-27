package com.fip.shared.model.financial.domain;

import java.util.Set;

public enum PaymentStatus {
    INITIATED,
    PENDING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean canTransitionTo(PaymentStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true; // Idempotent same-state transition
        }
        return switch (this) {
            case INITIATED -> Set.of(PENDING, COMPLETED, FAILED, CANCELLED).contains(target);
            case PENDING -> Set.of(COMPLETED, FAILED, CANCELLED).contains(target);
            case COMPLETED, FAILED, CANCELLED -> false; // Terminal states
        };
    }
}
