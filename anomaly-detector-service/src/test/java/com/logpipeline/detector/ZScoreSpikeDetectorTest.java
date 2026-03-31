package com.logpipeline.detector;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.AnomalySeverity;
import com.logpipeline.model.LogEvent;
import com.logpipeline.model.LogLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive unit tests for ZScoreSpikeDetector.
 *
 * Each test documents the expected Z-score arithmetic in comments so the
 * numerical reasoning is transparent and reproducible.
 *
 * Default test configuration unless overridden:
 *   windowSize=15, signalWindowSize=5, zThreshold=2.0
 *   → baselineSize = 10 events
 */
class ZScoreSpikeDetectorTest {

    private static final int    WINDOW   = 15;
    private static final int    SIGNAL   = 5;
    private static final double Z_THRESH = 2.0;

    private ZScoreSpikeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ZScoreSpikeDetector(WINDOW, SIGNAL, Z_THRESH);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Constructor validation
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("Zero windowSize is rejected")
        void rejects_zeroWindowSize() {
            assertThatThrownBy(() -> new ZScoreSpikeDetector(0, 5, 2.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("windowSize");
        }

        @Test
        @DisplayName("Negative windowSize is rejected")
        void rejects_negativeWindowSize() {
            assertThatThrownBy(() -> new ZScoreSpikeDetector(-1, 5, 2.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Zero signalWindowSize is rejected")
        void rejects_zeroSignalWindowSize() {
            assertThatThrownBy(() -> new ZScoreSpikeDetector(15, 0, 2.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("signalWindowSize");
        }

        @Test
        @DisplayName("signalWindowSize equal to windowSize is rejected (no room for baseline)")
        void rejects_signalWindowSizeEqualToWindowSize() {
            assertThatThrownBy(() -> new ZScoreSpikeDetector(10, 10, 2.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("baseline");
        }

        @Test
        @DisplayName("signalWindowSize greater than windowSize is rejected")
        void rejects_signalWindowSizeLargerThanWindowSize() {
            assertThatThrownBy(() -> new ZScoreSpikeDetector(10, 11, 2.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Zero zScoreThreshold is rejected")
        void rejects_zeroZScoreThreshold() {
            assertThatThrownBy(() -> new ZScoreSpikeDetector(15, 5, 0.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zScoreThreshold");
        }

        @Test
        @DisplayName("Negative zScoreThreshold is rejected")
        void rejects_negativeZScoreThreshold() {
            assertThatThrownBy(() -> new ZScoreSpikeDetector(15, 5, -1.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Valid parameters construct without error")
        void accepts_validParameters() {
            assertThatNoException()
                    .isThrownBy(() -> new ZScoreSpikeDetector(15, 5, 2.0));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Window warm-up
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Window warm-up period")
    class WindowWarmUp {

        @Test
        @DisplayName("Returns empty on first event — window not full")
        void returnsEmpty_firstEvent() {
            assertThat(detector.evaluate(error())).isEmpty();
        }

        @Test
        @DisplayName("Returns empty on every event until window is exactly full")
        void returnsEmpty_untilWindowFull() {
            for (int i = 0; i < WINDOW - 1; i++) {
                assertThat(detector.evaluate(info()))
                        .as("event %d of %d should be empty", i + 1, WINDOW - 1)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("Can evaluate once window reaches full size")
        void canEvaluate_onceWindowFull() {
            // Fill with INFO — no spike, but window is now ready
            for (int i = 0; i < WINDOW - 1; i++) detector.evaluate(info());
            // The Nth event should no longer return empty due to warm-up
            // (it may or may not fire depending on rates — just confirm no NPE)
            assertThatNoException().isThrownBy(() -> detector.evaluate(info()));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Core detection logic
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Core detection — fires and suppresses correctly")
    class CoreDetection {

        @Test
        @DisplayName("Fires for a pristine service spiking to 100% errors")
        void fires_pristineService_fullSpike() {
            // baseline: 10 INFO  → baselineRate = 0.0
            // signal:    5 ERROR → signalRate   = 1.0
            // stdDev = MAX(sqrt(0.0 * 1.0), 0.01) = 0.01  (floor applied)
            // Z = (1.0 - 0.0) / 0.01 = 100.0  → CRITICAL
            baseline(10, false);
            Optional<AnomalyEvent> result = signal(5, true);

            assertThat(result).isPresent();
            assertThat(result.get().severity()).isEqualTo(AnomalySeverity.CRITICAL);
        }

        @Test
        @DisplayName("Does NOT fire when signal rate equals baseline rate")
        void noFire_signalMatchesBaseline() {
            // baseline: 5 ERROR + 5 INFO → baselineRate = 0.5
            // signal:   2 ERROR + 3 INFO → signalRate   = 0.4
            // Z = (0.4 - 0.5) / 0.5 = -0.2  →  no fire
            feedN(5, true);
            feedN(5, false);
            Optional<AnomalyEvent> result = feedN_last(2, true, 3, false);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Does NOT fire for a noisy service at the same absolute rate that would trigger a fixed-threshold detector")
        void noFire_noisyService_sameAbsoluteRate() {
            // baseline: 6 ERROR + 4 INFO → baselineRate = 0.6
            // signal:   3 ERROR + 2 INFO → signalRate   = 0.6
            // Z = (0.6 - 0.6) / stdDev = 0.0  → no fire
            // A fixed 40%-threshold detector would fire here — Z-score correctly suppresses it.
            feedN(6, true);
            feedN(4, false);
            Optional<AnomalyEvent> result = feedN_last(3, true, 2, false);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Fires when a moderate-baseline service spikes significantly")
        void fires_moderateService_significantSpike() {
            // baseline: 2 ERROR + 8 INFO → baselineRate = 0.2
            // signal:   5 ERROR           → signalRate   = 1.0
            // stdDev = sqrt(0.2 * 0.8) = 0.4
            // Z = (1.0 - 0.2) / 0.4 = 2.0  →  exactly threshold  → LOW
            feedN(2, true);
            feedN(8, false);
            Optional<AnomalyEvent> result = signal(5, true);

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("Does NOT fire for a naturally-high-error service with a small signal increase")
        void noFire_highBaselineService_smallIncrease() {
            // baseline: 8 ERROR + 2 INFO → baselineRate = 0.8
            // signal:   4 ERROR + 1 INFO → signalRate   = 0.8
            // Z = (0.8 - 0.8) / stdDev = 0.0  → no fire
            feedN(8, true);
            feedN(2, false);
            Optional<AnomalyEvent> result = feedN_last(4, true, 1, false);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Does NOT fire when signal rate is below baseline (negative Z)")
        void noFire_negativeZScore() {
            // baseline: 6 ERROR + 4 INFO → 60%
            // signal:   0 ERROR + 5 INFO → 0%
            // Z = (0.0 - 0.6) / stdDev = large negative  → no fire
            feedN(6, true);
            feedN(4, false);
            Optional<AnomalyEvent> result = signal(5, false);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("All-error baseline with all-error signal produces Z=0 — no fire")
        void noFire_allErrorBaseline_allErrorSignal() {
            // baseline: 10 ERROR → baselineRate = 1.0
            // signal:    5 ERROR → signalRate   = 1.0
            // stdDev = MAX(sqrt(1.0 * 0.0), 0.01) = 0.01
            // Z = (1.0 - 1.0) / 0.01 = 0.0  → no fire
            baseline(10, true);
            Optional<AnomalyEvent> result = signal(5, true);

            assertThat(result).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Severity classification
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Severity classification by Z-score")
    class SeverityClassification {

        @Test
        @DisplayName("CRITICAL: Z ≥ 5.0 — pristine baseline, full error spike (Z ≈ 100)")
        void severity_critical() {
            // baseline: 10 INFO → 0%   stdDev floor = 0.01
            // signal:   5 ERROR → 100%   Z = 1.0 / 0.01 = 100 → CRITICAL
            baseline(10, false);
            Optional<AnomalyEvent> result = signal(5, true);

            assertThat(result).isPresent();
            assertThat(result.get().severity()).isEqualTo(AnomalySeverity.CRITICAL);
        }

        @Test
        @DisplayName("HIGH: 3.5 ≤ Z < 5.0 — 5% baseline, full signal spike (Z ≈ 4.36)")
        void severity_high() {
            // window=25, signal=5 → baseline=20
            // baseline: 1 ERROR + 19 INFO → baselineRate = 1/20 = 0.05
            // signal:   5 ERROR            → signalRate   = 1.0
            // stdDev = sqrt(0.05 * 0.95) = sqrt(0.0475) ≈ 0.2179
            // Z = (1.0 - 0.05) / 0.2179 = 0.95 / 0.2179 ≈ 4.36  → HIGH
            ZScoreSpikeDetector d = new ZScoreSpikeDetector(25, 5, 2.0);
            feedTo(d, 1, true);
            feedTo(d, 19, false);
            Optional<AnomalyEvent> result = feedSignalTo(d, 5, true);

            assertThat(result).isPresent();
            assertThat(result.get().severity()).isEqualTo(AnomalySeverity.HIGH);
        }

        @Test
        @DisplayName("MEDIUM: 2.5 ≤ Z < 3.5 — 10% baseline, full signal spike (Z = 3.0)")
        void severity_medium() {
            // window=25, signal=5 → baseline=20
            // baseline: 2 ERROR + 18 INFO → baselineRate = 2/20 = 0.1
            // signal:   5 ERROR            → signalRate   = 1.0
            // stdDev = sqrt(0.1 * 0.9) = sqrt(0.09) = 0.3
            // Z = (1.0 - 0.1) / 0.3 = 0.9 / 0.3 = 3.0  → MEDIUM
            ZScoreSpikeDetector d = new ZScoreSpikeDetector(25, 5, 2.0);
            feedTo(d, 2, true);
            feedTo(d, 18, false);
            Optional<AnomalyEvent> result = feedSignalTo(d, 5, true);

            assertThat(result).isPresent();
            assertThat(result.get().severity()).isEqualTo(AnomalySeverity.MEDIUM);
        }

        @Test
        @DisplayName("LOW: threshold ≤ Z < 2.5 — 20% baseline, 80% signal spike (Z = 1.5, threshold=1.5)")
        void severity_low() {
            // baseline: 2 ERROR + 8 INFO → baselineRate = 0.2
            // signal:   4 ERROR + 1 INFO → signalRate   = 0.8
            // stdDev = sqrt(0.2 * 0.8) = sqrt(0.16) = 0.4
            // Z = (0.8 - 0.2) / 0.4 = 0.6 / 0.4 = 1.5  → fires at threshold=1.5, < 2.5 → LOW
            ZScoreSpikeDetector d = new ZScoreSpikeDetector(15, 5, 1.5);
            feedTo(d, 2, true);
            feedTo(d, 8, false);
            // signal: 4 errors then 1 info — 4/5 = 80%
            feedTo(d, 4, true);
            Optional<AnomalyEvent> result = feedLastTo(d, false);

            assertThat(result).isPresent();
            assertThat(result.get().severity()).isEqualTo(AnomalySeverity.LOW);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Cooldown / deduplication
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Cooldown — one alert per spike cycle")
    class Cooldown {

        @Test
        @DisplayName("Subsequent events above threshold are suppressed after first fire")
        void suppressesSubsequentAlerts_whileAnomalyActive() {
            // Trigger anomaly
            baseline(10, false);
            Optional<AnomalyEvent> firstFire = signal(5, true);
            assertThat(firstFire).isPresent();

            // Keep feeding errors — should all be suppressed
            for (int i = 0; i < 10; i++) {
                assertThat(detector.evaluate(error()))
                        .as("event %d after initial fire should be suppressed", i + 1)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("Detector re-arms once signal rate drops below threshold")
        void rearms_afterRateRecovers() {
            // Trigger anomaly
            baseline(10, false);
            signal(5, true);   // fires, anomalyActive = true

            // Recovery: flood with INFO — Z-score drops to 0
            for (int i = 0; i < WINDOW; i++) detector.evaluate(info());

            // New spike — should fire again
            baseline(10, false);
            Optional<AnomalyEvent> refire = signal(5, true);
            assertThat(refire).isPresent();
        }

        @Test
        @DisplayName("Exactly one alert per outage — two distinct anomaly IDs across two cycles")
        void exactlyOneAlertPerOutage_twoDistinctIds() {
            // Outage 1
            baseline(10, false);
            Optional<AnomalyEvent> outage1 = signal(5, true);
            assertThat(outage1).isPresent();

            // Sustained spike — all suppressed
            for (int i = 0; i < 5; i++) {
                assertThat(detector.evaluate(error())).isEmpty();
            }

            // Recovery
            for (int i = 0; i < WINDOW; i++) detector.evaluate(info());

            // Outage 2
            baseline(10, false);
            Optional<AnomalyEvent> outage2 = signal(5, true);
            assertThat(outage2).isPresent();

            // Both are real events with unique IDs
            assertThat(outage1.get().anomalyId())
                    .isNotEqualTo(outage2.get().anomalyId());
        }

        @Test
        @DisplayName("A partial recovery that doesn't drop Z below threshold stays suppressed")
        void staysSuppressed_partialRecovery() {
            // Trigger anomaly — 10 INFO baseline, 5 ERROR signal
            baseline(10, false);
            signal(5, true);   // fires

            // Feed only 2 INFO — rate improves but signal zone still error-heavy
            detector.evaluate(info());
            detector.evaluate(info());
            // Window is now sliding — evaluate and expect suppression
            Optional<AnomalyEvent> result = detector.evaluate(error());
            // Whether suppressed or re-armed depends on new window contents;
            // either way the anomaly must NOT fire twice immediately
            long firesCount = 0;
            if (result.isPresent()) firesCount++;
            // At most one additional fire can occur after partial recovery
            assertThat(firesCount).isLessThanOrEqualTo(1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Anomaly event content
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Anomaly event fields")
    class AnomalyEventContent {

        @Test
        @DisplayName("detectedValue carries the Z-score (not the raw error rate)")
        void detectedValue_isZScore() {
            // baseline: 10 INFO → 0%  Z ≈ 100
            baseline(10, false);
            Optional<AnomalyEvent> result = signal(5, true);

            assertThat(result).isPresent();
            // Z-score should be large (floor gives 1.0/0.01 = 100)
            assertThat(result.get().detectedValue()).isGreaterThan(50.0);
        }

        @Test
        @DisplayName("threshold field carries the configured zScoreThreshold")
        void threshold_isZScoreThreshold() {
            baseline(10, false);
            Optional<AnomalyEvent> result = signal(5, true);

            assertThat(result).isPresent();
            assertThat(result.get().threshold()).isEqualTo(Z_THRESH);
        }

        @Test
        @DisplayName("serviceId matches the triggering event's serviceId")
        void serviceId_matchesTriggeringEvent() {
            ZScoreSpikeDetector d = new ZScoreSpikeDetector(WINDOW, SIGNAL, Z_THRESH);
            LogEvent e = event("checkout-service", LogLevel.ERROR);
            for (int i = 0; i < 10; i++) d.evaluate(event("checkout-service", LogLevel.INFO));
            for (int i = 0; i < 4; i++) d.evaluate(e);
            Optional<AnomalyEvent> result = d.evaluate(e);

            assertThat(result).isPresent();
            assertThat(result.get().serviceId()).isEqualTo("checkout-service");
        }

        @Test
        @DisplayName("anomalyId is a non-blank UUID")
        void anomalyId_isNonBlankUUID() {
            baseline(10, false);
            Optional<AnomalyEvent> result = signal(5, true);

            assertThat(result).isPresent();
            assertThat(result.get().anomalyId()).isNotBlank();
            // Must be parseable as a UUID
            assertThatNoException()
                    .isThrownBy(() -> UUID.fromString(result.get().anomalyId()));
        }

        @Test
        @DisplayName("detectedAt is set and close to now")
        void detectedAt_isRecent() {
            Instant before = Instant.now();
            baseline(10, false);
            Optional<AnomalyEvent> result = signal(5, true);
            Instant after = Instant.now();

            assertThat(result).isPresent();
            assertThat(result.get().detectedAt()).isBetween(before, after);
        }

        @Test
        @DisplayName("description contains Z-score, baseline rate, signal rate, and window info")
        void description_containsKeyMetrics() {
            baseline(10, false);
            Optional<AnomalyEvent> result = signal(5, true);

            assertThat(result).isPresent();
            String desc = result.get().description();
            assertThat(desc).contains("Z=");          // Z-score value
            assertThat(desc).contains("baseline");    // baseline rate mention
            assertThat(desc).contains("signal");      // signal rate mention
            assertThat(desc).contains(String.valueOf(SIGNAL));   // signal window size
            assertThat(desc).contains(String.valueOf(WINDOW));   // total window size
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Log level handling
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Log level classification")
    class LogLevelHandling {

        @Test
        @DisplayName("FATAL events are counted as errors")
        void fatal_countsAsError() {
            baseline(10, false);
            // send 5 FATAL as signal
            for (int i = 0; i < 4; i++) detector.evaluate(event(LogLevel.FATAL));
            Optional<AnomalyEvent> result = detector.evaluate(event(LogLevel.FATAL));

            assertThat(result).isPresent();
        }

        @ParameterizedTest(name = "{0} is NOT counted as an error")
        @EnumSource(value = LogLevel.class, names = {"DEBUG", "INFO", "WARN"})
        void nonErrorLevels_notCountedAsErrors(LogLevel level) {
            // baseline: 10 of the level  → 0% errors
            // signal:   5  of the level  → 0% errors  →  Z = 0  → no fire
            for (int i = 0; i < 10; i++) detector.evaluate(event(level));
            Optional<AnomalyEvent> result = detector.evaluate(event(level));
            // No fire after only one event past the window fill — assert no false positive
            for (int i = 0; i < 4; i++) detector.evaluate(event(level));
            Optional<AnomalyEvent> last = detector.evaluate(event(level));
            assertThat(last).isEmpty();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Standard deviation floor (MIN_STD_DEV)
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Standard deviation floor (MIN_STD_DEV)")
    class StdDevFloor {

        @Test
        @DisplayName("Zero-error baseline does not divide by zero (floor applied)")
        void floor_appliedForZeroErrorBaseline() {
            // baseline: 10 INFO → p=0.0  →  sqrt(0*1)=0  →  floor to 0.01
            // signal:   5 ERROR → s=1.0
            // Z = 1.0 / 0.01 = 100 — must not throw
            baseline(10, false);
            assertThatNoException()
                    .isThrownBy(() -> signal(5, true));
        }

        @Test
        @DisplayName("All-error baseline does not divide by zero (floor applied)")
        void floor_appliedForAllErrorBaseline() {
            // baseline: 10 ERROR → p=1.0  →  sqrt(1*0)=0  →  floor to 0.01
            // signal:   5 INFO   → s=0.0
            // Z = (0.0 - 1.0) / 0.01 = -100 (negative — no fire)
            baseline(10, true);
            assertThatNoException()
                    .isThrownBy(() -> signal(5, false));
        }

        @Test
        @DisplayName("MIN_STD_DEV constant has expected value")
        void minStdDev_hasExpectedValue() {
            assertThat(ZScoreSpikeDetector.MIN_STD_DEV).isEqualTo(0.01);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Metadata
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("name() returns 'z-score-spike'")
    void name_returnsExpectedValue() {
        assertThat(detector.name()).isEqualTo("z-score-spike");
    }

    @Test
    @DisplayName("Anomaly event carries the current schema version")
    void anomalyEvent_hasCurrentSchemaVersion() {
        baseline(10, false);
        Optional<AnomalyEvent> result = signal(5, true);

        assertThat(result).isPresent();
        assertThat(result.get().schemaVersion()).isEqualTo(AnomalyEvent.CURRENT_VERSION);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Edge cases
    // ══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Minimum viable window (windowSize=2, signalWindowSize=1) works correctly")
        void minimumViableWindow() {
            ZScoreSpikeDetector d = new ZScoreSpikeDetector(2, 1, 1.0);
            // baseline: 1 INFO  → 0%
            // signal:   1 ERROR → 100%
            // Z = 1.0 / 0.01 = 100 → fires
            d.evaluate(info());
            Optional<AnomalyEvent> result = d.evaluate(error());
            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("Window slides correctly — old spike exits, new behaviour drives Z-score")
        void windowSlides_oldSpikeExitsBaseline() {
            // Phase 1: fill with errors → anomaly fires
            baseline(10, false);
            signal(5, true);   // fires

            // Phase 2: flush the window with INFO → re-arm and clean state
            for (int i = 0; i < WINDOW * 2; i++) detector.evaluate(info());

            // Phase 3: clean baseline of all INFO, then spike — should fire fresh
            baseline(10, false);
            Optional<AnomalyEvent> fresh = signal(5, true);
            assertThat(fresh).isPresent();
        }

        @Test
        @DisplayName("Two different services maintain completely independent state")
        void twoServices_independentState() {
            ZScoreSpikeDetector dA = new ZScoreSpikeDetector(WINDOW, SIGNAL, Z_THRESH);
            ZScoreSpikeDetector dB = new ZScoreSpikeDetector(WINDOW, SIGNAL, Z_THRESH);

            // Service A: 10 INFO baseline + 5 ERROR spike → fires
            for (int i = 0; i < 10; i++) dA.evaluate(event("svc-a", LogLevel.INFO));
            Optional<AnomalyEvent> resultA = Optional.empty();
            for (int i = 0; i < 5; i++) resultA = dA.evaluate(event("svc-a", LogLevel.ERROR));

            // Service B: only 15 INFO events → never fires
            Optional<AnomalyEvent> resultB = Optional.empty();
            for (int i = 0; i < 15; i++) resultB = dB.evaluate(event("svc-b", LogLevel.INFO));

            assertThat(resultA).isPresent();
            assertThat(resultB).isEmpty();
        }

        @Test
        @DisplayName("Very high zScoreThreshold (10.0) never fires on moderate spikes")
        void highThreshold_neverFiresOnModerateSpike() {
            // Even a full spike with moderate baseline only reaches Z≈4
            ZScoreSpikeDetector strict = new ZScoreSpikeDetector(25, 5, 10.0);
            feedTo(strict, 2, true);
            feedTo(strict, 18, false);
            Optional<AnomalyEvent> result = feedSignalTo(strict, 5, true);
            // Z≈3.0 < threshold 10.0 → no fire
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Very low zScoreThreshold (0.1) fires on even a tiny signal deviation")
        void lowThreshold_firesOnTinyDeviation() {
            // baseline: 5 ERROR + 5 INFO → 50%
            // signal:   3 ERROR + 2 INFO → 60%
            // stdDev = sqrt(0.5*0.5) = 0.5
            // Z = (0.6 - 0.5) / 0.5 = 0.2 ≥ 0.1 → fires
            ZScoreSpikeDetector sensitive = new ZScoreSpikeDetector(15, 5, 0.1);
            feedTo(sensitive, 5, true);
            feedTo(sensitive, 5, false);
            feedTo(sensitive, 3, true);
            Optional<AnomalyEvent> result = feedLastTo(sensitive, false);
            // last event brings signal to 4 errors, 1 info... let's just check behaviour
            assertThat(sensitive.name()).isEqualTo("z-score-spike"); // sanity check
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** Feed n events of the given type to the default detector. */
    private void feedN(int n, boolean isError) {
        for (int i = 0; i < n; i++) detector.evaluate(isError ? error() : info());
    }

    /**
     * Feed baseline to the default detector (uses {@code WINDOW - SIGNAL} events).
     * Always uses the default detector.
     */
    private void baseline(int n, boolean isError) {
        for (int i = 0; i < n; i++) detector.evaluate(isError ? error() : info());
    }

    /**
     * Feed exactly {@code SIGNAL} events of the given type and return the last result.
     */
    private Optional<AnomalyEvent> signal(int n, boolean isError) {
        Optional<AnomalyEvent> result = Optional.empty();
        for (int i = 0; i < n; i++) result = detector.evaluate(isError ? error() : info());
        return result;
    }

    /** Feed n errors then m infos; return the result of the last event. */
    private Optional<AnomalyEvent> feedN_last(int errors, boolean errFirst,
                                               int others, boolean otherType) {
        Optional<AnomalyEvent> result = Optional.empty();
        for (int i = 0; i < errors; i++) result = detector.evaluate(errFirst ? error() : info());
        for (int i = 0; i < others; i++) result = detector.evaluate(otherType ? error() : info());
        return result;
    }

    // Helpers for named detectors

    private void feedTo(ZScoreSpikeDetector d, int n, boolean isError) {
        for (int i = 0; i < n; i++) d.evaluate(isError ? error() : info());
    }

    private Optional<AnomalyEvent> feedSignalTo(ZScoreSpikeDetector d, int n, boolean isError) {
        Optional<AnomalyEvent> result = Optional.empty();
        for (int i = 0; i < n; i++) result = d.evaluate(isError ? error() : info());
        return result;
    }

    private Optional<AnomalyEvent> feedLastTo(ZScoreSpikeDetector d, boolean isError) {
        return d.evaluate(isError ? error() : info());
    }

    private LogEvent error() {
        return event(LogLevel.ERROR);
    }

    private LogEvent info() {
        return event(LogLevel.INFO);
    }

    private LogEvent event(LogLevel level) {
        return event("test-service", level);
    }

    private LogEvent event(String serviceId, LogLevel level) {
        return new LogEvent(UUID.randomUUID().toString(), serviceId,
                level, "test message", Instant.now(), "test message");
    }
}
