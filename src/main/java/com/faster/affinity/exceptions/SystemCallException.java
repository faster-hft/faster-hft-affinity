package com.faster.affinity.exceptions;

/**
 * Exception for system call failures
 */
public class SystemCallException extends AffinityException {
    public SystemCallException(String operation, String syscall, int errno) {
        super(ErrorCodes.ERROR_SYSTEM_CALL_FAILED, operation,
                String.format("System call '%s' failed with errno %d", syscall, errno));
        addContext("syscall", syscall).addContext("errno", errno);
    }

    public SystemCallException(String operation, String syscall, Throwable cause) {
        super(ErrorCodes.ERROR_SYSTEM_CALL_FAILED, operation,
                String.format("System call '%s' failed", syscall), cause);
        addContext("syscall", syscall);
    }
}