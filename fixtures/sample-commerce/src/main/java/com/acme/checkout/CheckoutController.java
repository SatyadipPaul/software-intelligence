package com.acme.checkout;

import java.math.BigDecimal;

public final class CheckoutController {
    private final CheckoutService checkoutService = new CheckoutService();

    public String checkout(String customerId, BigDecimal total) {
        return checkoutService.execute(customerId, total);
    }
}
