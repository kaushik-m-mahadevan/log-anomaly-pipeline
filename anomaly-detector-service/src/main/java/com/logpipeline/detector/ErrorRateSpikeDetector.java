package com.logpipeline.detector;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.AnomalySeverity;
import com.logpipeline.model.LogEvent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;

/**
 * Sliding-window error rate detector.
 *
 * Maintains a fixed-size circular window of recent log events per service.
 * An anomaly is raised when the proportion of ERROR/FATAL events in the window
 * exceeds the configured threshold.
 *
 * This is the MVP implementation of AnomalyDetector — intentionally simple
 * and parameter-driven so it can be replaced by the Z-score detector later
 * without touching any Kafka or service-layer code.
 *
 * Thread safety: this class is NOT thread-safe by design. DetectorService
 * maintains one instance per serviceId, so concurrent calls do not occur
 * for the same key. If that assumption changes, synchronize on the window.
 */
public class ErrorRateSpikeDetector implements AnomalyDetector {

    private static final String DETECTOR_NAME = "error-rate-spike";

    private final int windowSize;
    private final double errorRateThreshold;
    private final Deque<Boolean> window;  // true = error event

    /**
     * Tracks whether an anomaly is currently active for this service.
     * Ensures alerts fire once on the healthy→anomalous transition, then are
     * suppressed until the error rate recovers below the threshold (re-arming).
     * Without this, a sustained outage would flood the alert channel on every
     * incoming event.
     */
    private boolean anomalyActive = false;

    /**
     * @param windowSize          number of recent events to track per service
     * @param errorRateThreshold  fraction of errors that triggers an anomaly (0.0–1.0)
     */
    public ErrorRateSpikeDetector(int windowSize, double errorRateThreshold) {
        if (windowSize <= 0) throw new IllegalArgumentException("windowSize must be positive");
        if (errorRateThreshold <= 0 || errorRateThreshold > 1)
            throw new IllegalArgumentException("errorRateThreshold must be in (0, 1]");

        this.windowSize = windowSize;
        this.errorRateThreshold = errorRateThreshold;
        this.window = new ArrayDeque<>(windowSize);
    }

    @Override
    public Optional<AnomalyEvent> evaluate(LogEvent event) {
        boolean isError = event.level().isError();
        slide(isError);

        if (window.size() < windowSize) {
            return Optional.empty();   // window not yet full — insufficient data
        }

        double errorRate = computeErrorRate();

        if (errorRate >= errorRateThreshold) {
            if (anomalyActive) {
                return Optional.empty();  // still in anomaly state — suppress until recovery
            }
            anomalyActive = true;
            return Optional.of(buildAnomalyEvent(event, errorRate));
        }

        // Rate has fallen back below threshold — re-arm for the next spike
        anomalyActive = false;
        return Optional.empty();
    }

    @Override
    public String name() {
        return DETECTOR_NAME;
    }

    private void slide(boolean isError) {
        if (window.size() == windowSize) {
            window.pollFirst();  // evict oldest
        }
        window.addLast(isError);
    }

    private double computeErrorRate() {
        long errorCount = window.stream().filter(Boolean::booleanValue).count();
        return (double) errorCount / window.size();
    }

    private AnomalyEvent buildAnomalyEvent(LogEvent triggeringEvent, double errorRate) {
        AnomalySeverity severity = classifySeverity(errorRate);

        return new AnomalyEvent(
                AnomalyEvent.CURRENT_VERSION,
                UUID.randomUUID().toString(),
                triggeringEvent.serviceId(),
                severity,
                String.format("Error rate spike detected: %.1f%% of last %d events were errors (threshold: %.1f%%)",
                        errorRate * 100, windowSize, errorRateThreshold * 100),
                errorRate,
                errorRateThreshold,
                Instant.now()
        );
    }

    private AnomalySeverity classifySeverity(double errorRate) {
        if (errorRate >= 0.9) return AnomalySeverity.CRITICAL;
        if (errorRate >= 0.7) return AnomalySeverity.HIGH;
        if (errorRate >= 0.5) return AnomalySeverity.MEDIUM;
        return AnomalySeverity.LOW;
    }
}
