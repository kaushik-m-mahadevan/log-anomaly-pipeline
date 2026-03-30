package com.logpipeline.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Inbound DTO for a batch of log events from an external client.
 * Wraps the list of events to match the expected JSON structure {"events": [...]}
 */
public record LogEventBatchRequest(

        @NotEmpty(message = "events must not be empty")
        List<@Valid LogEventRequest> events
) {}