package com.logpipeline.detector;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.LogEvent;

import java.util.Optional;

/**
 * Strategy interface for anomaly detection algorithms.
 *
 * Implementations receive a single log event and return an AnomalyEvent
 * if an anomaly is detected, or empty if the event is within normal bounds.
 *
 * Design constraints (enforced by this interface):
 *   - No Spring dependencies — detectors are pure domain logic
 *   - No Kafka I/O — DetectorService owns all messaging concerns
 *   - Stateful implementations must be thread-safe (one instance per serviceId)
 */
public interface AnomalyDetector {

    /**
     * Evaluates a log event and returns an anomaly if one is detected.
     *
     * @param event the incoming log event to evaluate
     * @return an anomaly event if a threshold is breached, otherwise empty
     */
    Optional<AnomalyEvent> evaluate(LogEvent event);

    /**
     * Human-readable name for this detector strategy, used in logs and metrics.
     */
    String name();
}
