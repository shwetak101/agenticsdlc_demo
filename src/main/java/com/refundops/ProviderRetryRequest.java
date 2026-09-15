package com.refundops;

import javax.validation.constraints.AssertTrue;

public class ProviderRetryRequest {
    @AssertTrue(message = "must explicitly confirm a provider retry")
    public boolean confirmed;
}
