package com.acme.checkout;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PaymentController {
    private final PaymentService payments = new PaymentService();

    @PostMapping("/payments/authorize")
    public String authorize() {
        return payments.authorize();
    }
}
