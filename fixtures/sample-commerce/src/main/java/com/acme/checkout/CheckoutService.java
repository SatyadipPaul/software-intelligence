package com.acme.checkout;

import java.math.BigDecimal;

public final class CheckoutService {
    private final PaymentGateway payments = new PaymentGateway();
    private final OrderRepository orders = new OrderRepository();

    public String execute(String customerId, BigDecimal total) {
        payments.authorize(customerId, total);
        return orders.save(customerId, total);
    }
}
