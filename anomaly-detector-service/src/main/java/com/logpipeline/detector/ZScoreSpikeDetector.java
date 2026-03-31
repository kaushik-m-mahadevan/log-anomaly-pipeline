package com.logpipeline.detector;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.AnomalySeverity;
import com.logpipeline.model.LogEvent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Z-score sliding-window anomaly detector.
 *
 * Improves on the fixed-threshold ErrorRateSpikeDetector by measuring how
 * unusual the current error rate is *relative to this service's own recent
 * baseline*, rather than comparing it to a hard-coded absolute threshold.
 *
 * Algorithm
 * ─────────
 * The window is partitioned into two zones (oldest → newest):
 *
 *   ┌──────────────────────────────────┬─────────────────┐
 *   │  BASELINE  (windowSize - signal) │  SIGNAL (n)     │
 *   │  Establishes "normal" behaviour  │  What we test   │
 *   └──────────────────────────────────┴─────────────────┘
 *
 * On every event:
 *   1. Slide the new event into the deque.
 *   2. If the window is not yet full, return empty (insufficient data).
 *   3. Compute baselineRate  = errors / baseline events.
 *   4. Compute signalRate    = errors / signal events.
 *   5. Compute stdDev using the Bernoulli formula: √(p · (1 – p)),
 *      floored at MIN_STD_DEV to prevent division-by-zero on a perfectly
 *      stable baseline.
 *   6. Z-score = (signalRate – baselineRate) / stdDev.
 *   7. Edge-triggered cooldown: fire once on the healthy→anomalous
 *      transition; suppress until the Z-score drops below the threshold
 *      (re-arming for the next spike).
 *
 * Why Z-score is better than a fixed threshold
 * ────────────────────────────────────────────
 * • A noisy legacy service running at 60 % errors won't trigger false alarms.
 * • A pristine service spiking from 0 % to 20 % will still be caught — a
 *   fixed 40 % threshold would miss it entirely.
 *
 * Thread safety: NOT thread-safe by design. DetectorService maintains one
 * instance per serviceId, so concurrent calls do not occur for the same key.
 */
public class ZScoreSpikeDetector implements AnomalyDetector {

    private static final String DETECTOR_NAME = "z-score-spike";

    /**
     * Minimum standard deviation applied when the baseline is perfectly
     * stable (all errors or all non-errors). Prevents division-by-zero and
     * keeps Z-scores meaningful for pristine services.
     */
    static final double MIN_STD_DEV = 0.01;

    private final int windowSize;
    private final int signalWindowSize;
    private final double zScoreThreshold;
    private final Deque<Boolean> window;

    /**
     * Tracks whether an anomaly is currently active for this service.
     * Suppresses repeated alerts during a sustained spike; resets when the
     * Z-score recovers below the threshold.
     */
    private boolean anomalyActive = false;

    /**
     * @param windowSize       total number of events tracked (baseline + signal)
     * @param signalWindowSize recent events used as the test signal; must be
     *                         strictly less than windowSize so the baseline has
     *                         at least one event
     * @param zScoreThreshold  standard deviations above the baseline mean that
     *                         trigger an anomaly (e.g. 2.0)
     */
    public ZScoreSpikeDetector(int windowSize, int signalWindowSize, double zScoreThreshold) {
        if (windowSize <= 0)
            throw new IllegalArgumentException("windowSize must be positive");
        if (signalWindowSize <= 0)
            throw new IllegalArgumentException("signalWindowSize must be positive");
        if (signalWindowSize >= windowSize)
            throw new IllegalArgumentException(
                    "signalWindowSize must be less than windowSize so a baseline exists");
        if (zScoreThreshold <= 0)
            throw new IllegalArgumentException("zScoreThreshold must be positive");

        this.windowSize       = windowSize;
        this.signalWindowSize = signalWindowSize;
        this.zScoreThreshold  = zScoreThreshold;
        this.window           = new ArrayDeque<>(windowSize);
    }

    @Override
    public Optional<AnomalyEvent> evaluate(LogEvent event) {
        slide(event.level().isError());

        if (window.size() < windowSize) {
            return Optional.empty();   // window not yet full — insufficient data
        }

        // Snapshot the deque in insertion order (oldest first)
        List<Boolean> events    = new ArrayList<>(window);
        int           baselineN = windowSize - signalWindowSize;

        List<Boolean> baseline = events.subList(0, baselineN);
        List<Boolean> signal   = events.subList(baselineN, windowSize);

        double baselineRate = errorRate(baseline);
        double signalRate   = errorRate(signal);

        // Bernoulli standard deviation, floored to prevent division-by-zero
        double stdDev = Math.max(
                Math.sqrt(baselineRate * (1.0 - baselineRate)),
                MIN_STD_DEV);

        double zScore = (signalRate - baselineRate) / stdDev;

        if (zScore >= zScoreThreshold) {
            if (anomalyActive) {
                return Optional.empty();   // already active — suppress until recovery
            }
            anomalyActive = true;
            return Optional.of(buildAnomalyEvent(event, zScore, baselineRate, signalRate));
        }

        // Z-score recovered below threshold — re-arm for the next spike
        anomalyActive = false;
        return Optional.empty();
    }

    @Override
    public String name() {
        return DETECTOR_NAME;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void slide(boolean isError) {
        if (window.size() == windowSize) {
            window.pollFirst();   // evict oldest
        }
        window.addLast(isError);
    }

    private double errorRate(List<Boolean> events) {
        return events.stream().filter(Boolean::booleanValue).count() / (double) events.size();
    }

    private AnomalyEvent buildAnomalyEvent(
            LogEvent triggeringEvent, double zScore, double baselineRate, double signalRate) {

        return new AnomalyEvent(
                AnomalyEvent.CURRENT_VERSION,
                UUID.randomUUID().toString(),
                triggeringEvent.serviceId(),
                classifySeverity(zScore),
                String.format(
                        "Z-score spike detected: signal error rate %.1f%% vs baseline %.1f%% "
                                + "(Z=%.2f, threshold=%.1f, window=%d/%d)",
                        signalRate   * 100,
                        baselineRate * 100,
                        zScore,
                        zScoreThreshold,
                        signalWindowSize,
                        windowSize),
                zScore,           // detectedValue carries the Z-score
                zScoreThreshold,
                Instant.now()
        );
    }

    private AnomalySeverity classifySeverity(double zScore) {
        if (zScore >= 5.0) return AnomalySeverity.CRITICAL;
        if (zScore >= 3.5) return AnomalySeverity.HIGH;
        if (zScore >= 2.5) return AnomalySeverity.MEDIUM;
        return AnomalySeverity.LOW;
    }
}
