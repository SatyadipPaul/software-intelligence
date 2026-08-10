package com.acme.billing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exists so the fixture exercises a security guard, a configuration property, and a second package.
 * Determinism bugs hide in whichever layer the smoke test never reaches.
 */
@RestController
@RequestMapping("/billing")
public class BillingAdminController {

    @Value("${billing.endpoint:http://localhost:9000}")
    private String endpoint;

    private final BillingLedger ledger = new BillingLedger();

    @PreAuthorize("hasRole('BILLING_ADMIN')")
    @GetMapping("/ledger")
    public String ledger() {
        return ledger.summary(endpoint);
    }
}
