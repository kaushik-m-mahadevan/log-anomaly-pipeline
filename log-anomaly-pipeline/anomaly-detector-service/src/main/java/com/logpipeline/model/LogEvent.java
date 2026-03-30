package com.logpipeline.model;

import java.time.Instant;

/**
 * Internal domain representation of a log event.
 * Deliberately separate from the ingestion DTO — the detector's domain model
 * must not be coupled to the ingestion service's API contract.
 */
public record LogEvent(
        String eventId,
        String serviceId,
        LogLevel level,
        String message,
        Instant timestamp,
        String normalizedMessage
) {}
