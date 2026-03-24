package org.opensearch.storage.common.tiering;

/**
 * Exception thrown when a tiering operation is rejected due to validation failures.
 * This exception wraps the original exception and provides structured rejection reasons
 * for better metrics tracking and error handling.
 *
 * @opensearch.internal
 */
public class TieringRejectionException extends RuntimeException {

    /**
     * Enumeration of all possible tiering rejection reasons for structured error handling.
     */
    public enum RejectionReason {
        NO_WARM_NODES,
        INDEX_RED_STATUS,
        HIGH_JVM_UTILIZATION,
        INSUFFICIENT_SPACE_WARM,
        INSUFFICIENT_SPACE_HOT,
        CCR_INDEX_REJECTION,
        REMOTE_STORE_NOT_ENABLED,
        INVALID_TIER_TRANSITION,
        LARGEST_SHARD_SPACE_INSUFFICIENT,
        HIGH_FILE_CACHE_UTILIZATION,
        CONCURRENT_LIMIT_EXCEEDED,
        SHARD_LIMIT_EXCEEDED
    }

    private final RejectionReason rejectionReason;

    /**
     * Constructs a TieringRejectionException with rejection reason and original exception.
     *
     * @param rejectionReason the structured reason for rejection
     * @param originalException the original exception that was thrown
     */
    public TieringRejectionException(RejectionReason rejectionReason, RuntimeException originalException) {
        super(originalException.getMessage(), originalException);
        this.rejectionReason = rejectionReason;
    }

    /**
     * Gets the structured rejection reason.
     *
     * @return the rejection reason enum value
     */
    public RejectionReason getRejectionReason() {
        return rejectionReason;
    }
}
