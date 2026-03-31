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

    // ── Cooldown / deduplication ──────────────────────────────────────────────

    @Test
    @DisplayName("Subsequent events above threshold do not re-fire while anomaly is active")
    void shouldSuppressAlerts_whileAnomalyIsActive() {
        // Trigger anomaly: 2 errors → window full at 40%
        detector.evaluate(errorEvent());
        detector.evaluate(errorEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());
        Optional<AnomalyEvent> firstFire = detector.evaluate(infoEvent()); // 2/5 = 40% → fires
        assertThat(firstFire).isPresent();

        // Same error rate sustained — should be suppressed, not re-fired
        Optional<AnomalyEvent> suppressed1 = detector.evaluate(errorEvent()); // still above threshold
        Optional<AnomalyEvent> suppressed2 = detector.evaluate(errorEvent());
        assertThat(suppressed1).isEmpty();
        assertThat(suppressed2).isEmpty();
    }

    @Test
    @DisplayName("Detector re-arms after error rate recovers below threshold")
    void shouldRearm_afterRateRecovers() {
        // Fire anomaly
        detector.evaluate(errorEvent());
        detector.evaluate(errorEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent()); // 2/5 = 40% → fires

        // Flood with INFO to push rate below threshold
        detector.evaluate(infoEvent()); // 1/5 = 20% → re-arms
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent()); // 0/5 = 0% — fully recovered

        // New spike — should fire again after recovery
        detector.evaluate(errorEvent());
        detector.evaluate(errorEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());
        Optional<AnomalyEvent> refire = detector.evaluate(infoEvent()); // 2/5 = 40% → fires again
        assertThat(refire).isPresent();
    }

    @Test
    @DisplayName("Exactly one alert fires per outage — fire, suppress, recover, fire again")
    void exactlyOneAlertPerOutage_withRecoveryInBetween() {
        // Outage 1
        for (int i = 0; i < 4; i++) detector.evaluate(errorEvent());
        Optional<AnomalyEvent> outage1 = detector.evaluate(errorEvent()); // 5/5 = 100%
        assertThat(outage1).isPresent();

        // Sustained — more errors should all be suppressed
        for (int i = 0; i < 3; i++) {
            assertThat(detector.evaluate(errorEvent())).isEmpty();
        }

        // Recovery
        for (int i = 0; i < WINDOW_SIZE; i++) detector.evaluate(infoEvent());

        // Outage 2
        for (int i = 0; i < 4; i++) detector.evaluate(errorEvent());
        Optional<AnomalyEvent> outage2 = detector.evaluate(errorEvent()); // 5/5 = 100%
        assertThat(outage2).isPresent();

        // Both anomaly IDs should be distinct (separate events)
        assertThat(outage1.get().anomalyId()).isNotEqualTo(outage2.get().anomalyId());
    }

    @Test
    @DisplayName("Alert is suppressed when rate stays well above threshold during an active anomaly")
    void shouldSuppressAlerts_whileRateRemainsHighAboveThreshold() {
        // Fill window with all errors to trigger CRITICAL anomaly
        for (int i = 0; i < WINDOW_SIZE; i++) detector.evaluate(errorEvent());
        // The 5th event fires — anomalyActive = true

        // Keep feeding errors — rate stays at 100%, but every subsequent call is suppressed
        int suppressedCount = 0;
        for (int i = 0; i < 10; i++) {
            if (detector.evaluate(errorEvent()).isEmpty()) suppressedCount++;
        }

        assertThat(suppressedCount).isEqualTo(10);
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

    // ── Severity classification ───────────────────────────────────────────────

    @Test
    @DisplayName("Should classify HIGH severity at 70-89% error rate")
    void shouldClassifyHighSeverity() {
        // Use window=10, threshold=0.5 to exercise HIGH band: 8/10 = 80%
        ErrorRateSpikeDetector d = new ErrorRateSpikeDetector(10, 0.5);
        for (int i = 0; i < 8; i++) d.evaluate(errorEvent());
        for (int i = 0; i < 2; i++) d.evaluate(infoEvent());

        // Re-evaluate with an error to force window evaluation at 80%
        ErrorRateSpikeDetector d2 = new ErrorRateSpikeDetector(10, 0.5);
        for (int i = 0; i < 7; i++) d2.evaluate(errorEvent());
        for (int i = 0; i < 2; i++) d2.evaluate(infoEvent());
        Optional<AnomalyEvent> result = d2.evaluate(errorEvent()); // 8/10 = 80%

        assertThat(result).isPresent();
        assertThat(result.get().severity()).isEqualTo(AnomalySeverity.HIGH);
    }

    @Test
    @DisplayName("Should classify MEDIUM severity at 50-69% error rate")
    void shouldClassifyMediumSeverity() {
        // 6/10 = 60% → MEDIUM
        ErrorRateSpikeDetector d = new ErrorRateSpikeDetector(10, 0.4);
        for (int i = 0; i < 5; i++) d.evaluate(errorEvent());
        for (int i = 0; i < 4; i++) d.evaluate(infoEvent());
        Optional<AnomalyEvent> result = d.evaluate(errorEvent()); // 6/10 = 60%

        assertThat(result).isPresent();
        assertThat(result.get().severity()).isEqualTo(AnomalySeverity.MEDIUM);
    }

    @Test
    @DisplayName("Should classify LOW severity at threshold to 49% error rate")
    void shouldClassifyLowSeverity() {
        // threshold=0.3, 4/10 = 40% → LOW (< 50%)
        ErrorRateSpikeDetector d = new ErrorRateSpikeDetector(10, 0.3);
        for (int i = 0; i < 3; i++) d.evaluate(errorEvent());
        for (int i = 0; i < 6; i++) d.evaluate(infoEvent());
        Optional<AnomalyEvent> result = d.evaluate(errorEvent()); // 4/10 = 40%

        assertThat(result).isPresent();
        assertThat(result.get().severity()).isEqualTo(AnomalySeverity.LOW);
    }

    // ── Threshold boundary ────────────────────────────────────────────────────

    @Test
    @DisplayName("Should NOT trigger when error rate is just below threshold")
    void shouldNotTriggerJustBelowThreshold() {
        // 1 error in 5 events = 20% < 40% threshold
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());
        Optional<AnomalyEvent> result = detector.evaluate(errorEvent()); // 1/5 = 20%

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Anomaly description includes rate, window size, and threshold")
    void anomalyDescription_containsRateAndWindowInfo() {
        for (int i = 0; i < 4; i++) detector.evaluate(errorEvent());
        Optional<AnomalyEvent> result = detector.evaluate(errorEvent()); // 5/5 = 100%

        assertThat(result).isPresent();
        String desc = result.get().description();
        assertThat(desc).contains("100.0%");
        assertThat(desc).contains("5"); // window size
    }

    @Test
    @DisplayName("Anomaly event carries correct detectedValue and threshold")
    void anomalyEvent_hasCorrectDetectedValueAndThreshold() {
        // 2 errors in 5 = 40% at threshold
        detector.evaluate(errorEvent());
        detector.evaluate(errorEvent());
        detector.evaluate(infoEvent());
        detector.evaluate(infoEvent());
        Optional<AnomalyEvent> result = detector.evaluate(infoEvent());

        assertThat(result).isPresent();
        assertThat(result.get().detectedValue()).isEqualTo(0.4);
        assertThat(result.get().threshold()).isEqualTo(THRESHOLD);
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Window size 1 — single error triggers immediately")
    void windowSizeOne_singleError_triggersImmediately() {
        ErrorRateSpikeDetector d = new ErrorRateSpikeDetector(1, 1.0);
        Optional<AnomalyEvent> result = d.evaluate(errorEvent());

        assertThat(result).isPresent();
        assertThat(result.get().severity()).isEqualTo(AnomalySeverity.CRITICAL);
    }

    @Test
    @DisplayName("Window size 1 — single INFO never triggers")
    void windowSizeOne_singleInfo_neverTriggers() {
        ErrorRateSpikeDetector d = new ErrorRateSpikeDetector(1, 1.0);
        Optional<AnomalyEvent> result = d.evaluate(infoEvent());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("DEBUG and WARN are not counted as errors")
    void debugAndWarnEvents_notCountedAsErrors() {
        for (int i = 0; i < 3; i++) detector.evaluate(debugEvent());
        for (int i = 0; i < 1; i++) detector.evaluate(warnEvent());
        Optional<AnomalyEvent> result = detector.evaluate(infoEvent()); // 0/5 = 0%

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("name() returns detector identifier")
    void name_returnsExpectedValue() {
        assertThat(detector.name()).isEqualTo("error-rate-spike");
    }

    @Test
    @DisplayName("Anomaly event has non-null UUID anomaly ID")
    void anomalyEvent_hasNonNullId() {
        for (int i = 0; i < 4; i++) detector.evaluate(errorEvent());
        Optional<AnomalyEvent> result = detector.evaluate(errorEvent());

        assertThat(result).isPresent();
        assertThat(result.get().anomalyId()).isNotBlank();
    }

    @Test
    @DisplayName("Anomaly event carries the current schema version")
    void anomalyEvent_hasCurrentSchemaVersion() {
        for (int i = 0; i < 4; i++) detector.evaluate(errorEvent());
        Optional<AnomalyEvent> result = detector.evaluate(errorEvent());

        assertThat(result).isPresent();
        assertThat(result.get().schemaVersion()).isEqualTo(AnomalyEvent.CURRENT_VERSION);
    }

    @Test
    @DisplayName("Anomaly event carries the serviceId of the triggering event")
    void anomalyEvent_hasCorrectServiceId() {
        ErrorRateSpikeDetector d = new ErrorRateSpikeDetector(WINDOW_SIZE, THRESHOLD);
        LogEvent event = new LogEvent(
                "evt-1", "payment-service", LogLevel.ERROR, "msg", Instant.now(), "msg");

        for (int i = 0; i < 4; i++) d.evaluate(event);
        Optional<AnomalyEvent> result = d.evaluate(event);

        assertThat(result).isPresent();
        assertThat(result.get().serviceId()).isEqualTo("payment-service");
    }

    @Test
    @DisplayName("Negative window size rejected")
    void negativeWindowSize_isRejected() {
        assertThatThrownBy(() -> new ErrorRateSpikeDetector(-1, 0.5))
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

    private LogEvent debugEvent() {
        return event(LogLevel.DEBUG);
    }

    private LogEvent warnEvent() {
        return event(LogLevel.WARN);
    }

    private LogEvent event(LogLevel level) {
        return new LogEvent(UUID.randomUUID().toString(), "test-service",
                level, "test message", Instant.now(), "test message");
    }
}
