package com.logpipeline.model;

import java.time.Instant;

/**
 * Canonical log event published to the processed-logs Kafka topic.
 * This is the wire contract between log-ingestion-service (producer) and
 * anomaly-detector-service (consumer) — change field names or types with care.
 *
 * Shared via the 'common' module so both services compile against the same type.
 * If the schema needs to evolve, increment schemaVersion and handle both versions
 * in the consumer before removing the old one (expand-contract pattern).
 */
public record LogEventMessage(
        String schemaVersion,
        String eventId,
        String serviceId,
        String level,
        String message,
        Instant timestamp,
        String normalizedMessage
) {
    /** Current schema version. Increment when fields are added or removed. */
    public static final String CURRENT_VERSION = "1";
}
