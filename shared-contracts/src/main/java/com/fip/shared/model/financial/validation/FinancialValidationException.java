package com.fip.shared.model.financial.validation;

import java.util.Collections;
import java.util.List;

public class FinancialValidationException extends IllegalArgumentException {

    private final List<String> errors;

    public FinancialValidationException(String message) {
        super(message);
        this.errors = Collections.singletonList(message);
    }

    public FinancialValidationException(String message, Throwable cause) {
        super(message, cause);
        this.errors = Collections.singletonList(message);
    }

    public FinancialValidationException(String message, List<String> errors) {
        super(message + (errors != null && !errors.isEmpty() ? ": " + String.join(", ", errors) : ""));
        this.errors = errors != null ? List.copyOf(errors) : Collections.emptyList();
    }

    public FinancialValidationException(String message, List<String> errors, Throwable cause) {
        super(message + (errors != null && !errors.isEmpty() ? ": " + String.join(", ", errors) : ""), cause);
        this.errors = errors != null ? List.copyOf(errors) : Collections.emptyList();
    }

    public List<String> getErrors() {
        return errors;
    }
}
