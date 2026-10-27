package com.fip.shared.model.financial.domain;

import java.util.Set;

public enum RefundStatus {
    INITIATED,
    PENDING,
    COMPLETED,
    FAILED,
    REJECTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == REJECTED;
    }

    public boolean canTransitionTo(RefundStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return switch (this) {
            case INITIATED -> Set.of(PENDING, COMPLETED, FAILED, REJECTED).contains(target);
            case PENDING -> Set.of(COMPLETED, FAILED, REJECTED).contains(target);
            case COMPLETED, FAILED, REJECTED -> false;
        };
    }
}
