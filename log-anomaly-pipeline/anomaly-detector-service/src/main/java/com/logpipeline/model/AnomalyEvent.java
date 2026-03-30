package com.logpipeline.model;

import java.time.Instant;

/**
 * Represents a detected anomaly, published to the anomaly-alerts Kafka topic.
 * Enriched with enough context for the alert-service to act without re-querying.
 */
public record AnomalyEvent(
        String anomalyId,
        String serviceId,
        AnomalySeverity severity,
        String description,
        double detectedValue,
        double threshold,
        Instant detectedAt
) {}
