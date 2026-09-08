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
    private final Map<String, Order> orders = new LinkedHashMap<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final List<Payment> payments = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Submission> submissions = new LinkedHashMap<>();
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
        return new Dashboard(new ArrayList<>(orders.values()), refunds, payments, events);
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
        String paymentId = "PAY-" + (paymentSequence + 1);
        Refund refund = new Refund(refundId, order, requester, request.reason.name(), notes, now, paymentId);
        Payment payment = provider.send(paymentId, refund, now);
        RefundResult result = new RefundResult(refund, payment, false);
        // The mock provider and every state transition share this monitor, including reset and snapshots.
        refundSequence++;
        paymentSequence++;
        orders.put(order.id, order.refundSent());
        refunds.add(0, refund);
        payments.add(0, payment);
        events.add(0, new Event("EVT-" + (++eventSequence), now, "REFUND_SENT",
                "Refund sent to MockPay", refundId + " · " + order.id + " · INR "
                + order.amount.toPlainString() + " · " + request.reason.name(), requester.displayName, refundId));
        submissions.put(key, new Submission(username, order.id, request.reason.name(), notes, result));
        return result;
    }

    public synchronized Dashboard reset() {
        orders.clear();
        refunds.clear();
        payments.clear();
        events.clear();
        submissions.clear();
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
                "BASELINE_READY", "Legacy baseline ready",
                "12 synthetic orders loaded. All valid refunds are sent immediately to MockPay.",
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
        private final RefundResult result;

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
