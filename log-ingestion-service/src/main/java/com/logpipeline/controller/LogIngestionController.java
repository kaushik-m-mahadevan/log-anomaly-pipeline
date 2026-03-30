package com.logpipeline.controller;

import com.logpipeline.dto.LogEventRequest;
import com.logpipeline.service.LogPublisherService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Entry point for log ingestion.
 *
 * POST /api/v1/logs/batch — accepts up to 500 events per request.
 * Chosen over a single-event endpoint because:
 *   1. Reduces HTTP overhead for high-throughput sources (agents, log shippers)
 *   2. Maps naturally onto Kafka producer batching (linger.ms micro-batching)
 *   3. Simplifies client retry logic — one call, one result
 */
@RestController
@RequestMapping("/api/v1/logs")
@Validated
public class LogIngestionController {

    private static final Logger log = LoggerFactory.getLogger(LogIngestionController.class);
    private static final int MAX_BATCH_SIZE = 500;

    private final LogPublisherService publisherService;

    public LogIngestionController(LogPublisherService publisherService) {
        this.publisherService = publisherService;
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> ingestBatch(
            @RequestBody @NotEmpty @Size(max = MAX_BATCH_SIZE) List<@Valid LogEventRequest> requests) {

        log.info("Received batch of {} log events", requests.size());
        publisherService.publishBatch(requests);

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)   // 202: accepted for async processing, not yet committed
                .body(Map.of(
                        "accepted", requests.size(),
                        "status", "queued"
                ));
    }

    /**
     * Convenience single-event endpoint — delegates to the batch path.
     * Useful for testing and simple integrations.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> ingestSingle(
            @RequestBody @Valid LogEventRequest request) {
        return ingestBatch(List.of(request));
    }
}
