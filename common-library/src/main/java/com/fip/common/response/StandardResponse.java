package com.fip.common.response;

import java.time.LocalDateTime;

public class StandardResponse<T> {
    private boolean success;
    private int status;
    private String message;
    private T data;
    private LocalDateTime timestamp;

    public StandardResponse() {
        this.status = 200;
        this.timestamp = LocalDateTime.now();
    }

    public StandardResponse(boolean success, int status, String message, T data, LocalDateTime timestamp) {
        this.success = success;
        this.status = status != 0 ? status : (success ? 200 : 400);
        this.message = message;
        this.data = data;
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public T getData() { return data; }
    public void setData(T data) { this.data = data; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private boolean success;
        private int status;
        private String message;
        private T data;
        private LocalDateTime timestamp = LocalDateTime.now();

        public Builder<T> success(boolean success) {
            this.success = success;
            if (this.status == 0) {
                this.status = success ? 200 : 400;
            }
            return this;
        }

        public Builder<T> status(int status) {
            this.status = status;
            return this;
        }

        public Builder<T> message(String message) {
            this.message = message;
            return this;
        }

        public Builder<T> data(T data) {
            this.data = data;
            return this;
        }

        public Builder<T> timestamp(LocalDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public StandardResponse<T> build() {
            return new StandardResponse<>(success, status, message, data, timestamp);
        }
    }

    public static <T> StandardResponse<T> success(T data, String message) {
        return StandardResponse.<T>builder()
                .success(true)
                .status(200)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> StandardResponse<T> success(int status, T data, String message) {
        return StandardResponse.<T>builder()
                .success(true)
                .status(status)
                .message(message)
                .data(data)
                .build();
    }

    public static <T> StandardResponse<T> success(T data) {
        return success(data, "Operation completed successfully");
    }

    public static <T> StandardResponse<T> failure(String message) {
        return StandardResponse.<T>builder()
                .success(false)
                .status(400)
                .message(message)
                .build();
    }
}
