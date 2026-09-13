package com.refundops;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import com.refundops.RefundModels.Dashboard;
import com.refundops.RefundModels.RefundResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RefundServiceTest {
    private ValidatorFactory factory;
    private RefundService service;
    private MockPayProvider provider;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        provider = spy(new MockPayProvider());
        service = new RefundService(provider, factory.getValidator());
    }

    @AfterEach
    void closeValidator() {
        factory.close();
    }

    @Test
    void simultaneousReplayOnlySendsOnePayment() throws Exception {
        String key = UUID.randomUUID().toString();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RefundResult>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.create("shweta", request(key, "ORD-1046"));
                }));
            }
            start.countDown();
            int newResults = 0;
            for (Future<RefundResult> future : futures) {
                RefundResult result = future.get(15, TimeUnit.SECONDS);
                assertThat(result.refund.id).isEqualTo("RF-2001");
                assertThat(result.payment.id).isEqualTo("PAY-3001");
                if (!result.replayed) {
                    newResults++;
                }
            }
            assertThat(newResults).isEqualTo(1);
            assertThat(service.dashboard().payments).hasSize(1);
            verify(provider, times(1)).send(anyString(), any(), any());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void simultaneousDifferentKeysAndUsersOnlySendOnePayment() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                String username = i % 2 == 0 ? "shweta" : "dahnesh";
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        service.create(username, request(UUID.randomUUID().toString(), "ORD-1046"));
                        return true;
                    } catch (ApiException error) {
                        assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(error.getCode()).isEqualTo("ORDER_ALREADY_REFUNDED");
                        return false;
                    }
                }));
            }
            start.countDown();
            int successful = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(15, TimeUnit.SECONDS)) {
                    successful++;
                }
            }
            assertThat(successful).isEqualTo(1);
            Dashboard snapshot = service.dashboard();
            assertThat(snapshot.refunds).hasSize(1);
            assertThat(snapshot.payments).hasSize(1);
            assertThat(snapshot.events).hasSize(2);
            verify(provider, times(1)).send(anyString(), any(), any());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void snapshotsCannotBeMutatedAndDoNotChangeAfterCreateOrReset() {
        Dashboard initial = service.dashboard();
        service.create("shweta", request(UUID.randomUUID().toString(), "ORD-1046"));
        Dashboard sent = service.dashboard();
        assertThat(initial.refunds).isEmpty();
        assertThat(order(initial, "ORD-1046").status).isEqualTo("PAID");
        assertThat(order(sent, "ORD-1046").status).isEqualTo("REFUND_SENT");
        assertThatThrownBy(() -> sent.orders.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> sent.refunds.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> sent.payments.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> sent.events.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> DemoUsers.get("dahnesh").roles.clear())
                .isInstanceOf(UnsupportedOperationException.class);
        service.reset();
        assertThat(sent.refunds).hasSize(1);
        assertThat(order(sent, "ORD-1046").status).isEqualTo("REFUND_SENT");
        assertThat(service.dashboard().refunds).isEmpty();
    }

    @Test
    void canonicalUuidCaseReplaysAndNullNotesMatchOmittedNotes() {
        String key = "aabbccdd-1122-3344-5566-778899aabbcc";
        RefundRequest request = request(key, "ORD-1046");
        request.notes = null;
        RefundResult original = service.create("dahnesh", request);
        RefundRequest retry = request(key.toUpperCase(java.util.Locale.ROOT), "ORD-1046");
        retry.notes = "";
        RefundResult replay = service.create("dahnesh", retry);
        assertThat(replay.replayed).isTrue();
        assertThat(replay.refund).isSameAs(original.refund);
        assertThat(replay.payment).isSameAs(original.payment);
    }

    @Test
    void highValueRequestWaitsForIndependentApprovalBeforeSendingPayment() {
        String key = UUID.randomUUID().toString();
        RefundResult pending = service.create("shweta", request(key, "ORD-1044"));
        assertThat(pending.refund.status).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.payment).isNull();
        assertThat(pending.refund.approvalThreshold).isEqualByComparingTo("10000");
        assertThat(pending.refund.policyVersion).isEqualTo("refund-policy-v1");
        assertThat(service.dashboard().approvalQueue).containsExactly(pending.refund);
        assertThat(service.dashboard().payments).isEmpty();
        verify(provider, times(0)).send(anyString(), any(), any());

        RefundResult approved = service.approve("dahnesh", pending.refund.id, decision("Reviewed independently"));
        assertThat(approved.refund.status).isEqualTo("SENT_TO_PROVIDER");
        assertThat(approved.refund.decidedByUsername).isEqualTo("dahnesh");
        assertThat(approved.payment.id).isEqualTo("PAY-3001");
        assertThat(service.dashboard().approvalQueue).isEmpty();
        verify(provider, times(1)).send(anyString(), any(), any());
        RefundResult replay = service.create("shweta", request(key, "ORD-1044"));
        assertThat(replay.replayed).isTrue();
        assertThat(replay.refund.status).isEqualTo("SENT_TO_PROVIDER");
        assertThat(replay.payment.id).isEqualTo("PAY-3001");
    }

    @ParameterizedTest
    @CsvSource({"2499.99,PENDING_APPROVAL", "2500,SENT_TO_PROVIDER", "2500.01,SENT_TO_PROVIDER",
            "0,PENDING_APPROVAL"})
    void configuredThresholdControlsCreation(String threshold, String status) {
        service = new RefundService(provider, factory.getValidator(), new ApprovalPolicy(threshold, "custom-v2"));
        RefundResult result = service.create("shweta", request(UUID.randomUUID().toString(), "ORD-1046"));
        assertThat(result.refund.status).isEqualTo(status);
        assertThat(result.refund.approvalThreshold).isEqualByComparingTo(threshold);
        assertThat(result.refund.policyVersion).isEqualTo("custom-v2");
        assertThat(service.dashboard().application.approvalThreshold).isEqualByComparingTo(threshold);
        assertThat(service.dashboard().application.policyVersion).isEqualTo("custom-v2");
        verify(provider, times("PENDING_APPROVAL".equals(status) ? 0 : 1)).send(anyString(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void frozenPolicyAndNotesSurviveDecisionsSnapshotsAndReplaysWithoutReclassification(String action) {
        service = new RefundService(provider, factory.getValidator(),
                new ApprovalPolicy("2499.99", "creation-v2"));
        RefundRequest originalRequest = request(UUID.randomUUID().toString(), "ORD-1046");
        RefundRequest automaticRequest = request(UUID.randomUUID().toString(), "ORD-1047");
        RefundResult pending = service.create("shweta", originalRequest);
        RefundResult automatic = service.create("shweta", automaticRequest);
        Dashboard before = service.dashboard();

        // Simulate a future policy replacement without adding a production hot-reload/reset path.
        ReflectionTestUtils.setField(service, "approvalPolicy", new ApprovalPolicy("100000", "later-v3"));
        assertThat(service.dashboard().refunds).hasSize(2);
        assertThat(service.dashboard().payments).hasSize(1);
        assertThat(service.dashboard().approvalQueue).containsExactly(pending.refund);
        RefundResult stillPending = service.create("shweta", originalRequest);
        assertThat(stillPending.replayed).isTrue();
        assertThat(stillPending.refund).isSameAs(pending.refund);
        assertThat(stillPending.payment).isNull();

        RefundResult newRefund = service.create("shweta", request(UUID.randomUUID().toString(), "ORD-1044"));
        assertThat(newRefund.refund.status).isEqualTo("SENT_TO_PROVIDER");
        assertThat(newRefund.refund.policyVersion).isEqualTo("later-v3");
        assertThat(newRefund.refund.approvalThreshold).isEqualByComparingTo("100000");
        assertThat(before.application.policyVersion).isEqualTo("creation-v2");
        assertThat(before.application.approvalThreshold).isEqualByComparingTo("2499.99");

        ApprovalDecisionRequest decision = decision("Independent " + action + " notes");
        RefundResult decided = "approve".equals(action)
                ? service.approve("dahnesh", pending.refund.id, decision)
                : service.reject("dahnesh", pending.refund.id, decision);
        assertThat(decided.refund.status).isEqualTo("approve".equals(action) ? "SENT_TO_PROVIDER" : "REJECTED");
        assertThat(decided.refund.policyVersion).isEqualTo("creation-v2");
        assertThat(decided.refund.approvalThreshold).isEqualByComparingTo("2499.99");
        assertThat(decided.refund.requestedAt).isEqualTo(pending.refund.requestedAt);
        assertThat(decided.refund.notes).isEqualTo(originalRequest.notes);
        assertThat(decided.refund.reason).isEqualTo(pending.refund.reason);
        assertThat(decided.refund.requesterUsername).isEqualTo("shweta");
        assertThat(decided.refund.decidedByUsername).isEqualTo("dahnesh");
        assertThat(decided.refund.decidedAt).isNotNull();
        assertThat(decided.refund.decisionNotes).isEqualTo("Independent " + action + " notes");
        assertThat(before.approvalQueue).containsExactly(pending.refund);
        assertThat(pending.refund.status).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.refund.decidedAt).isNull();
        assertThat(pending.refund.decidedByUsername).isNull();
        assertThat(pending.refund.decisionNotes).isEmpty();
        assertThat(service.dashboard().approvalQueue).isEmpty();
        assertThat(service.dashboard().refunds).contains(decided.refund);

        ReflectionTestUtils.setField(service, "approvalPolicy", new ApprovalPolicy("0", "later-v4"));
        RefundResult automaticReplay = service.create("shweta", automaticRequest);
        assertThat(automaticReplay.replayed).isTrue();
        assertThat(automaticReplay.refund).isSameAs(automatic.refund);
        assertThat(automaticReplay.refund.policyVersion).isEqualTo("creation-v2");
        assertThat(automaticReplay.refund.approvalThreshold).isEqualByComparingTo("2499.99");
        assertThat(automaticReplay.payment).isSameAs(automatic.payment);
        RefundResult creationReplay = service.create("shweta", originalRequest);
        assertThat(creationReplay.replayed).isTrue();
        assertThat(creationReplay.refund).isSameAs(decided.refund);
        assertThat(creationReplay.payment).isSameAs(decided.payment);
        RefundResult decisionReplay = "approve".equals(action)
                ? service.approve("dahnesh", pending.refund.id, decision("Must not replace notes"))
                : service.reject("dahnesh", pending.refund.id, decision("Must not replace notes"));
        assertThat(decisionReplay.replayed).isTrue();
        assertThat(decisionReplay.refund).isSameAs(decided.refund);
        assertThat(decisionReplay.payment).isSameAs(decided.payment);
        if ("reject".equals(action)) {
            assertThat(decided.payment).isNull();
        }
        verify(provider, times("approve".equals(action) ? 3 : 2)).send(anyString(), any(), any());
    }

    @Test
    void explicitResetRetainsConfiguredPolicy() {
        service = new RefundService(provider, factory.getValidator(), new ApprovalPolicy("25000.50", "custom-v2"));
        service.create("shweta", request(UUID.randomUUID().toString(), "ORD-1044"));
        Dashboard reset = service.reset();
        assertThat(reset.refunds).isEmpty();
        assertThat(reset.payments).isEmpty();
        assertThat(reset.orders).hasSize(12);
        assertThat(reset.application.approvalThreshold).isEqualByComparingTo("25000.50");
        assertThat(reset.application.policyVersion).isEqualTo("custom-v2");
        assertThat(reset.events.get(0).detail).contains("INR 25000.50", "custom-v2").doesNotContain("10,000");
    }

    @Test
    void requesterCannotDecideOwnHighValueRefund() {
        RefundResult pending = service.create("dahnesh", request(UUID.randomUUID().toString(), "ORD-1044"));
        assertThatThrownBy(() -> service.approve("dahnesh", pending.refund.id, decision("")))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("SELF_APPROVAL_FORBIDDEN");
        assertThatThrownBy(() -> service.reject("shweta", pending.refund.id, decision("")))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo("APPROVER_REQUIRED");
        assertThat(service.dashboard().payments).isEmpty();
    }

    @Test
    void simultaneousApprovalRetriesSendExactlyOnePayment() throws Exception {
        RefundResult pending = service.create("shweta", request(UUID.randomUUID().toString(), "ORD-1044"));
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RefundResult>> futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.approve("dahnesh", pending.refund.id, decision("Approved"));
                }));
            }
            start.countDown();
            for (Future<RefundResult> future : futures) {
                assertThat(future.get(15, TimeUnit.SECONDS).payment.id).isEqualTo("PAY-3001");
            }
            assertThat(service.dashboard().payments).hasSize(1);
            verify(provider, times(1)).send(anyString(), any(), any());
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void missingCredentialsFailWithActionableMessagesAndPasswordsAreBcrypt() {
        SecurityConfig config = new SecurityConfig();
        assertThatThrownBy(() -> config.userDetailsService("", "test-only-shweta", config.passwordEncoder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Set the REFUNDS_DAHNESH_PASSWORD environment variable");
        assertThatThrownBy(() -> config.userDetailsService("test-only-dahnesh", " ", config.passwordEncoder()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Set the REFUNDS_SHWETA_PASSWORD environment variable");
        org.springframework.security.core.userdetails.UserDetailsService users = config.userDetailsService(
                "test-only-dahnesh", "test-only-shweta", config.passwordEncoder());
        assertThat(users.loadUserByUsername("dahnesh").getPassword()).startsWith("$2a$");
        assertThat(config.passwordEncoder().matches("test-only-shweta",
                users.loadUserByUsername("shweta").getPassword())).isTrue();
    }

    private RefundRequest request(String key, String orderId) {
        RefundRequest request = new RefundRequest();
        request.orderId = orderId;
        request.reason = RefundRequest.Reason.RETURNED_ITEM;
        request.notes = "Synthetic return";
        request.idempotencyKey = key;
        return request;
    }

    private ApprovalDecisionRequest decision(String notes) {
        ApprovalDecisionRequest request = new ApprovalDecisionRequest();
        request.notes = notes;
        return request;
    }

    private RefundModels.Order order(Dashboard dashboard, String orderId) {
        return dashboard.orders.stream().filter(order -> order.id.equals(orderId)).findFirst().orElseThrow();
    }
}
