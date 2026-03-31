package com.logpipeline.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window rate limiter for the log ingestion REST endpoint (Item 12).
 *
 * A fixed-window counter is deliberately chosen here over a sliding-window or
 * token-bucket for two reasons:
 *   1. Zero external dependencies — no Guava, no Resilience4j.
 *   2. The ingestion endpoint is the outermost boundary; bursts at window edges
 *      are acceptable because Kafka absorbs them and the downstream anomaly
 *      detector operates on sliding windows of its own.
 *
 * Thread safety: AtomicInteger ensures correct behaviour under concurrent requests
 * without locks. The window reset races slightly on the boundary, which is fine
 * for a soft rate limit.
 */
@Component
public class IngressRateLimiter {

    private final int maxRequestsPerSecond;
    private final AtomicInteger counter = new AtomicInteger(0);
    private volatile long windowStartMs = System.currentTimeMillis();

    public IngressRateLimiter(
            @Value("${rate-limit.requests-per-second:1000}") int maxRequestsPerSecond) {
        this.maxRequestsPerSecond = maxRequestsPerSecond;
    }

    /**
     * Returns {@code true} if the request should be allowed through,
     * {@code false} if the rate limit has been exceeded.
     */
    public boolean tryAcquire() {
        long now = System.currentTimeMillis();

        // Reset window every second
        if (now - windowStartMs >= 1_000) {
            windowStartMs = now;
            counter.set(0);
        }

        return counter.incrementAndGet() <= maxRequestsPerSecond;
    }
}
