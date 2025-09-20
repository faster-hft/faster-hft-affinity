package com.faster.affinity.exceptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Error context builder for comprehensive error reporting
 */
public class ErrorContext {
    private final Map<String, Object> context = new HashMap<>();

    public ErrorContext add(String key, Object value) {
        context.put(key, value);
        return this;
    }

    public ErrorContext addSystemInfo() {
        context.put("os_name", System.getProperty("os.name"));
        context.put("os_version", System.getProperty("os.version"));
        context.put("java_version", System.getProperty("java.version"));
        context.put("timestamp", System.currentTimeMillis());
        return this;
    }

    public ErrorContext addThreadInfo() {
        Thread current = Thread.currentThread();
        context.put("thread_name", current.getName());
        context.put("thread_id", current.getId());
        return this;
    }

    public Map<String, Object> build() {
        return new HashMap<>(context);
    }
}