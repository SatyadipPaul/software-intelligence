package com.acme.checkout;

import org.springframework.kafka.annotation.KafkaListener;

public final class PaymentEventConsumer {
    @KafkaListener(topics = "payment-authorized")
    public void consume(String event) { }
}
