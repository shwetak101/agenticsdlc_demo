package com.refundops;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalPolicyConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(application -> {
                application.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                application.getEnvironment().getPropertySources()
                        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            })
            .withUserConfiguration(ApprovalPolicyConfiguration.class);

    @Test
    void absentSettingsUseTheSameDefaultsAsStandaloneServices() {
        context.run(application -> {
            assertThat(application).hasNotFailed();
            ApprovalPolicy policy = application.getBean(ApprovalPolicy.class);
            assertThat(policy.approvalThreshold).isEqualByComparingTo("10000");
            assertThat(policy.policyVersion).isEqualTo("refund-policy-v1");
            assertThat(policy.approvalThreshold).isEqualTo(ApprovalPolicy.defaults().approvalThreshold);
            assertThat(policy.policyVersion).isEqualTo(ApprovalPolicy.defaults().policyVersion);
        });
    }

    @Test
    void environmentVariablesOverrideDefaults() {
        context.withInitializer(application -> application.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-environment", Map.of(
                        "REFUNDS_APPROVAL_THRESHOLD", "25000.50",
                        "REFUNDS_APPROVAL_VERSION", "refund-policy-v2"))))
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    ApprovalPolicy policy = application.getBean(ApprovalPolicy.class);
                    assertThat(policy.approvalThreshold).isEqualByComparingTo("25000.50");
                    assertThat(policy.policyVersion).isEqualTo("refund-policy-v2");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "2500.5", "25000.50"})
    void validMonetaryPropertiesStartSuccessfully(String threshold) {
        context.withPropertyValues("refunds.approval.threshold=" + threshold,
                        "refunds.approval.version=refund-policy-custom")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    ApprovalPolicy policy = application.getBean(ApprovalPolicy.class);
                    assertThat(policy.approvalThreshold).isEqualByComparingTo(threshold);
                    assertThat(policy.policyVersion).isEqualTo("refund-policy-custom");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-1", "-0.01", "not-money", "NaN", "Infinity", "1,000",
            "0.001", "10000.001", "2500.000"})
    void explicitlyInvalidThresholdFailsStartupInsteadOfUsingDefault(String threshold) {
        context.withPropertyValues("refunds.approval.threshold=" + threshold)
                .run(application -> {
                    assertThat(application).hasFailed();
                    assertThat(application.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("refunds.approval.threshold")
                            .hasStackTraceContaining("non-negative INR amount")
                            .hasStackTraceContaining("at most two decimal places");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\u2003"})
    void explicitlyBlankVersionFailsStartup(String version) {
        context.withPropertyValues("refunds.approval.version=" + version)
                .run(application -> {
                    assertThat(application).hasFailed();
                    assertThat(application.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("refunds.approval.version")
                            .hasStackTraceContaining("must be non-blank");
                });
    }

    @Test
    void blankEnvironmentOverridesAlsoFailStartup() {
        for (String variable : new String[]{"REFUNDS_APPROVAL_THRESHOLD", "REFUNDS_APPROVAL_VERSION"}) {
            context.withInitializer(application -> application.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource("test-environment", Map.of(variable, ""))))
                    .run(application -> {
                        assertThat(application).hasFailed();
                        assertThat(application.getStartupFailure()).hasStackTraceContaining(variable);
                    });
        }
    }

    @ParameterizedTest
    @CsvSource({"10000,9999.99,false", "10000,10000,false", "10000,10000.01,true",
            "2500.50,2500.49,false", "2500.50,2500.50,false", "2500.50,2500.51,true",
            "0,0,false", "0,0.01,true"})
    void onlyAmountsStrictlyAboveTheExactDecimalThresholdRequireApproval(
            String threshold, String amount, boolean requiresApproval) {
        ApprovalPolicy policy = new ApprovalPolicy(threshold, "refund-policy-boundary");
        assertThat(policy.requiresApproval(new BigDecimal(amount))).isEqualTo(requiresApproval);
    }

    @Test
    void standalonePoliciesCannotBypassValidation() {
        assertThatThrownBy(() -> new ApprovalPolicy(null, "version"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refunds.approval.threshold");
        assertThatThrownBy(() -> new ApprovalPolicy("10000", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("refunds.approval.version");
    }
}
