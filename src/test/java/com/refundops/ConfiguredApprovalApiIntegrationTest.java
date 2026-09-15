package com.refundops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "REFUNDS_DAHNESH_PASSWORD=test-only-dahnesh-password",
        "REFUNDS_SHWETA_PASSWORD=test-only-shweta-password",
        "refunds.approval.threshold=25000.50",
        "refunds.approval.version=refund-policy-v2",
        "spring.main.banner-mode=off",
        "logging.level.root=WARN"
})
@AutoConfigureMockMvc
@WithMockUser(username = "shweta", roles = "REQUESTOR")
class ConfiguredApprovalApiIntegrationTest {
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
    void dashboardAndResetExposeTheActualStartupPolicy() throws Exception {
        mvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.approvalThreshold").value(25000.50))
                .andExpect(jsonPath("$.application.policyVersion").value("refund-policy-v2"));
        assertThat(service.dashboard().events.get(0).detail).contains("INR 25000.50", "refund-policy-v2");
        mvc.perform(post("/api/demo/reset").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.approvalThreshold").value(25000.50))
                .andExpect(jsonPath("$.application.policyVersion").value("refund-policy-v2"))
                .andExpect(jsonPath("$.refunds", hasSize(0)));
    }

    @Test
    void configuredThresholdAutomaticallySendsFormerlyHighValueRefundAndReplaysItsMetadata() throws Exception {
        ObjectNode request = payload("ORD-1044").put("approvalThreshold", 0).put("policyVersion", "forged");
        JsonNode original = json(mvc.perform(refund(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refund.status").value("SENT_TO_PROVIDER"))
                .andExpect(jsonPath("$.refund.approvalThreshold").value(25000.50))
                .andExpect(jsonPath("$.refund.policyVersion").value("refund-policy-v2"))
                .andExpect(jsonPath("$.refund.decidedByUsername").value(nullValue()))
                .andExpect(jsonPath("$.payment.amount").value(25000))
                .andReturn());
        JsonNode replay = json(mvc.perform(refund(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true)).andReturn());
        assertThat(replay.get("refund")).isEqualTo(original.get("refund"));
        assertThat(replay.get("payment")).isEqualTo(original.get("payment"));
        assertThat(service.dashboard().payments).hasSize(1);
        assertThat(service.dashboard().approvalQueue).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void configuredPolicyAndNotesSurviveDecisionsAndBothKindsOfReplay(String action) throws Exception {
        ObjectNode request = payload("ORD-1043")
                .put("approvalThreshold", 100000).put("policyVersion", "forged");
        JsonNode pending = json(mvc.perform(refund(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refund.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.refund.approvalThreshold").value(25000.50))
                .andExpect(jsonPath("$.refund.policyVersion").value("refund-policy-v2"))
                .andExpect(jsonPath("$.payment").value(nullValue())).andReturn());
        String id = pending.at("/refund/id").asText();
        assertThat(service.dashboard().payments).isEmpty();
        mvc.perform(get("/api/dashboard"))
                .andExpect(jsonPath("$.approvalQueue[0].approvalThreshold").value(25000.50))
                .andExpect(jsonPath("$.approvalQueue[0].policyVersion").value("refund-policy-v2"));

        mvc.perform(post("/api/refunds/{id}/{action}", id, action).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"notes\":\"Not an approver\"}"))
                .andExpect(status().isForbidden());
        JsonNode decided = json(mvc.perform(decision(id, action, "Independent decision"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.status").value("approve".equals(action) ? "SENT_TO_PROVIDER" : "REJECTED"))
                .andExpect(jsonPath("$.refund.approvalThreshold").value(25000.50))
                .andExpect(jsonPath("$.refund.policyVersion").value("refund-policy-v2"))
                .andExpect(jsonPath("$.refund.notes").value("Original request notes"))
                .andExpect(jsonPath("$.refund.decisionNotes").value("Independent decision"))
                .andExpect(jsonPath("$.refund.decidedByUsername").value("dahnesh"))
                .andReturn());
        JsonNode decisionReplay = json(mvc.perform(decision(id, action, "Must not replace notes"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true)).andReturn());
        JsonNode creationReplay = json(mvc.perform(refund(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true)).andReturn());
        assertThat(decisionReplay.get("refund")).isEqualTo(decided.get("refund"));
        assertThat(creationReplay.get("refund")).isEqualTo(decided.get("refund"));
        assertThat(decisionReplay.get("payment")).isEqualTo(decided.get("payment"));
        assertThat(creationReplay.get("payment")).isEqualTo(decided.get("payment"));
        mvc.perform(get("/api/dashboard"))
                .andExpect(jsonPath("$.approvalQueue", hasSize(0)))
                .andExpect(jsonPath("$.refunds[0].approvalThreshold").value(25000.50))
                .andExpect(jsonPath("$.refunds[0].policyVersion").value("refund-policy-v2"))
                .andExpect(jsonPath("$.refunds[0].decisionNotes").value("Independent decision"))
                .andExpect(jsonPath("$.payments", hasSize("approve".equals(action) ? 1 : 0)));
        if ("reject".equals(action)) {
            assertThat(decided.get("payment").isNull()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "reject"})
    void configuredPolicyStillForbidsSelfDecision(String action) throws Exception {
        JsonNode pending = json(mvc.perform(refund(payload("ORD-1042"))
                        .with(user("dahnesh").roles("REQUESTOR", "APPROVER")))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(decision(pending.at("/refund/id").asText(), action, "Self decision"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL_FORBIDDEN"));
        assertThat(service.dashboard().payments).isEmpty();
        assertThat(service.dashboard().approvalQueue).hasSize(1);
    }

    private ObjectNode payload(String orderId) {
        return mapper.createObjectNode().put("orderId", orderId).put("reason", "CUSTOMER_REQUEST")
                .put("notes", "Original request notes").put("idempotencyKey", UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder refund(ObjectNode request) {
        return post("/api/refunds").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request.toString());
    }

    private MockHttpServletRequestBuilder decision(String id, String action, String notes) {
        return post("/api/refunds/{id}/{action}", id, action).with(csrf())
                .with(user("dahnesh").roles("REQUESTOR", "APPROVER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("notes", notes).toString());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }
}
