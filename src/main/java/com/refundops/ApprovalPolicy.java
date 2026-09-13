package com.refundops;

import java.math.BigDecimal;

public final class ApprovalPolicy {
    static final String DEFAULT_THRESHOLD = "10000";
    static final String DEFAULT_VERSION = "refund-policy-v1";
    private static final String THRESHOLD_ERROR =
            "refunds.approval.threshold (REFUNDS_APPROVAL_THRESHOLD) must be a non-negative INR amount"
                    + " with at most two decimal places.";

    public final BigDecimal approvalThreshold;
    public final String policyVersion;

    public ApprovalPolicy(String threshold, String policyVersion) {
        if (threshold == null || threshold.isBlank()) {
            throw new IllegalArgumentException(THRESHOLD_ERROR);
        }
        try {
            this.approvalThreshold = new BigDecimal(threshold);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(THRESHOLD_ERROR, error);
        }
        if (approvalThreshold.signum() < 0 || approvalThreshold.scale() > 2) {
            throw new IllegalArgumentException(THRESHOLD_ERROR);
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "refunds.approval.version (REFUNDS_APPROVAL_VERSION) must be non-blank.");
        }
        this.policyVersion = policyVersion;
    }

    public static ApprovalPolicy defaults() {
        return new ApprovalPolicy(DEFAULT_THRESHOLD, DEFAULT_VERSION);
    }

    public boolean requiresApproval(BigDecimal amount) {
        return amount.compareTo(approvalThreshold) > 0;
    }
}
