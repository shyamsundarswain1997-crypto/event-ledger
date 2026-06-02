package com.eventledger.gateway.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

/**
 * Registers and exposes custom business metrics for the Event Gateway.
 *
 * <p>All meters are registered eagerly at construction time so dashboards
 * show them from startup, not only after the first event arrives.</p>
 */
@Service
public class GatewayMetricsService {

    private final Counter eventsProcessedCredit;
    private final Counter eventsProcessedDebit;
    private final Counter duplicateEvents;
    private final Counter circuitBreakerOpenEvents;

    public GatewayMetricsService(MeterRegistry registry) {
        this.eventsProcessedCredit = Counter.builder("gateway.events.processed")
                .tag("type", "CREDIT")
                .description("Successfully processed CREDIT events forwarded to Account Service")
                .register(registry);

        this.eventsProcessedDebit = Counter.builder("gateway.events.processed")
                .tag("type", "DEBIT")
                .description("Successfully processed DEBIT events forwarded to Account Service")
                .register(registry);

        this.duplicateEvents = Counter.builder("gateway.events.duplicates")
                .description("Duplicate event submissions (idempotency hits)")
                .register(registry);

        this.circuitBreakerOpenEvents = Counter.builder("gateway.circuit_breaker.open")
                .description("Number of times a request was rejected due to open circuit breaker")
                .register(registry);
    }

    public void incrementEventsProcessed(String type) {
        if ("CREDIT".equals(type)) {
            eventsProcessedCredit.increment();
        } else {
            eventsProcessedDebit.increment();
        }
    }

    public void incrementDuplicateEvents() {
        duplicateEvents.increment();
    }

    public void incrementCircuitBreakerOpen() {
        circuitBreakerOpenEvents.increment();
    }
}
