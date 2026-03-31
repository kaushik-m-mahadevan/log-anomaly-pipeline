package com.logpipeline.controller;

import com.logpipeline.dto.LogEventBatchRequest;
import com.logpipeline.dto.LogEventRequest;
import com.logpipeline.ratelimit.IngressRateLimiter;
import com.logpipeline.service.LogPublisherService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
    private final IngressRateLimiter rateLimiter;

    public LogIngestionController(LogPublisherService publisherService, IngressRateLimiter rateLimiter) {
        this.publisherService = publisherService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> ingestBatch(
            @RequestBody @Valid LogEventBatchRequest batch) {

        // Item 12: global rate limit — 429 before touching Kafka
        if (!rateLimiter.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded. Retry after 1 second.");
        }

        List<LogEventRequest> requests = batch.events();

        if (requests.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Batch size exceeds maximum of " + MAX_BATCH_SIZE);
        }

        log.info("Received batch of {} log events", requests.size());
        publisherService.publishBatch(requests);
        log.info("Published batch of {} log events to Kafka", requests.size());

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
        return ingestBatch(new LogEventBatchRequest(List.of(request)));
    }
}
