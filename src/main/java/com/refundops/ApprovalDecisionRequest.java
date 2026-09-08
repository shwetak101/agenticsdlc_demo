package com.refundops;

import javax.validation.constraints.Size;

public class ApprovalDecisionRequest {
    @Size(max = 500)
    public String notes;
}
