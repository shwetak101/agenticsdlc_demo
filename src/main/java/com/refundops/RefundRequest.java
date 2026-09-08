package com.refundops;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

public class RefundRequest {
    public enum Reason {
        RETURNED_ITEM, DAMAGED_ITEM, DUPLICATE_CHARGE, CUSTOMER_REQUEST, OTHER
    }

    @NotBlank
    @Size(max = 64)
    public String orderId;

    @NotNull
    public Reason reason;

    @Size(max = 500)
    public String notes;

    @NotBlank
    @Size(max = 36)
    @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
            message = "must be a UUID in 8-4-4-4-12 format")
    public String idempotencyKey;
}
