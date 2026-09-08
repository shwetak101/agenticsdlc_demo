package com.refundops;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class RefundModels {
    private RefundModels() {
    }

    public static final class Application {
        public final String name = "RefundOps";
        public final String version = "Legacy baseline";
        public final String javaBaseline = "11";
        public final String springBootVersion = "2.7.18";
        public final String mode = "AUTO_PROCESS";
        public final String provider = "MockPay";
        public final String storage = "In-memory";
    }

    public static final class Dashboard {
        public final Application application = new Application();
        public final List<Order> orders;
        public final List<Refund> refunds;
        public final List<Payment> payments;
        public final List<Event> events;

        public Dashboard(List<Order> orders, List<Refund> refunds, List<Payment> payments, List<Event> events) {
            this.orders = List.copyOf(orders);
            this.refunds = List.copyOf(refunds);
            this.payments = List.copyOf(payments);
            this.events = List.copyOf(events);
        }
    }

    public static final class Order {
        public final String id;
        public final String customerName;
        public final String customerInitials;
        public final String customerEmail;
        public final String product;
        public final String category;
        public final BigDecimal amount;
        public final String currency = "INR";
        public final Instant purchasedAt;
        public final String paymentMethod;
        public final String status;

        public Order(String id, String customerName, String customerInitials, String customerEmail,
                     String product, String category, BigDecimal amount, Instant purchasedAt,
                     String paymentMethod, String status) {
            this.id = id;
            this.customerName = customerName;
            this.customerInitials = customerInitials;
            this.customerEmail = customerEmail;
            this.product = product;
            this.category = category;
            this.amount = amount;
            this.purchasedAt = purchasedAt;
            this.paymentMethod = paymentMethod;
            this.status = status;
        }

        public Order refundSent() {
            return new Order(id, customerName, customerInitials, customerEmail, product, category, amount,
                    purchasedAt, paymentMethod, "REFUND_SENT");
        }
    }

    public static final class Refund {
        public final String id;
        public final String orderId;
        public final String customerName;
        public final BigDecimal amount;
        public final String currency = "INR";
        public final String requesterUsername;
        public final String requesterName;
        public final String reason;
        public final String notes;
        public final String status = "SENT_TO_PROVIDER";
        public final Instant requestedAt;
        public final String paymentId;

        public Refund(String id, Order order, DemoUsers.User requester, String reason, String notes,
                      Instant requestedAt, String paymentId) {
            this.id = id;
            this.orderId = order.id;
            this.customerName = order.customerName;
            this.amount = order.amount;
            this.requesterUsername = requester.username;
            this.requesterName = requester.displayName;
            this.reason = reason;
            this.notes = notes;
            this.requestedAt = requestedAt;
            this.paymentId = paymentId;
        }
    }

    public static final class Payment {
        public final String id;
        public final String refundId;
        public final String orderId;
        public final BigDecimal amount;
        public final String currency = "INR";
        public final String status = "SENT_TO_PROVIDER";
        public final String provider = "MockPay";
        public final Instant sentAt;
        public final String requesterName;

        public Payment(String id, Refund refund, Instant sentAt) {
            this.id = id;
            this.refundId = refund.id;
            this.orderId = refund.orderId;
            this.amount = refund.amount;
            this.sentAt = sentAt;
            this.requesterName = refund.requesterName;
        }
    }

    public static final class Event {
        public final String id;
        public final Instant occurredAt;
        public final String type;
        public final String title;
        public final String detail;
        public final String actorDisplayName;
        public final String refundId;

        public Event(String id, Instant occurredAt, String type, String title, String detail,
                     String actorDisplayName, String refundId) {
            this.id = id;
            this.occurredAt = occurredAt;
            this.type = type;
            this.title = title;
            this.detail = detail;
            this.actorDisplayName = actorDisplayName;
            this.refundId = refundId;
        }
    }

    public static final class RefundResult {
        public final Refund refund;
        public final Payment payment;
        public final boolean replayed;

        public RefundResult(Refund refund, Payment payment, boolean replayed) {
            this.refund = refund;
            this.payment = payment;
            this.replayed = replayed;
        }
    }
}
