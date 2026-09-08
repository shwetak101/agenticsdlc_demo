package com.refundops;

import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class MockPayProvider {
    public RefundModels.Payment send(String paymentId, RefundModels.Refund refund, Instant sentAt) {
        // Pure in-process simulation: no network, credentials, or financial side effects.
        return new RefundModels.Payment(paymentId, refund, sentAt);
    }
}
