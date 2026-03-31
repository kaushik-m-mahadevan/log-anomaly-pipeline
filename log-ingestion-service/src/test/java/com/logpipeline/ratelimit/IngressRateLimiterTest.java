package com.logpipeline.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for IngressRateLimiter (Item 12).
 *
 * These tests use a very low limit (e.g. 3 req/s) to exercise the rate-limiting
 * logic without requiring any timing manipulation.
 */
class IngressRateLimiterTest {

    @Test
    @DisplayName("Requests up to the limit are all allowed")
    void tryAcquire_withinLimit_allAllowed() {
        IngressRateLimiter limiter = limiterWith(5);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire())
                    .as("request %d of 5 should be allowed", i + 1)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Request exceeding the limit is denied")
    void tryAcquire_exceedingLimit_denied() {
        IngressRateLimiter limiter = limiterWith(3);

        limiter.tryAcquire(); // 1
        limiter.tryAcquire(); // 2
        limiter.tryAcquire(); // 3

        assertThat(limiter.tryAcquire()).isFalse(); // 4th — over limit
    }

    @Test
    @DisplayName("Exactly one request allowed when limit is 1")
    void tryAcquire_limitOfOne_allowsOneThenDenies() {
        IngressRateLimiter limiter = limiterWith(1);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("Very high limit never denies normal traffic")
    void tryAcquire_veryHighLimit_neverDenies() {
        IngressRateLimiter limiter = limiterWith(Integer.MAX_VALUE);

        for (int i = 0; i < 10_000; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
    }

    @Test
    @DisplayName("Window resets after 1 second, allowing requests again")
    void tryAcquire_afterWindowReset_allowsRequests() throws InterruptedException {
        IngressRateLimiter limiter = limiterWith(2);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse(); // limit hit

        Thread.sleep(1_100); // wait for window to reset

        // New window — requests should be allowed again
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private IngressRateLimiter limiterWith(int maxRps) {
        return new IngressRateLimiter(maxRps);
    }
}
