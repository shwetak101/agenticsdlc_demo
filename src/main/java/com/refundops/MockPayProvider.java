package com.refundops;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MockPayProvider {
    public enum FaultProfile {
        SUCCESS,
        CONFIRMED_NOT_SENT_ONCE,
        ACCEPTED_RESPONSE_LOST_ONCE
    }

    public enum DeliveryCertainty {
        CONFIRMED_NOT_SENT,
        OUTCOME_UNKNOWN
    }

    private final FaultProfile faultProfile;
    private final Map<String, RefundModels.Payment> receipts = new LinkedHashMap<>();
    private final Set<String> injectedFaults = new HashSet<>();

    @Autowired
    public MockPayProvider(
            @Value("${refunds.mockpay.fault-profile:SUCCESS}") String configuredFaultProfile) {
        try {
            this.faultProfile = FaultProfile.valueOf(
                    configuredFaultProfile.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Unsupported MockPay fault profile: "
                    + configuredFaultProfile, error);
        }
    }

    public MockPayProvider() {
        this(FaultProfile.SUCCESS.name());
    }

    public RefundModels.Payment send(
            String paymentId, RefundModels.Refund refund, Instant sentAt) {
        return send("mockpay-refund:" + refund.id, paymentId, refund, sentAt);
    }

    public synchronized RefundModels.Payment send(
            String providerIdempotencyKey, String paymentId, RefundModels.Refund refund, Instant sentAt) {
        RefundModels.Payment existing = receipts.get(providerIdempotencyKey);
        if (existing != null) {
            requireSameInstruction(existing, paymentId, refund);
            return existing;
        }

        if (faultProfile == FaultProfile.CONFIRMED_NOT_SENT_ONCE
                && injectedFaults.add(providerIdempotencyKey)) {
            throw new ProviderFailure(DeliveryCertainty.CONFIRMED_NOT_SENT,
                    "MockPay simulated a transient failure before accepting the instruction.");
        }

        RefundModels.Payment receipt = new RefundModels.Payment(paymentId, refund, sentAt);
        receipts.put(providerIdempotencyKey, receipt);
        if (faultProfile == FaultProfile.ACCEPTED_RESPONSE_LOST_ONCE
                && injectedFaults.add(providerIdempotencyKey)) {
            throw new ProviderFailure(DeliveryCertainty.OUTCOME_UNKNOWN,
                    "MockPay accepted the instruction but simulated a lost response.");
        }
        return receipt;
    }

    public synchronized Optional<RefundModels.Payment> findReceipt(String providerIdempotencyKey) {
        return Optional.ofNullable(receipts.get(providerIdempotencyKey));
    }

    public synchronized void reset() {
        receipts.clear();
        injectedFaults.clear();
    }

    public FaultProfile faultProfile() {
        return faultProfile;
    }

    synchronized int receiptCount() {
        return receipts.size();
    }

    private static void requireSameInstruction(
            RefundModels.Payment existing, String paymentId, RefundModels.Refund refund) {
        if (!existing.id.equals(paymentId)
                || !existing.refundId.equals(refund.id)
                || !existing.orderId.equals(refund.orderId)
                || existing.amount.compareTo(refund.amount) != 0) {
            throw new IllegalStateException(
                    "A MockPay idempotency key was reused for a different instruction.");
        }
    }

    public static final class ProviderFailure extends RuntimeException {
        private final DeliveryCertainty certainty;

        ProviderFailure(DeliveryCertainty certainty, String message) {
            super(message);
            this.certainty = certainty;
        }

        public DeliveryCertainty getCertainty() {
            return certainty;
        }
    }
}
