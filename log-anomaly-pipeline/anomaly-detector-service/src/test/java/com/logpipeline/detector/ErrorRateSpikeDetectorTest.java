package com.logpipeline.detector;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.AnomalySeverity;
import com.logpipeline.model.LogEvent;
import com.logpipeline.model.LogLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for ErrorRateSpikeDetector.
 * No Spring context — pure JVM, fast, and framework-free by design.
 */
class ErrorRateSpikeDetectorTest {

    private static final int WINDOW_SIZE = 5;
    private static final double THRESHOLD = 0.4; // 40% error rate triggers anomaly

    private ErrorRateSpikeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ErrorRateSpikeDetector(WINDOW_SIZE, THRESHOLD);
    }

    @Test
    @DisplayName("Should return empty when window is not yet full")
    void shouldReturnEmptyWhenWindowNotFull() {
        Optional<AnomalyEvent> result = detector.evaluate(errorEvent());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return empty when error rate is below threshold")
    void shouldReturnEmptyWhenBelowThreshold() {
        // 1 error in 5 events = 20% — below 40% threshold
        detector.evaluate(errorEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());

        Optional<AnomalyEvent> result = detector.evaluate(infoEvent());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should detect anomaly when error rate meets threshold")
    void shouldDetectAnomalyAtThreshold() {
        // 2 errors in 5 events = 40% — exactly at threshold
        detector.evaluate(errorEvent());
        detector.evaluate(errorEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());

        Optional<AnomalyEvent> result = detector.evaluate(infoEvent());
        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("Should classify CRITICAL severity at 90%+ error rate")
    void shouldClassifyCriticalSeverity() {
        for (int i = 0; i < 4; i++) detector.evaluate(errorEvent());
        Optional<AnomalyEvent> result = detector.evaluate(errorEvent()); // 5/5 = 100%

        assertThat(result).isPresent();
        assertThat(result.get().severity()).isEqualTo(AnomalySeverity.CRITICAL);
    }

    @Test
    @DisplayName("Should slide window — oldest events evicted as new ones arrive")
    void shouldSlideWindowCorrectly() {
        // Fill window with errors to trigger anomaly
        for (int i = 0; i < WINDOW_SIZE; i++) detector.evaluate(errorEvent());

        // Now flood with INFO events — error rate should drop below threshold
        for (int i = 0; i < WINDOW_SIZE; i++) {
            Optional<AnomalyEvent> result = detector.evaluate(infoEvent());
            if (i == WINDOW_SIZE - 1) {
                // After sliding out all errors, no more anomaly
                assertThat(result).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("Should treat FATAL as an error-level event")
    void shouldTreatFatalAsError() {
        detector.evaluate(fatalEvent());
        detector.evaluate(fatalEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());

        Optional<AnomalyEvent> result = detector.evaluate(infoEvent());
        assertThat(result).isPresent(); // 2/5 = 40%
    }

    @Test
    @DisplayName("Should reject invalid constructor arguments")
    void shouldRejectInvalidArguments() {
        assertThatThrownBy(() -> new ErrorRateSpikeDetector(0, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorRateSpikeDetector(5, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ErrorRateSpikeDetector(5, 1.1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Test data builders ───────────────────────────────────────────────────

    private LogEvent errorEvent() {
        return event(LogLevel.ERROR);
    }

    private LogEvent infoEvent() {
        return event(LogLevel.INFO);
    }

    private LogEvent fatalEvent() {
        return event(LogLevel.FATAL);
    }

    private LogEvent event(LogLevel level) {
        return new LogEvent(UUID.randomUUID().toString(), "test-service",
                level, "test message", Instant.now(), "test message");
    }
}
