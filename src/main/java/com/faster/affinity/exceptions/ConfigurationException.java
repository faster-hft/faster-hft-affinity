package com.faster.affinity.exceptions;

/**
 * Exception for configuration errors
 */
public class ConfigurationException extends RuntimeException {
    private final String operation;

    public ConfigurationException(String operation, String message) {
        super(message);
        this.operation = operation;
    }

    public String getOperation() { return operation; }
}