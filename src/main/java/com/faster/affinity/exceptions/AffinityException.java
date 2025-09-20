package com.faster.affinity.exceptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Base exception for all affinity library errors
 */
public abstract class AffinityException extends Exception {
    private final int errorCode;
    private final String operation;
    private final Map<String, Object> context;
    private final long timestamp;

    protected AffinityException(int errorCode, String operation, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.operation = operation;
        this.context = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    protected AffinityException(int errorCode, String operation, String message) {
        this(errorCode, operation, message, null);
    }

    public int getErrorCode() { return errorCode; }
    public String getOperation() { return operation; }
    public Map<String, Object> getContext() { return new HashMap<>(context); }
    public long getTimestamp() { return timestamp; }

    public AffinityException addContext(String key, Object value) {
        context.put(key, value);
        return this;
    }

    @Override
    public String toString() {
        return String.format("%s[code=%d, op=%s, msg=%s, context=%s]",
                getClass().getSimpleName(), errorCode, operation, getMessage(), context);
    }
}