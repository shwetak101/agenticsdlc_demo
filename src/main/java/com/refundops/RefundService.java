package com.refundops;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import javax.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.refundops.RefundModels.Dashboard;
import com.refundops.RefundModels.Event;
import com.refundops.RefundModels.Order;
import com.refundops.RefundModels.Payment;
import com.refundops.RefundModels.Refund;
import com.refundops.RefundModels.RefundResult;

@Service
public class RefundService {
    private static final BigDecimal APPROVAL_THRESHOLD = new BigDecimal("10000");
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final List<Payment> payments = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Submission> submissions = new LinkedHashMap<>();
    private final Map<String, RefundModels.ProviderOperation> providerOperations = new LinkedHashMap<>();
    private final MockPayProvider provider;
    private final Validator validator;
    private int refundSequence;
    private int paymentSequence;
    private int eventSequence;

    public RefundService(MockPayProvider provider, Validator validator) {
        this.provider = provider;
        this.validator = validator;
        reset();
    }

    public synchronized Dashboard dashboard() {
        return new Dashboard(new ArrayList<>(orders.values()), refunds, payments, events,
                new ArrayList<>(providerOperations.values()));
    }

    public synchronized RefundResult create(String username, RefundRequest request) {
        Set<ConstraintViolation<RefundRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        DemoUsers.User requester = DemoUsers.get(username);
        String key = UUID.fromString(request.idempotencyKey).toString();
        String notes = request.notes == null ? "" : request.notes;
        Submission previous = submissions.get(key);
        if (previous != null) {
            if (!previous.matches(username, request.orderId, request.reason.name(), notes)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "This idempotency key was already used for a different requester or payload.");
            }
            return new RefundResult(previous.result.refund, previous.result.payment, true);
        }
        Order order = orders.get(request.orderId);
        if (order == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found.");
        }
        if (!"PAID".equals(order.status)) {
            throw new ApiException(HttpStatus.CONFLICT, "ORDER_ALREADY_REFUNDED",
                    "A refund has already been sent for this order.");
        }
        Instant now = Instant.now();
        String refundId = "RF-" + (refundSequence + 1);
        refundSequence++;
        if (order.amount.compareTo(APPROVAL_THRESHOLD) > 0) {
            Refund refund = new Refund(refundId, order, requester, request.reason.name(), notes,
                    "PENDING_APPROVAL", now, null, null, null, "");
            orders.put(order.id, order.refundPending());
            events.add(0, new Event("EVT-" + (++eventSequence), now, "REFUND_PENDING_APPROVAL",
                    "Refund awaiting approval", refundId + " · " + order.id + " · INR "
                    + order.amount.toPlainString() + " · requested by " + requester.displayName,
                    requester.displayName, refundId));
            RefundResult result = new RefundResult(refund, null, false);
            refunds.add(0, refund);
            submissions.put(key, new Submission(username, order.id, request.reason.name(), notes, result));
            return result;
        }

        String paymentId = "PAY-" + (++paymentSequence);
        Refund refund = new Refund(refundId, order, requester, request.reason.name(), notes,
                "PROVIDER_SUBMISSION_PENDING", now, paymentId, null, null, "");
        RefundResult result = new RefundResult(refund, null, false);
        orders.put(order.id, order.refundPending());
        refunds.add(0, refund);
        submissions.put(key, new Submission(username, order.id, request.reason.name(), notes, result));
        prepareProviderOperation(refund, paymentId);
        return submitToProvider(0, refund, requester, "REFUND_SENT",
                "Refund sent to MockPay", "automatic processing");
    }

    public synchronized RefundResult approve(
            String username, String refundId, ApprovalDecisionRequest request) {
        DemoUsers.User approver = requireApprover(username);
        int index = refundIndex(refundId);
        Refund refund = refunds.get(index);
        if (refund.requesterUsername.equals(approver.username)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                    "Requesters cannot approve their own refund.");
        }
        if ("SENT_TO_PROVIDER".equals(refund.status)) {
            return new RefundResult(refund, paymentFor(refund), true);
        }
        if (isRecoveryStatus(refund.status)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROVIDER_RECOVERY_REQUIRED",
                    "The approval is recorded. Use the provider recovery action; approval will not resubmit it.");
        }
        requirePending(refund);
        String notes = decisionNotes(request);
        Instant now = Instant.now();
        String paymentId = "PAY-" + (++paymentSequence);
        Refund approved = refund.decide(
                "PROVIDER_SUBMISSION_PENDING", paymentId, approver, now, notes);
        refunds.set(index, approved);
        updateSubmissionResult(approved, null);
        prepareProviderOperation(approved, paymentId);
        return submitToProvider(index, approved, approver, "REFUND_APPROVED",
                "Refund approved and sent to MockPay",
                "requested by " + refund.requesterName);
    }

    public synchronized RefundResult reconcileProvider(String username, String refundId) {
        DemoUsers.User approver = requireApprover(username);
        int index = refundIndex(refundId);
        Refund refund = refunds.get(index);
        if ("SENT_TO_PROVIDER".equals(refund.status)) {
            return new RefundResult(refund, paymentFor(refund), true);
        }
        if (!"PROVIDER_OUTCOME_UNKNOWN".equals(refund.status)) {
            throw new ApiException(HttpStatus.CONFLICT, "RECONCILIATION_NOT_AVAILABLE",
                    "Reconciliation is available only when the provider outcome is unknown.");
        }
        requireRecoveryApproval(refund);
        RefundModels.ProviderOperation operation = providerOperation(refund.id);
        Payment receipt = provider.findReceipt(operation.providerIdempotencyKey)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PROVIDER_RECEIPT_NOT_FOUND",
                        "MockPay has no receipt for this key. The outcome remains unknown; do not retry."));
        return confirmProviderReceipt(index, refund, receipt, operation, approver, Instant.now(),
                "REFUND_RECONCILED", "MockPay receipt reconciled without resubmission",
                "provider receipt registry");
    }

    public synchronized RefundResult retryProvider(
            String username, String refundId, ProviderRetryRequest request) {
        Set<ConstraintViolation<ProviderRetryRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        DemoUsers.User approver = requireApprover(username);
        int index = refundIndex(refundId);
        Refund refund = refunds.get(index);
        if ("SENT_TO_PROVIDER".equals(refund.status)) {
            return new RefundResult(refund, paymentFor(refund), true);
        }
        if (!"PROVIDER_RETRY_REQUIRED".equals(refund.status)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROVIDER_RETRY_NOT_ALLOWED",
                    "Retry is allowed only after MockPay confirms that nothing was sent.");
        }
        requireRecoveryApproval(refund);
        return submitToProvider(index, refund, approver, "REFUND_RECOVERED",
                "Refund explicitly retried and sent to MockPay", "approver-confirmed retry");
    }

    public synchronized RefundResult reject(
            String username, String refundId, ApprovalDecisionRequest request) {
        DemoUsers.User approver = requireApprover(username);
        int index = refundIndex(refundId);
        Refund refund = refunds.get(index);
        if (refund.requesterUsername.equals(approver.username)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                    "Requesters cannot reject their own refund.");
        }
        if ("REJECTED".equals(refund.status) && approver.username.equals(refund.decidedByUsername)) {
            return new RefundResult(refund, null, true);
        }
        requirePending(refund);
        String notes = decisionNotes(request);
        Instant now = Instant.now();
        Refund rejected = refund.decide("REJECTED", null, approver, now, notes);
        refunds.set(index, rejected);
        updateSubmissionResult(rejected, null);
        Order order = orders.get(refund.orderId);
        orders.put(order.id, order.refundRejected());
        events.add(0, new Event("EVT-" + (++eventSequence), now, "REFUND_REJECTED",
                "Refund rejected", refund.id + " · " + refund.orderId + " · INR "
                + refund.amount.toPlainString() + " · requested by " + refund.requesterName,
                approver.displayName, refund.id));
        return new RefundResult(rejected, null, false);
    }

    private DemoUsers.User requireApprover(String username) {
        DemoUsers.User user = DemoUsers.get(username);
        if (!user.roles.contains("APPROVER")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "APPROVER_REQUIRED",
                    "Only an approver can decide high-value refunds or recover provider submissions.");
        }
        return user;
    }

    private int refundIndex(String refundId) {
        for (int index = 0; index < refunds.size(); index++) {
            if (refunds.get(index).id.equals(refundId)) {
                return index;
            }
        }
        throw new ApiException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund not found.");
    }

    private static void requirePending(Refund refund) {
        if (!"PENDING_APPROVAL".equals(refund.status)) {
            throw new ApiException(HttpStatus.CONFLICT, "DECISION_ALREADY_MADE",
                    "This refund already has a final decision.");
        }
    }

    private static String decisionNotes(ApprovalDecisionRequest request) {
        return request.notes == null ? "" : request.notes;
    }

    private void updateSubmissionResult(Refund refund, Payment payment) {
        submissions.values().stream()
                .filter(submission -> submission.result.refund.id.equals(refund.id))
                .findFirst()
                .ifPresent(submission -> submission.result = new RefundResult(refund, payment, false));
    }

    private void prepareProviderOperation(Refund refund, String paymentId) {
        providerOperations.put(refund.id, new RefundModels.ProviderOperation(
                refund.id, refund.orderId, paymentId, providerIdempotencyKey(refund.id),
                "READY", 0, null, "", "", null, null));
    }

    private RefundResult submitToProvider(
            int refundIndex, Refund refund, DemoUsers.User actor, String successEventType,
            String successTitle, String successDetail) {
        Instant attemptedAt = Instant.now();
        RefundModels.ProviderOperation operation = providerOperation(refund.id).beginAttempt(attemptedAt);
        providerOperations.put(refund.id, operation);
        try {
            Payment receipt = provider.send(operation.providerIdempotencyKey, operation.paymentId,
                    refund, attemptedAt);
            return confirmProviderReceipt(refundIndex, refund, receipt, operation, actor, attemptedAt,
                    successEventType, successTitle, successDetail);
        } catch (MockPayProvider.ProviderFailure failure) {
            boolean confirmedNotSent =
                    failure.getCertainty() == MockPayProvider.DeliveryCertainty.CONFIRMED_NOT_SENT;
            String refundStatus = confirmedNotSent
                    ? "PROVIDER_RETRY_REQUIRED" : "PROVIDER_OUTCOME_UNKNOWN";
            String operationStatus = confirmedNotSent ? "CONFIRMED_NOT_SENT" : "OUTCOME_UNKNOWN";
            String code = confirmedNotSent
                    ? "PROVIDER_CONFIRMED_NOT_SENT" : "PROVIDER_OUTCOME_UNKNOWN";
            String message = confirmedNotSent
                    ? "MockPay confirmed that no instruction was accepted. An approver must explicitly retry this recorded refund."
                    : "MockPay may have accepted the instruction. An approver must reconcile its receipt; it will not be resent automatically.";
            Refund failed = refund.providerStatus(refundStatus);
            refunds.set(refundIndex, failed);
            providerOperations.put(refund.id,
                    operation.failed(operationStatus, code, failure.getMessage()));
            updateSubmissionResult(failed, null);
            events.add(0, new Event("EVT-" + (++eventSequence), attemptedAt,
                    confirmedNotSent ? "PROVIDER_CONFIRMED_NOT_SENT" : "PROVIDER_OUTCOME_UNKNOWN",
                    confirmedNotSent
                            ? "MockPay confirmed no instruction was sent"
                            : "MockPay outcome requires reconciliation",
                    refund.id + " · " + refund.orderId + " · stable key "
                            + operation.providerIdempotencyKey,
                    actor.displayName, refund.id));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
        }
    }

    private RefundResult confirmProviderReceipt(
            int refundIndex, Refund refund, Payment receipt,
            RefundModels.ProviderOperation operation, DemoUsers.User actor, Instant resolvedAt,
            String eventType, String eventTitle, String eventDetail) {
        Refund sent = refund.providerStatus("SENT_TO_PROVIDER");
        refunds.set(refundIndex, sent);
        if (payments.stream().noneMatch(payment -> payment.id.equals(receipt.id))) {
            payments.add(0, receipt);
        }
        providerOperations.put(refund.id, operation.resolved(actor, resolvedAt));
        Order order = orders.get(refund.orderId);
        orders.put(order.id, order.refundSent());
        updateSubmissionResult(sent, receipt);
        events.add(0, new Event("EVT-" + (++eventSequence), resolvedAt, eventType, eventTitle,
                refund.id + " · " + refund.orderId + " · INR " + refund.amount.toPlainString()
                        + " · " + eventDetail,
                actor.displayName, refund.id));
        return new RefundResult(sent, receipt, false);
    }

    private RefundModels.ProviderOperation providerOperation(String refundId) {
        RefundModels.ProviderOperation operation = providerOperations.get(refundId);
        if (operation == null) {
            throw new IllegalStateException("Refund is missing its MockPay operation.");
        }
        return operation;
    }

    private Payment paymentFor(Refund refund) {
        return payments.stream()
                .filter(candidate -> refund.id.equals(candidate.refundId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Sent refund is missing its payment."));
    }

    private static String providerIdempotencyKey(String refundId) {
        return "mockpay-refund:" + refundId;
    }

    private static boolean isRecoveryStatus(String status) {
        return "PROVIDER_RETRY_REQUIRED".equals(status)
                || "PROVIDER_OUTCOME_UNKNOWN".equals(status);
    }

    private static void requireRecoveryApproval(Refund refund) {
        if (refund.amount.compareTo(APPROVAL_THRESHOLD) > 0
                && (refund.decidedByUsername == null
                || refund.decidedByUsername.equals(refund.requesterUsername))) {
            throw new ApiException(HttpStatus.CONFLICT, "RECOVERY_APPROVAL_REQUIRED",
                    "A high-value refund needs a recorded independent approval before recovery.");
        }
    }

    public synchronized Dashboard reset() {
        orders.clear();
        refunds.clear();
        payments.clear();
        events.clear();
        submissions.clear();
        providerOperations.clear();
        // Reset holds the service monitor used by every call and snapshot; MockPay state cannot race it.
        provider.reset();
        refundSequence = 2000;
        paymentSequence = 3000;
        eventSequence = 4000;
        seed("ORD-1042", "Aarav Deshmane", "AD", "aarav.deshmane", "NovaBook Air 14 Laptop",
                "Electronics", "84990", "2026-09-01T09:15:00Z", "Credit Card");
        seed("ORD-1043", "Meera Kulshrestha", "MK", "meera.kulshrestha", "Aura X Pro Smartphone",
                "Electronics", "64999", "2026-09-01T11:20:00Z", "UPI");
        seed("ORD-1044", "Kabir Menon", "KM", "kabir.menon", "Harbor Ergonomic Office Chair",
                "Home & Furniture", "25000", "2026-09-02T06:45:00Z", "Debit Card");
        seed("ORD-1045", "Anaya Bendre", "AB", "anaya.bendre", "EchoWave Noise-Cancelling Headphones",
                "Electronics", "10000", "2026-09-02T10:00:00Z", "UPI");
        seed("ORD-1046", "Rohan Vardhan", "RV", "rohan.vardhan", "TrailStep Running Shoes",
                "Sports & Outdoors", "2500", "2026-09-03T08:30:00Z", "UPI");
        seed("ORD-1047", "Ishita Narvekar", "IN", "ishita.narvekar", "Everyday Insulated Water Bottle",
                "Home & Kitchen", "1499", "2026-09-03T12:10:00Z", "Debit Card");
        seed("ORD-1048", "Vihaan Srinivas", "VS", "vihaan.srinivas", "BrewCraft Espresso Machine",
                "Home & Kitchen", "18990", "2026-09-04T05:55:00Z", "Credit Card");
        seed("ORD-1049", "Tara Luthra", "TL", "tara.luthra", "LinenNest Cotton Bedding Set",
                "Home & Living", "3999", "2026-09-04T14:25:00Z", "Net Banking");
        seed("ORD-1050", "Neel Bhattachar", "NB", "neel.bhattachar", "PulseFit Smartwatch",
                "Wearables", "7999", "2026-09-05T07:00:00Z", "UPI");
        seed("ORD-1051", "Sana Mirza", "SM", "sana.mirza", "RoamLite Cabin Suitcase",
                "Travel", "5990", "2026-09-05T09:35:00Z", "Credit Card");
        seed("ORD-1052", "Devika Baliga", "DB", "devika.baliga", "PureAir Home Air Purifier",
                "Home Appliances", "12499", "2026-09-06T08:50:00Z", "Debit Card");
        seed("ORD-1053", "Arjun Talwalkar", "AT", "arjun.talwalkar", "FrameView 27-inch Monitor",
                "Electronics", "22990", "2026-09-06T13:40:00Z", "Net Banking");
        events.add(new Event("EVT-" + (++eventSequence), Instant.parse("2026-09-07T08:00:00Z"),
                "CANDIDATE_READY", "Approval candidate ready",
                "12 synthetic orders loaded. Refunds above INR 10,000 require independent approval.",
                "System", null));
        return dashboard();
    }

    private void seed(String id, String name, String initials, String email, String product,
                      String category, String amount, String purchasedAt, String paymentMethod) {
        orders.put(id, new Order(id, name, initials, email + "@example.com", product, category,
                new BigDecimal(amount), Instant.parse(purchasedAt), paymentMethod, "PAID"));
    }

    private static final class Submission {
        private final String username;
        private final String orderId;
        private final String reason;
        private final String notes;
        private RefundResult result;

        private Submission(String username, String orderId, String reason, String notes, RefundResult result) {
            this.username = username;
            this.orderId = orderId;
            this.reason = reason;
            this.notes = notes;
            this.result = result;
        }

        private boolean matches(String username, String orderId, String reason, String notes) {
            return this.username.equals(username) && this.orderId.equals(orderId)
                    && this.reason.equals(reason) && this.notes.equals(notes);
        }
    }
}
