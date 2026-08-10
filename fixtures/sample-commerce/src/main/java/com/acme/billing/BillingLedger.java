package com.acme.billing;

import com.acme.audit.AuditTrail;
import com.acme.checkout.PaymentGateway;
import org.springframework.stereotype.Service;

/**
 * Depends on two other packages on purpose: module coupling with a single target cannot expose an
 * ordering bug, and ordering bugs are exactly what the determinism gate is for.
 */
@Service
public class BillingLedger {
    private final AuditTrail audit = new AuditTrail();
    private final PaymentGateway gateway = new PaymentGateway();

    public String summary(String endpoint) {
        return audit.record(gateway.getClass().getSimpleName()) + " via " + endpoint;
    }
}
