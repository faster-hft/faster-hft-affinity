package com.faster.affinity.exceptions;

/**
 * Exception for NUMA-related errors
 */
public class NumaException extends AffinityException {
    public NumaException(String operation, String message) {
        super(ErrorCodes.ERROR_NUMA_NOT_AVAILABLE, operation, message);
    }

    public NumaException(String operation, int nodeId) {
        super(ErrorCodes.ERROR_NUMA_NOT_AVAILABLE, operation,
                String.format("NUMA node %d not available or NUMA not supported", nodeId));
        addContext("numa_node", nodeId);
    }
}