package com.kiran.seatreservation.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business-level counters, exposed at /actuator/prometheus as
 * reservation_operations_total{operation="reserve",outcome="success"}.
 *
 * Latency and status-code metrics already come from http_server_requests,
 * and pool metrics from hikaricp_*.
 */
@Component
public class ReservationMetrics {

    private final MeterRegistry registry;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void success(String operation) {
        record(operation, "success");
    }

    public void failure(String operation, Exception ex) {
        record(operation, ex.getClass().getSimpleName());
    }

    private void record(String operation, String outcome) {
        registry.counter(
                "reservation.operations",
                "operation", operation,
                "outcome", outcome
        ).increment();
    }
}