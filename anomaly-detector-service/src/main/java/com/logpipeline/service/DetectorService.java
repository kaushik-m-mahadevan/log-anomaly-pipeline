package com.logpipeline.service;

import com.logpipeline.detector.AnomalyDetector;
import com.logpipeline.detector.ErrorRateSpikeDetector;
import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.LogEvent;
import com.logpipeline.model.LogLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the detection pipeline.
 *
 * Responsibilities:
 *   1. Map incoming Kafka messages to domain LogEvent objects
 *   2. Route each event to the correct per-service detector instance
 *   3. Publish AnomalyEvents when the detector fires
 *
 * Per-service detector instances: each serviceId gets its own AnomalyDetector,
 * which maintains independent sliding-window state. This is the architectural
 * decision that makes the partition-key-by-serviceId strategy meaningful.
 */
@Service
public class DetectorService {

    private static final Logger log = LoggerFactory.getLogger(DetectorService.class);

    private final KafkaTemplate<String, AnomalyEvent> kafkaTemplate;
    private final String anomalyAlertsTopic;
    private final int windowSize;
    private final double errorRateThreshold;

    /**
     * One detector per serviceId — created lazily on first event for that service.
     * ConcurrentHashMap because multiple Kafka listener threads may call process() concurrently
     * for different partitions.
     */
    private final Map<String, AnomalyDetector> detectorRegistry = new ConcurrentHashMap<>();

    public DetectorService(
            KafkaTemplate<String, AnomalyEvent> kafkaTemplate,
            @Value("${kafka.topics.anomaly-alerts}") String anomalyAlertsTopic,
            @Value("${detector.window-size:20}") int windowSize,
            @Value("${detector.error-rate-threshold:0.4}") double errorRateThreshold) {
        this.kafkaTemplate = kafkaTemplate;
        this.anomalyAlertsTopic = anomalyAlertsTopic;
        this.windowSize = windowSize;
        this.errorRateThreshold = errorRateThreshold;
    }

    /**
     * Entry point called by the Kafka consumer for each incoming log event.
     * Deserialises, detects, and publishes — in that order.
     */
    public void process(Map<String, Object> rawPayload) {
        try {
            LogEvent event = toLogEvent(rawPayload);
            log.info("Processing log event from service {}: level={}, message={}", 
                    event.serviceId(), event.level(), event.message());
            AnomalyDetector detector = detectorFor(event.serviceId());
            Optional<AnomalyEvent> anomaly = detector.evaluate(event);

            anomaly.ifPresent(a -> {
                log.warn("[{}] Anomaly detected by {}: {} (value={}, threshold={})",
                        a.serviceId(), detector.name(), a.description(),
                        a.detectedValue(), a.threshold());
                publishAnomaly(a);
            });

        } catch (Exception ex) {
            log.error("Failed to process log event payload: {}", rawPayload, ex);
            // Exception is swallowed here intentionally — the consumer's error handler
            // will route the original Kafka record to the dead-letter topic.
            throw ex;
        }
    }

    private LogEvent toLogEvent(Map<String, Object> payload) {
        Object tsObj = payload.get("timestamp");
        Instant timestamp;
        if (tsObj instanceof String s) {
            timestamp = Instant.parse(s);
        } else if (tsObj instanceof Number n) {
            timestamp = Instant.ofEpochMilli(n.longValue());
        } else {
            timestamp = Instant.now();
        }

        String rawLevel = (String) payload.get("level");
        if (rawLevel == null) {
            throw new IllegalArgumentException("Missing required field 'level' in log event payload");
        }
        LogLevel level;
        try {
            level = LogLevel.valueOf(rawLevel);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown log level '" + rawLevel + "' in log event payload");
        }

        return new LogEvent(
                (String) payload.get("eventId"),
                (String) payload.get("serviceId"),
                level,
                (String) payload.get("message"),
                timestamp,
                (String) payload.getOrDefault("normalizedMessage", "")
        );
    }

    private AnomalyDetector detectorFor(String serviceId) {
        return detectorRegistry.computeIfAbsent(serviceId,
                id -> new ErrorRateSpikeDetector(windowSize, errorRateThreshold));
    }

    private void publishAnomaly(AnomalyEvent anomaly) {
        kafkaTemplate.send(anomalyAlertsTopic, anomaly.serviceId(), anomaly)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish anomaly event [anomalyId={}]: {}",
                                anomaly.anomalyId(), ex.getMessage());
                    } else {
                        log.info("Anomaly published [anomalyId={}, partition={}, offset={}]",
                                anomaly.anomalyId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}

