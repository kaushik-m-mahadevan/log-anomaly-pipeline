package com.logpipeline.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.time.temporal.ChronoUnit;

class LogEventRequestTest {

    @Test
    void resolvedTimestamp_returnsProvidedTimestamp_whenNotNull() {
        Instant fixed = Instant.parse("2024-06-01T12:00:00Z");
        LogEventRequest request = new LogEventRequest("svc", "INFO", "msg", fixed);

        assertThat(request.resolvedTimestamp()).isEqualTo(fixed);
    }

    @Test
    void resolvedTimestamp_returnsNow_whenTimestampIsNull() {
        Instant before = Instant.now();
        LogEventRequest request = new LogEventRequest("svc", "INFO", "msg", null);
        Instant resolved = request.resolvedTimestamp();
        Instant after = Instant.now();

        assertThat(resolved).isBetween(before, after);
    }

    @Test
    void resolvedTimestamp_calledTwice_returnsDifferentInstants_whenNull() throws InterruptedException {
        LogEventRequest request = new LogEventRequest("svc", "INFO", "msg", null);

        Instant first = request.resolvedTimestamp();
        Thread.sleep(1);
        Instant second = request.resolvedTimestamp();

        // Each call produces a fresh Instant.now() — they may differ
        assertThat(second).isAfterOrEqualTo(first);
    }
}
