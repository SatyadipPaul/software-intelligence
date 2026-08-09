package com.acme.checkout;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class PaymentService {
    @Transactional
    public String authorize() {
        return "authorized";
    }
}
