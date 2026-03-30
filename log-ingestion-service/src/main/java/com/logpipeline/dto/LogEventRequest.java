package com.logpipeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

/**
 * Inbound DTO for a single log event from an external client.
 * Validated at the controller boundary; never leaks into Kafka or domain logic.
 */
public record LogEventRequest(

        @NotBlank(message = "serviceId must not be blank")
        String serviceId,

        @NotNull(message = "level must not be null")
        @Pattern(regexp = "DEBUG|INFO|WARN|ERROR|FATAL",
                 message = "level must be one of: DEBUG, INFO, WARN, ERROR, FATAL")
        String level,

        @NotBlank(message = "message must not be blank")
        String message,

        Instant timestamp
) {
    /** Provides a default timestamp when clients omit it. */
    public Instant resolvedTimestamp() {
        return timestamp != null ? timestamp : Instant.now();
    }
}
