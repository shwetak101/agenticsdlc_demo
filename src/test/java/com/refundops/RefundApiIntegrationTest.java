package com.refundops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "REFUNDS_DAHNESH_PASSWORD=test-only-dahnesh-password",
        "REFUNDS_SHWETA_PASSWORD=test-only-shweta-password",
        "spring.main.banner-mode=off",
        "logging.level.root=WARN"
})
@AutoConfigureMockMvc
class RefundApiIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private RefundService service;

    @BeforeEach
    void reset() {
        service.reset();
    }

    @Test
    void anonymousSessionHasSpringCsrfAndNoIdentity() throws Exception {
        mvc.perform(get("/api/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").value(nullValue()))
                .andExpect(jsonPath("$.csrf.token").isNotEmpty())
                .andExpect(jsonPath("$.csrf.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.csrf.parameterName").value("_csrf"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/css/styles.css", "/js/app.js", "/styles.css", "/app.js"})
    void loginShellAndStaticPathsArePublic(String path) throws Exception {
        int status = mvc.perform(get(path)).andReturn().getResponse().getStatus();
        // Frontend assets are supplied independently; absent assets may return 404, never an auth redirect.
        assertThat(status).isIn(200, 404);
    }

    @ParameterizedTest
    @CsvSource({"dahnesh,Dahnesh,2", "shweta,Shweta,1"})
    void realLoginEstablishesExactIdentityAndRotatesCsrf(String username, String displayName, int roleCount)
            throws Exception {
        Session anonymous = session(null);
        String oldSessionId = anonymous.http.getId();
        mvc.perform(post("/login").session(anonymous.http)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", username).param("password", password(username))
                        .param(anonymous.parameterName, anonymous.token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        Session loggedIn = session(anonymous.http);
        assertThat(loggedIn.http.getId()).isNotEqualTo(oldSessionId);
        assertThat(loggedIn.token).isNotEqualTo(anonymous.token);
        mvc.perform(get("/api/session").session(loggedIn.http))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.user.username").value(username))
                .andExpect(jsonPath("$.user.displayName").value(displayName))
                .andExpect(jsonPath("$.user.roles", hasSize(roleCount)))
                .andExpect(jsonPath("$.user.roles[0]").value("REQUESTOR"));
        if ("dahnesh".equals(username)) {
            mvc.perform(get("/api/session").session(loggedIn.http))
                    .andExpect(jsonPath("$.user.roles[1]").value("APPROVER"));
        }
        mvc.perform(post("/api/demo/reset").session(loggedIn.http)
                        .header(anonymous.headerName, anonymous.token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void invalidCredentialsProduceJson401() throws Exception {
        Session anonymous = session(null);
        mvc.perform(post("/login").session(anonymous.http)
                        .param("username", "dahnesh").param("password", "wrong-test-password")
                        .param(anonymous.parameterName, anonymous.token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(get("/api/session").session(anonymous.http))
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void loginRequiresCsrf() throws Exception {
        mvc.perform(post("/login").param("username", "shweta").param("password", password("shweta")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void protectedReadsAndWritesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/refunds").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(payload("ORD-1042").toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/demo/reset").with(csrf()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").isNotEmpty());
        mvc.perform(post("/api/refunds").servletPath("/api/refunds")
                        .contentType(MediaType.APPLICATION_JSON).content(payload("ORD-1042").toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/api/refunds/RF-2001/approve").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void refundResetAndLogoutRequireCsrfAndLogoutClearsAuthentication() throws Exception {
        Session auth = login("shweta");
        mvc.perform(post("/api/refunds").session(auth.http)
                        .contentType(MediaType.APPLICATION_JSON).content(payload("ORD-1042").toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/api/demo/reset").session(auth.http))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/logout").session(auth.http))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(post("/logout").session(auth.http).header(auth.headerName, auth.token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertThat(auth.http.isInvalid()).isTrue();
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/session")).andExpect(jsonPath("$.authenticated").value(false));
        assertThat(service.dashboard().refunds).isEmpty();
    }

    @Test
    @WithMockUser(username = "dahnesh", roles = "APPROVER")
    void approverWithoutRequestorCannotSubmitOrReset() throws Exception {
        mvc.perform(post("/api/refunds").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(payload("ORD-1042").toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post("/api/demo/reset").with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        assertThat(service.dashboard().refunds).isEmpty();
    }

    @Test
    void dashboardHasExactMetadataAndDeterministicSyntheticBaseline() throws Exception {
        Session auth = login("dahnesh");
        JsonNode dashboard = json(mvc.perform(get("/api/dashboard").session(auth.http))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orders", hasSize(12)))
                .andExpect(jsonPath("$.refunds", hasSize(0)))
                .andExpect(jsonPath("$.payments", hasSize(0)))
                .andExpect(jsonPath("$.events", hasSize(1)))
                .andExpect(jsonPath("$.approvalQueue", hasSize(0)))
                .andReturn());
        assertThat(dashboard.size()).isEqualTo(6);
        assertThat(dashboard.get("application")).isEqualTo(mapper.readTree(
                "{\"name\":\"RefundOps\",\"version\":\"High-value approval candidate\",\"javaBaseline\":\"11\","
                        + "\"springBootVersion\":\"2.7.18\",\"mode\":\"THRESHOLD_APPROVAL\","
                        + "\"provider\":\"MockPay\",\"storage\":\"In-memory\",\"approvalThreshold\":10000}"));
        for (JsonNode order : dashboard.get("orders")) {
            assertThat(order.size()).isEqualTo(11);
            assertThat(order.get("status").asText()).isEqualTo("PAID");
            assertThat(order.get("currency").asText()).isEqualTo("INR");
            assertThat(order.get("amount").isNumber()).isTrue();
            assertThat(java.time.Instant.parse(order.get("purchasedAt").asText())).isNotNull();
            assertThat(order.get("customerEmail").asText()).endsWith("@example.com");
        }
        JsonNode baseline = dashboard.get("events").get(0);
        assertThat(baseline.size()).isEqualTo(7);
        assertThat(baseline.get("refundId").isNull()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"shweta,ORD-1045,10000", "shweta,ORD-1046,2500", "shweta,ORD-1047,1499",
            "dahnesh,ORD-1045,10000", "dahnesh,ORD-1047,1499"})
    void refundsAtOrBelowThresholdAreImmediatelySent(String username, String orderId, int amount)
            throws Exception {
        Session auth = login(username);
        ObjectNode request = payload(orderId);
        request.put("amount", -500).put("requesterUsername", "attacker").put("requesterName", "Forged")
                .put("actorDisplayName", "Forged").put("status", "APPROVED");
        JsonNode result = json(mvc.perform(refund(auth, request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.refund.id").value("RF-2001"))
                .andExpect(jsonPath("$.refund.orderId").value(orderId))
                .andExpect(jsonPath("$.refund.amount").value(amount))
                .andExpect(jsonPath("$.refund.status").value("SENT_TO_PROVIDER"))
                .andExpect(jsonPath("$.refund.requesterUsername").value(username))
                .andExpect(jsonPath("$.refund.requesterName").value(DemoUsers.get(username).displayName))
                .andExpect(jsonPath("$.payment.id").value("PAY-3001"))
                .andExpect(jsonPath("$.payment.amount").value(amount))
                .andExpect(jsonPath("$.payment.status").value("SENT_TO_PROVIDER"))
                .andExpect(jsonPath("$.payment.provider").value("MockPay"))
                .andExpect(jsonPath("$.payment.requesterName").value(DemoUsers.get(username).displayName))
                .andReturn());
        assertThat(result.get("refund").size()).isEqualTo(16);
        assertThat(result.get("payment").size()).isEqualTo(9);
        assertThat(result.at("/refund/paymentId")).isEqualTo(result.at("/payment/id"));
        assertThat(result.at("/payment/refundId")).isEqualTo(result.at("/refund/id"));
        assertThat(service.dashboard().events.get(0).actorDisplayName)
                .isEqualTo(DemoUsers.get(username).displayName);
        assertThat(service.dashboard().orders.stream().filter(order -> order.id.equals(orderId))
                .findFirst().orElseThrow().status).isEqualTo("REFUND_SENT");
        assertThat(service.dashboard().payments).hasSize(1);
    }

    @Test
    void shwetaHighValueRefundRequiresDahneshApproval() throws Exception {
        Session shweta = login("shweta");
        Session dahnesh = login("dahnesh");
        JsonNode pending = json(mvc.perform(refund(shweta, payload("ORD-1044")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refund.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.refund.requesterUsername").value("shweta"))
                .andExpect(jsonPath("$.payment").value(nullValue()))
                .andReturn());
        String refundId = pending.at("/refund/id").asText();
        mvc.perform(get("/api/dashboard").session(dahnesh.http))
                .andExpect(jsonPath("$.approvalQueue", hasSize(1)))
                .andExpect(jsonPath("$.payments", hasSize(0)));
        mvc.perform(decision(shweta, refundId, "approve"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(decision(dahnesh, refundId, "approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.status").value("SENT_TO_PROVIDER"))
                .andExpect(jsonPath("$.refund.decidedByUsername").value("dahnesh"))
                .andExpect(jsonPath("$.payment.id").value("PAY-3001"));
        mvc.perform(decision(dahnesh, refundId, "approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.payment.id").value("PAY-3001"));
        assertThat(service.dashboard().payments).hasSize(1);
    }

    @Test
    void dahneshCannotApproveOwnHighValueRefundAndRejectionSendsNoPayment() throws Exception {
        Session dahnesh = login("dahnesh");
        JsonNode own = json(mvc.perform(refund(dahnesh, payload("ORD-1044")))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(decision(dahnesh, own.at("/refund/id").asText(), "approve"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
        assertThat(service.dashboard().payments).isEmpty();

        service.reset();
        Session shweta = login("shweta");
        JsonNode pending = json(mvc.perform(refund(shweta, payload("ORD-1044")))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(decision(dahnesh, pending.at("/refund/id").asText(), "reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.status").value("REJECTED"))
                .andExpect(jsonPath("$.refund.decidedByUsername").value("dahnesh"))
                .andExpect(jsonPath("$.payment").value(nullValue()));
        assertThat(service.dashboard().payments).isEmpty();
    }

    @Test
    void replayReturnsOriginalAndConflictingKeysOrOrdersNeverCreateAnotherPayment() throws Exception {
        Session dahnesh = login("dahnesh");
        Session shweta = login("shweta");
        ObjectNode request = payload("ORD-1046");
        JsonNode original = json(mvc.perform(refund(dahnesh, request))
                .andExpect(status().isCreated()).andReturn());
        JsonNode replay = json(mvc.perform(refund(dahnesh, request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true)).andReturn());
        assertThat(replay.get("refund")).isEqualTo(original.get("refund"));
        assertThat(replay.get("payment")).isEqualTo(original.get("payment"));
        mvc.perform(refund(shweta, request)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        for (String field : new String[]{"notes", "orderId", "reason"}) {
            ObjectNode changed = request.deepCopy();
            changed.put(field, field.equals("orderId") ? "ORD-1043" : field.equals("reason") ? "OTHER" : "changed");
            mvc.perform(refund(dahnesh, changed)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        }
        mvc.perform(refund(dahnesh, payload("ORD-1046"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_REFUNDED"));
        assertThat(service.dashboard().refunds).hasSize(1);
        assertThat(service.dashboard().payments).hasSize(1);
        assertThat(service.dashboard().events).hasSize(2);
    }

    @Test
    void resetIsSharedDeterministicAndClearsIdempotency() throws Exception {
        Session dahnesh = login("dahnesh");
        Session shweta = login("shweta");
        JsonNode original = json(mvc.perform(get("/api/dashboard").session(shweta.http)).andReturn());
        ObjectNode request = payload("ORD-1046");
        mvc.perform(refund(dahnesh, request)).andExpect(status().isCreated());
        mvc.perform(get("/api/dashboard").session(shweta.http))
                .andExpect(jsonPath("$.refunds", hasSize(1)));
        JsonNode restored = json(mvc.perform(post("/api/demo/reset").session(shweta.http)
                .header(shweta.headerName, shweta.token)).andExpect(status().isOk()).andReturn());
        assertThat(restored).isEqualTo(original);
        mvc.perform(get("/api/dashboard").session(dahnesh.http))
                .andExpect(jsonPath("$.refunds", hasSize(0)));
        mvc.perform(refund(shweta, request)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.refund.id").value("RF-2001"))
                .andExpect(jsonPath("$.payment.id").value("PAY-3001"));
        mvc.perform(post("/api/demo/reset").session(dahnesh.http).header(dahnesh.headerName, dahnesh.token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.refunds", hasSize(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingReason", "unknownReason", "longNotes", "malformedKey", "longKey",
            "missingKey", "blankOrder", "missingOrder", "emptyBody"})
    void invalidInputIsRejectedWithoutSideEffects(String variant) throws Exception {
        Session auth = login("shweta");
        ObjectNode request = payload("ORD-1042");
        switch (variant) {
            case "missingReason": request.remove("reason"); break;
            case "unknownReason": request.put("reason", "HIGH_VALUE"); break;
            case "longNotes": request.put("notes", "x".repeat(501)); break;
            case "malformedKey": request.put("idempotencyKey", "1-1-1-1-1"); break;
            case "longKey": request.put("idempotencyKey", UUID.randomUUID() + "0"); break;
            case "missingKey": request.remove("idempotencyKey"); break;
            case "blankOrder": request.put("orderId", " "); break;
            case "missingOrder": request.remove("orderId"); break;
            case "emptyBody": request.removeAll(); break;
            default: throw new AssertionError(variant);
        }
        mvc.perform(refund(auth, request)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").isNotEmpty()).andExpect(jsonPath("$.message").isNotEmpty());
        assertThat(service.dashboard().refunds).isEmpty();
        assertThat(service.dashboard().payments).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETURNED_ITEM", "DAMAGED_ITEM", "DUPLICATE_CHARGE", "CUSTOMER_REQUEST", "OTHER"})
    void supportedReasonsAndMaximumNotesAreAccepted(String reason) throws Exception {
        Session auth = login("shweta");
        ObjectNode request = payload("ORD-1047").put("reason", reason).put("notes", "n".repeat(500));
        mvc.perform(refund(auth, request)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.refund.reason").value(reason))
                .andExpect(jsonPath("$.refund.notes").value("n".repeat(500)));
    }

    @Test
    void omittedNotesAreOptionalAndUnknownOrderIsStructured404() throws Exception {
        Session auth = login("shweta");
        mvc.perform(refund(auth, payload("ORD-UNKNOWN"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        ObjectNode request = payload("ORD-1047");
        request.remove("notes");
        mvc.perform(refund(auth, request)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.refund.notes").value(""));
    }

    @Test
    void malformedJsonAndWrongContentTypeAreStructuredErrors() throws Exception {
        Session auth = login("shweta");
        mvc.perform(post("/api/refunds").session(auth.http).header(auth.headerName, auth.token)
                        .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JSON"));
        mvc.perform(post("/api/refunds").session(auth.http).header(auth.headerName, auth.token)
                        .contentType(MediaType.TEXT_PLAIN).content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void noPermissiveCorsHeaders() throws Exception {
        MvcResult result = mvc.perform(get("/api/session").header("Origin", "https://untrusted.example"))
                .andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void servletErrorDispatchReturnsOnlySafeStructuredErrors() throws Exception {
        JsonNode error = json(mvc.perform(get("/error")
                        .requestAttr(javax.servlet.RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(javax.servlet.RequestDispatcher.ERROR_MESSAGE, "sensitive internal detail"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn());
        assertThat(error.size()).isEqualTo(2);
        assertThat(error.get("message").asText()).doesNotContain("sensitive");
        mvc.perform(get("/error").requestAttr(javax.servlet.RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private Session login(String username) throws Exception {
        Session anonymous = session(null);
        mvc.perform(post("/login").session(anonymous.http).param("username", username)
                        .param("password", password(username)).param(anonymous.parameterName, anonymous.token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        return session(anonymous.http);
    }

    private Session session(MockHttpSession existing) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/session");
        if (existing != null) {
            request.session(existing);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        JsonNode csrf = json(result).get("csrf");
        return new Session((MockHttpSession) result.getRequest().getSession(false),
                csrf.get("token").asText(), csrf.get("headerName").asText(), csrf.get("parameterName").asText());
    }

    private ObjectNode payload(String orderId) {
        return mapper.createObjectNode().put("orderId", orderId).put("reason", "CUSTOMER_REQUEST")
                .put("notes", "Synthetic test refund").put("idempotencyKey", UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder refund(Session auth, ObjectNode request) {
        return post("/api/refunds").session(auth.http).header(auth.headerName, auth.token)
                .contentType(MediaType.APPLICATION_JSON).content(request.toString());
    }

    private MockHttpServletRequestBuilder decision(Session auth, String refundId, String action) {
        return post("/api/refunds/{refundId}/{action}", refundId, action)
                .session(auth.http).header(auth.headerName, auth.token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"Independent review\"}");
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private String password(String username) {
        return "test-only-" + username + "-password";
    }

    private static final class Session {
        final MockHttpSession http;
        final String token;
        final String headerName;
        final String parameterName;

        Session(MockHttpSession http, String token, String headerName, String parameterName) {
            this.http = http;
            this.token = token;
            this.headerName = headerName;
            this.parameterName = parameterName;
        }
    }
}
