package com.acme.audit;

import org.springframework.stereotype.Service;

@Service
public class AuditTrail {
    public String record(String event) {
        return "audited:" + event;
    }
}
