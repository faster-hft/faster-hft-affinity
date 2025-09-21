package com.faster.affinity.transaction;

import com.faster.affinity.security.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Transaction manager for ensuring ACID properties in multi-step affinity operations.
 * Provides rollback capabilities and transactional integrity for HFT environments.
 */
public final class TransactionManager {
    private static final Logger logger = LoggerFactory.getLogger(TransactionManager.class);

    // Transaction ID generator
    private static final AtomicLong transactionIdGenerator = new AtomicLong(0);

    // Thread-local transaction context
    private static final ThreadLocal<TransactionContext> currentTransaction = new ThreadLocal<>();

    /**
     * Execute operations within a transaction with automatic rollback on failure.
     */
    public static <T> T executeTransaction(String operationName, TransactionalOperation<T> operation) {
        long transactionId = transactionIdGenerator.incrementAndGet();
        TransactionContext context = new TransactionContext(transactionId, operationName);

        // Check for nested transactions
        if (currentTransaction.get() != null) {
            throw new IllegalStateException("Nested transactions are not supported. " +
                "Current transaction: " + currentTransaction.get().getOperationName());
        }

        currentTransaction.set(context);
        context.begin();

        try {
            AuditLogger.logSystemInitialization("Transaction", "1.0.0",
                "Started transaction: " + operationName + " (ID: " + transactionId + ")", true);

            T result = operation.execute(context);

            context.commit();
            AuditLogger.logSystemInitialization("Transaction", "1.0.0",
                "Committed transaction: " + operationName + " (ID: " + transactionId + ")", true);

            return result;

        } catch (Exception e) {
            context.rollback();
            AuditLogger.logSecurityViolation(AuditLogger.AuditEventType.SYSTEM_INITIALIZATION,
                "TransactionRollback", "Transaction failed: " + operationName,
                "Transaction ID: " + transactionId + ", Error: " + e.getMessage());

            throw new TransactionException("Transaction failed: " + operationName +
                " (ID: " + transactionId + ")", e);

        } finally {
            currentTransaction.remove();
        }
    }

    /**
     * Get the current transaction context (null if no active transaction).
     */
    public static TransactionContext getCurrentTransaction() {
        return currentTransaction.get();
    }

    /**
     * Check if there is an active transaction.
     */
    public static boolean isTransactionActive() {
        return currentTransaction.get() != null;
    }

    /**
     * Transaction context that tracks operations and provides rollback capability.
     */
    public static class TransactionContext {
        private final long transactionId;
        private final String operationName;
        private final List<CompensatingAction> compensatingActions;
        private final List<RollbackFailure> rollbackFailures;
        private final long startTime;
        private volatile TransactionState state;

        private TransactionContext(long transactionId, String operationName) {
            this.transactionId = transactionId;
            this.operationName = operationName;
            this.compensatingActions = new ArrayList<>();
            this.rollbackFailures = new ArrayList<>();
            this.startTime = System.currentTimeMillis();
            this.state = TransactionState.CREATED;
        }

        /**
         * Add a compensating action for rollback.
         */
        public void addCompensatingAction(String actionName, Runnable rollbackAction) {
            if (state != TransactionState.ACTIVE) {
                throw new IllegalStateException("Cannot add compensating action to inactive transaction");
            }

            compensatingActions.add(new CompensatingAction(actionName, rollbackAction));
            logger.trace("Added compensating action: {} (transaction: {})", actionName, transactionId);
        }

        /**
         * Execute an operation with automatic rollback registration.
         */
        public <T> T executeWithRollback(String actionName, Supplier<T> action, Runnable rollbackAction) {
            try {
                T result = action.get();
                addCompensatingAction(actionName, rollbackAction);
                return result;
            } catch (Exception e) {
                logger.warn("Action failed in transaction {}: {} - {}", transactionId, actionName, e.getMessage());
                throw e;
            }
        }

        /**
         * Execute an operation without return value.
         */
        public void executeWithRollback(String actionName, Runnable action, Runnable rollbackAction) {
            executeWithRollback(actionName, () -> {
                action.run();
                return null;
            }, rollbackAction);
        }

        void begin() {
            if (state != TransactionState.CREATED) {
                throw new IllegalStateException("Transaction already started: " + transactionId);
            }
            state = TransactionState.ACTIVE;
            logger.debug("Transaction started: {} (ID: {})", operationName, transactionId);
        }

        void commit() {
            if (state != TransactionState.ACTIVE) {
                throw new IllegalStateException("Cannot commit inactive transaction: " + transactionId);
            }

            state = TransactionState.COMMITTED;
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("Transaction committed: {} (ID: {}, duration: {}ms, actions: {})",
                        operationName, transactionId, duration, compensatingActions.size());
        }

        void rollback() {
            if (state == TransactionState.ROLLED_BACK || state == TransactionState.ROLLBACK_FAILED) {
                logger.warn("Transaction already rolled back: {} (state: {})", transactionId, state);
                return;
            }

            state = TransactionState.ROLLING_BACK;
            logger.warn("Rolling back transaction: {} (ID: {}, actions: {})",
                       operationName, transactionId, compensatingActions.size());

            int rolledBack = 0;
            List<RollbackFailure> criticalFailures = new ArrayList<>();
            List<RollbackFailure> nonCriticalFailures = new ArrayList<>();

            // Execute compensating actions in reverse order
            for (int i = compensatingActions.size() - 1; i >= 0; i--) {
                CompensatingAction action = compensatingActions.get(i);
                try {
                    action.execute();
                    rolledBack++;
                    logger.trace("Rolled back action: {} (transaction: {})", action.getName(), transactionId);
                } catch (Exception e) {
                    RollbackFailure failure = new RollbackFailure(action.getName(), e);
                    rollbackFailures.add(failure);

                    // Categorize failure severity
                    if (isCriticalRollbackFailure(action.getName(), e)) {
                        criticalFailures.add(failure);
                        logger.error("CRITICAL rollback failure: {} (transaction: {}) - {}",
                                   action.getName(), transactionId, e.getMessage(), e);
                    } else {
                        nonCriticalFailures.add(failure);
                        logger.warn("Non-critical rollback failure: {} (transaction: {}) - {}",
                                  action.getName(), transactionId, e.getMessage());
                    }
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            int totalFailures = rollbackFailures.size();

            // Set final state based on failure severity
            if (!criticalFailures.isEmpty()) {
                state = TransactionState.ROLLBACK_FAILED;
                logger.error("Transaction rollback FAILED with {} critical errors: {} (ID: {}, " +
                           "duration: {}ms, success: {}, total_failed: {})",
                           criticalFailures.size(), operationName, transactionId, duration, rolledBack, totalFailures);

                // Trigger escalation for critical failures
                escalateRollbackFailures(criticalFailures);

            } else {
                state = TransactionState.ROLLED_BACK;
                if (totalFailures > 0) {
                    logger.warn("Transaction rollback completed with {} non-critical errors: {} (ID: {}, " +
                              "duration: {}ms, success: {}, failed: {})",
                              totalFailures, operationName, transactionId, duration, rolledBack, totalFailures);
                } else {
                    logger.info("Transaction rollback completed successfully: {} (ID: {}, duration: {}ms, actions: {})",
                               operationName, transactionId, duration, rolledBack);
                }
            }

            // Log detailed failure information for audit
            if (!rollbackFailures.isEmpty()) {
                AuditLogger.logSecurityViolation(AuditLogger.AuditEventType.SYSTEM_INITIALIZATION,
                    "TransactionRollbackFailures",
                    String.format("Transaction %d rollback had %d failures", transactionId, totalFailures),
                    "Critical: " + criticalFailures.size() + ", Non-critical: " + nonCriticalFailures.size());
            }
        }

        /**
         * Determine if a rollback failure is critical and requires escalation.
         */
        private boolean isCriticalRollbackFailure(String actionName, Exception e) {
            // Consider security-related, resource-related, or state corruption failures as critical
            return actionName.toLowerCase().contains("security") ||
                   actionName.toLowerCase().contains("privilege") ||
                   actionName.toLowerCase().contains("affinity") ||
                   e instanceof SecurityException ||
                   e instanceof IllegalStateException ||
                   e.getMessage().toLowerCase().contains("corruption") ||
                   e.getMessage().toLowerCase().contains("inconsistent");
        }

        /**
         * Escalate critical rollback failures for immediate attention.
         */
        private void escalateRollbackFailures(List<RollbackFailure> criticalFailures) {
            try {
                // Log critical system alert
                logger.error("ESCALATION: Transaction {} has {} critical rollback failures requiring manual intervention",
                           transactionId, criticalFailures.size());

                // Create escalation record for monitoring systems
                StringBuilder escalationDetails = new StringBuilder();
                escalationDetails.append("Transaction ID: ").append(transactionId).append("\n");
                escalationDetails.append("Operation: ").append(operationName).append("\n");
                escalationDetails.append("Critical failures:\n");

                for (RollbackFailure failure : criticalFailures) {
                    escalationDetails.append("- ").append(failure.getActionName())
                                   .append(": ").append(failure.getException().getMessage()).append("\n");
                }

                // Log as security violation for SIEM integration
                AuditLogger.logSecurityViolation(AuditLogger.AuditEventType.SYSTEM_INITIALIZATION,
                    "CriticalRollbackFailure",
                    "Critical transaction rollback failure requires manual intervention",
                    escalationDetails.toString());

                // TODO: Add integration with monitoring/alerting systems
                // - Send to monitoring system (e.g., Prometheus alerts)
                // - Create incident ticket
                // - Send email/SMS to on-call engineer

            } catch (Exception escalationError) {
                logger.error("Failed to escalate rollback failures: {}", escalationError.getMessage(), escalationError);
            }
        }

        public long getTransactionId() { return transactionId; }
        public String getOperationName() { return operationName; }
        public TransactionState getState() { return state; }
        public long getStartTime() { return startTime; }
        public int getCompensatingActionCount() { return compensatingActions.size(); }
        public List<RollbackFailure> getRollbackFailures() { return new ArrayList<>(rollbackFailures); }
        public boolean hasRollbackFailures() { return !rollbackFailures.isEmpty(); }
        public boolean hasCriticalRollbackFailures() {
            return rollbackFailures.stream().anyMatch(f -> isCriticalRollbackFailure(f.getActionName(), f.getException()));
        }
    }

    /**
     * Compensating action for rollback.
     */
    private static class CompensatingAction {
        private final String name;
        private final Runnable action;

        CompensatingAction(String name, Runnable action) {
            this.name = name;
            this.action = action;
        }

        void execute() {
            action.run();
        }

        String getName() {
            return name;
        }
    }

    /**
     * Transaction state enumeration.
     */
    public enum TransactionState {
        CREATED,
        ACTIVE,
        COMMITTED,
        ROLLING_BACK,
        ROLLED_BACK,
        ROLLBACK_FAILED  // New state for failed rollbacks
    }

    /**
     * Rollback failure information for escalation.
     */
    public static class RollbackFailure {
        private final String actionName;
        private final Exception exception;
        private final long timestamp;

        public RollbackFailure(String actionName, Exception exception) {
            this.actionName = actionName;
            this.exception = exception;
            this.timestamp = System.currentTimeMillis();
        }

        public String getActionName() { return actionName; }
        public Exception getException() { return exception; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("RollbackFailure{action=%s, error=%s, time=%d}",
                                actionName, exception.getMessage(), timestamp);
        }
    }

    /**
     * Functional interface for transactional operations.
     */
    @FunctionalInterface
    public interface TransactionalOperation<T> {
        T execute(TransactionContext context) throws Exception;
    }

    /**
     * Transaction exception for handling transaction failures.
     */
    public static class TransactionException extends RuntimeException {
        private final long transactionId;

        public TransactionException(String message) {
            super(message);
            this.transactionId = getCurrentTransactionId();
        }

        public TransactionException(String message, Throwable cause) {
            super(message, cause);
            this.transactionId = getCurrentTransactionId();
        }

        public long getTransactionId() {
            return transactionId;
        }

        private static long getCurrentTransactionId() {
            TransactionContext context = getCurrentTransaction();
            return context != null ? context.getTransactionId() : -1;
        }
    }

    /**
     * Get transaction statistics for monitoring.
     */
    public static TransactionStats getStats() {
        return new TransactionStats(transactionIdGenerator.get());
    }

    /**
     * Transaction statistics for monitoring.
     */
    public static class TransactionStats {
        public final long totalTransactions;

        TransactionStats(long totalTransactions) {
            this.totalTransactions = totalTransactions;
        }

        @Override
        public String toString() {
            return String.format("TransactionStats{total=%d}", totalTransactions);
        }
    }

    // Prevent instantiation
    private TransactionManager() {
        throw new UnsupportedOperationException("Utility class");
    }
}