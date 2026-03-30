package com.logpipeline.dto;

import java.time.Instant;

/**
 * Canonical log event published to the processed-logs Kafka topic.
 * This is the contract between ingestion and the detector — change with care.
 */
public record LogEventMessage(
        String eventId,
        String serviceId,
        String level,
        String message,
        Instant timestamp,
        String normalizedMessage
) {}
