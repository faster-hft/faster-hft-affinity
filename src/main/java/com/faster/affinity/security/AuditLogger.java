package com.faster.affinity.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Comprehensive audit logging for security-sensitive affinity operations.
 * Provides tamper-resistant logging with structured format for SIEM integration.
 */
public final class AuditLogger {
    private static final Logger logger = LoggerFactory.getLogger("SECURITY.AUDIT");
    private static final Logger alertLogger = LoggerFactory.getLogger("SECURITY.ALERT");

    // Audit event sequence counter for tamper detection
    private static final AtomicLong sequenceCounter = new AtomicLong(0);

    // ISO 8601 timestamp format for consistent audit trails
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);

    /**
     * Audit event types for security monitoring.
     */
    public enum AuditEventType {
        AFFINITY_SET("AFFINITY_SET", "Thread affinity modification"),
        AFFINITY_GET("AFFINITY_GET", "Thread affinity query"),
        PRIVILEGE_CHECK("PRIVILEGE_CHECK", "Privilege validation"),
        RATE_LIMIT_EXCEEDED("RATE_LIMIT_EXCEEDED", "Rate limiting violation"),
        CONFIGURATION_CHANGE("CONFIG_CHANGE", "Configuration modification"),
        NUMA_OPERATION("NUMA_OPERATION", "NUMA topology operation"),
        PERFORMANCE_ACCESS("PERF_ACCESS", "Performance counter access"),
        SYSTEM_INITIALIZATION("SYS_INIT", "System initialization"),
        SECURITY_VIOLATION("SECURITY_VIOLATION", "Security policy violation"),
        AUTHENTICATION_EVENT("AUTH_EVENT", "Authentication/authorization event");

        private final String code;
        private final String description;

        AuditEventType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() { return code; }
        public String getDescription() { return description; }
    }

    /**
     * Audit event severity levels.
     */
    public enum AuditSeverity {
        INFO("INFO"),
        WARNING("WARNING"),
        CRITICAL("CRITICAL"),
        EMERGENCY("EMERGENCY");

        private final String level;

        AuditSeverity(String level) {
            this.level = level;
        }

        public String getLevel() { return level; }
    }

    /**
     * Log successful affinity operation.
     */
    public static void logAffinityOperation(AuditEventType eventType, String operation,
                                          long threadId, String cpuMask, String result) {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(eventType)
            .severity(AuditSeverity.INFO)
            .operation(operation)
            .threadId(threadId)
            .resourceId("cpu_mask:" + sanitizeForLogging(cpuMask))
            .result("SUCCESS")
            .details(result)
            .build();

        logAuditEvent(event);
    }

    /**
     * Log security violation or suspicious activity.
     */
    public static void logSecurityViolation(AuditEventType eventType, String operation,
                                          String violation, String details) {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(eventType)
            .severity(AuditSeverity.CRITICAL)
            .operation(operation)
            .result("VIOLATION")
            .details("VIOLATION: " + sanitizeForLogging(violation) + " | " + sanitizeForLogging(details))
            .build();

        logAuditEvent(event);

        // Also send to security alert channel
        alertLogger.error("SECURITY_ALERT seq={} event={} operation={} violation={} user={} thread={} timestamp={}",
                         event.sequenceNumber, eventType.getCode(), operation, violation,
                         getCurrentUser(), Thread.currentThread().getId(), event.timestamp);
    }

    /**
     * Log rate limiting violation.
     */
    public static void logRateLimitViolation(String operation, long threadId, String limitInfo) {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEventType.RATE_LIMIT_EXCEEDED)
            .severity(AuditSeverity.WARNING)
            .operation(operation)
            .threadId(threadId)
            .result("BLOCKED")
            .details("Rate limit exceeded: " + sanitizeForLogging(limitInfo))
            .build();

        logAuditEvent(event);
    }

    /**
     * Log privilege check results.
     */
    public static void logPrivilegeCheck(String operation, String privilegeType,
                                       boolean granted, String details) {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEventType.PRIVILEGE_CHECK)
            .severity(granted ? AuditSeverity.INFO : AuditSeverity.WARNING)
            .operation(operation)
            .resourceId("privilege:" + privilegeType)
            .result(granted ? "GRANTED" : "DENIED")
            .details(sanitizeForLogging(details))
            .build();

        logAuditEvent(event);
    }

    /**
     * Log configuration changes.
     */
    public static void logConfigurationChange(String configKey, String oldValue,
                                            String newValue, String operation) {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEventType.CONFIGURATION_CHANGE)
            .severity(AuditSeverity.INFO)
            .operation(operation)
            .resourceId("config:" + configKey)
            .result("CHANGED")
            .details("Changed from [" + sanitizeForLogging(oldValue) +
                    "] to [" + sanitizeForLogging(newValue) + "]")
            .build();

        logAuditEvent(event);
    }

    /**
     * Log system initialization events.
     */
    public static void logSystemInitialization(String component, String version,
                                             String config, boolean successful) {
        AuditEvent event = new AuditEvent.Builder()
            .eventType(AuditEventType.SYSTEM_INITIALIZATION)
            .severity(successful ? AuditSeverity.INFO : AuditSeverity.CRITICAL)
            .operation("SYSTEM_INIT")
            .resourceId("component:" + component)
            .result(successful ? "SUCCESS" : "FAILURE")
            .details("Version: " + sanitizeForLogging(version) +
                    " | Config: " + sanitizeForLogging(config))
            .build();

        logAuditEvent(event);
    }

    /**
     * Core audit event logging with structured format.
     */
    private static void logAuditEvent(AuditEvent event) {
        // Set MDC context for structured logging
        MDC.put("auditSequence", String.valueOf(event.sequenceNumber));
        MDC.put("auditEventType", event.eventType.getCode());
        MDC.put("auditSeverity", event.severity.getLevel());
        MDC.put("auditUser", event.user);
        MDC.put("auditThreadId", String.valueOf(event.threadId));
        MDC.put("auditTimestamp", event.timestamp);

        try {
            // Log with consistent audit format for SIEM parsing
            String auditMessage = String.format(
                "AUDIT seq=%d event=%s severity=%s operation=%s user=%s thread=%d pid=%d " +
                "resource=%s result=%s timestamp=%s details=%s",
                event.sequenceNumber,
                event.eventType.getCode(),
                event.severity.getLevel(),
                event.operation,
                event.user,
                event.threadId,
                event.processId,
                event.resourceId != null ? event.resourceId : "N/A",
                event.result,
                event.timestamp,
                event.details != null ? event.details : "N/A"
            );

            // Route to appropriate log level based on severity
            switch (event.severity) {
                case INFO:
                    logger.info(auditMessage);
                    break;
                case WARNING:
                    logger.warn(auditMessage);
                    break;
                case CRITICAL:
                case EMERGENCY:
                    logger.error(auditMessage);
                    break;
            }

        } finally {
            // Clean up MDC to prevent context leakage
            MDC.clear();
        }
    }

    /**
     * Sanitize strings for safe logging (prevent log injection and parsing attacks).
     * Implements comprehensive protection against CRLF injection, structured log attacks,
     * and format string vulnerabilities.
     */
    private static String sanitizeForLogging(String input) {
        if (input == null) {
            return "null";
        }

        // Length validation first to prevent DoS attacks
        String safeInput = input.length() > 1000 ? input.substring(0, 1000) + "...TRUNCATED" : input;

        // Multi-layer sanitization for comprehensive protection
        StringBuilder sanitized = new StringBuilder(safeInput.length());

        for (int i = 0; i < safeInput.length(); i++) {
            char c = safeInput.charAt(i);

            // Remove CRLF injection vectors (primary attack vector)
            if (c == '\r' || c == '\n') {
                sanitized.append('_');
            }
            // Remove tab characters that could break structured parsing
            else if (c == '\t') {
                sanitized.append(' ');
            }
            // Remove control characters that could break log parsers
            else if (Character.isISOControl(c) && c != ' ') {
                sanitized.append('_');
            }
            // Escape log format delimiters used in structured logging
            else if (c == '=' || c == '|' || c == ';') {
                sanitized.append('\\').append(c);
            }
            // Remove angle brackets to prevent XML/HTML injection
            else if (c == '<' || c == '>') {
                sanitized.append('_');
            }
            // Escape quotes that could break JSON/CSV parsing
            else if (c == '"') {
                sanitized.append("\\\"");
            }
            else if (c == '\'') {
                sanitized.append("\\'");
            }
            // Convert backslashes to forward slashes (safer path separators)
            else if (c == '\\') {
                sanitized.append('/');
            }
            // Remove null bytes that could terminate C-style strings
            else if (c == '\0') {
                sanitized.append('_');
            }
            // Remove format string specifiers that could cause vulnerabilities
            else if (c == '%' && i + 1 < safeInput.length()) {
                char next = safeInput.charAt(i + 1);
                if (Character.isLetter(next) || next == '%') {
                    sanitized.append("PCT"); // Replace format specifiers
                    i++; // Skip the next character
                } else {
                    sanitized.append(c);
                }
            }
            // Allow safe printable ASCII characters
            else if (c >= 32 && c <= 126) {
                sanitized.append(c);
            }
            // Replace other characters with underscore
            else {
                sanitized.append('_');
            }
        }

        String result = sanitized.toString();

        // Additional validation to prevent common injection patterns
        result = result.replaceAll("(?i)script[^a-z]", "SCRIPT_");
        result = result.replaceAll("(?i)javascript:", "JS_");
        result = result.replaceAll("(?i)data:", "DATA_");
        result = result.replaceAll("(?i)vbscript:", "VBS_");

        // Prevent SQL injection patterns in log data
        result = result.replaceAll("(?i)(union|select|insert|delete|update|drop|create|alter)\\s", "$1_");

        // Prevent LDAP injection patterns
        result = result.replaceAll("[()\\*\\\\]", "_");

        return result.trim();
    }

    /**
     * Get current user context safely.
     */
    private static String getCurrentUser() {
        try {
            return System.getProperty("user.name", "unknown");
        } catch (SecurityException e) {
            return "restricted";
        }
    }

    /**
     * Get current process ID safely.
     */
    private static long getCurrentProcessId() {
        try {
            return ProcessHandle.current().pid();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Immutable audit event record.
     */
    private static class AuditEvent {
        final long sequenceNumber;
        final AuditEventType eventType;
        final AuditSeverity severity;
        final String operation;
        final String user;
        final long threadId;
        final long processId;
        final String resourceId;
        final String result;
        final String details;
        final String timestamp;

        private AuditEvent(Builder builder) {
            this.sequenceNumber = sequenceCounter.incrementAndGet();
            this.eventType = builder.eventType;
            this.severity = builder.severity;
            this.operation = builder.operation;
            this.user = getCurrentUser();
            this.threadId = builder.threadId != 0 ? builder.threadId : Thread.currentThread().getId();
            this.processId = getCurrentProcessId();
            this.resourceId = builder.resourceId;
            this.result = builder.result;
            this.details = builder.details;
            this.timestamp = TIMESTAMP_FORMAT.format(Instant.now());
        }

        static class Builder {
            private AuditEventType eventType;
            private AuditSeverity severity = AuditSeverity.INFO;
            private String operation;
            private long threadId = 0;
            private String resourceId;
            private String result;
            private String details;

            Builder eventType(AuditEventType eventType) {
                this.eventType = eventType;
                return this;
            }

            Builder severity(AuditSeverity severity) {
                this.severity = severity;
                return this;
            }

            Builder operation(String operation) {
                this.operation = operation;
                return this;
            }

            Builder threadId(long threadId) {
                this.threadId = threadId;
                return this;
            }

            Builder resourceId(String resourceId) {
                this.resourceId = resourceId;
                return this;
            }

            Builder result(String result) {
                this.result = result;
                return this;
            }

            Builder details(String details) {
                this.details = details;
                return this;
            }

            AuditEvent build() {
                if (eventType == null || operation == null) {
                    throw new IllegalArgumentException("Event type and operation are required");
                }
                return new AuditEvent(this);
            }
        }
    }

    /**
     * Get audit statistics for monitoring.
     */
    public static AuditStats getAuditStats() {
        return new AuditStats(sequenceCounter.get());
    }

    /**
     * Audit statistics for monitoring.
     */
    public static class AuditStats {
        public final long totalAuditEvents;

        AuditStats(long totalAuditEvents) {
            this.totalAuditEvents = totalAuditEvents;
        }

        @Override
        public String toString() {
            return String.format("AuditStats{totalEvents=%d}", totalAuditEvents);
        }
    }
}