package com.logpipeline.model;

import java.time.Instant;

/**
 * Represents a detected anomaly, published to the anomaly-alerts Kafka topic.
 * Enriched with enough context for the alert-service to act without re-querying.
 *
 * Single source of truth shared by anomaly-detector-service (producer)
 * and alert-service (consumer).
 *
 * schemaVersion enables independent rolling deploys: consumers can check this
 * field and reject (or park to DLT) messages from incompatible producers without
 * failing silently on unknown fields.
 */
public record AnomalyEvent(
        String schemaVersion,
        String anomalyId,
        String serviceId,
        AnomalySeverity severity,
        String description,
        double detectedValue,
        double threshold,
        Instant detectedAt
) {
    /** Current schema version. Increment when fields are added or removed. */
    public static final String CURRENT_VERSION = "1";
}
