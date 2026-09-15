package com.refundops;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApprovalPolicyConfiguration {
    @Bean
    public ApprovalPolicy approvalPolicy(
            @Value("${refunds.approval.threshold:" + ApprovalPolicy.DEFAULT_THRESHOLD + "}") String threshold,
            @Value("${refunds.approval.version:" + ApprovalPolicy.DEFAULT_VERSION + "}") String version) {
        return new ApprovalPolicy(threshold, version);
    }
}
