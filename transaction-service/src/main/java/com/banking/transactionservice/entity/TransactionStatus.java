package com.banking.transactionservice.entity;

/**
 * PENDING -> PROCESSING -> COMPLETED
 *                      -> PENDING_VERIFICATION
 *                          -> COMPLETED
 *                          -> FLAGGED (SAGA REFUND)
 *
 * Enum representing the status of a transaction.
 */
public enum TransactionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    PENDING_VERIFICATION,
    FLAGGED
}
