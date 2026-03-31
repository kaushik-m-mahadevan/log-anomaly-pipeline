package com.logpipeline.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.logpipeline.detector.AnomalyDetector;
import com.logpipeline.detector.ZScoreSpikeDetector;
import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.LogEventMessage;
import com.logpipeline.model.LogLevel;
import com.logpipeline.model.LogEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the detection pipeline.
 *
 * Responsibilities:
 *   1. Map incoming Kafka messages to domain LogEvent objects
 *   2. Route each event to the correct per-service detector instance
 *   3. Publish AnomalyEvents when the detector fires
 *
 * Item 2: process() now takes LogEventMessage (typed) instead of Map<String,Object>.
 * Item 4: idempotency guard — duplicate eventIds (from retried producers) are dropped.
 * Item 6: detector registry is a bounded Caffeine cache (max 10,000 services, 1h TTL)
 *         to prevent OOM when unique serviceIds grow without bound.
 * Item 10: custom Micrometer metrics expose anomaly rates and registry size to Prometheus.
 */
@Service
public class DetectorService {

    private static final Logger log = LoggerFactory.getLogger(DetectorService.class);

    private final KafkaTemplate<String, AnomalyEvent> kafkaTemplate;
    private final String anomalyAlertsTopic;
    private final int windowSize;
    private final int signalWindowSize;
    private final double zScoreThreshold;

    // Item 6: bounded Caffeine cache — evicts inactive services after 1 h to prevent OOM
    private final Cache<String, AnomalyDetector> detectorRegistry;

    // Item 4: seen-eventId cache — bounded LRU to deduplicate producer retries
    private final Cache<String, Boolean> seenEventIds;

    // Item 10: custom metrics
    private final Counter anomaliesDetectedCounter;
    private final Counter duplicatesDroppedCounter;
    private final Counter publishFailedCounter;

    public DetectorService(
            KafkaTemplate<String, AnomalyEvent> kafkaTemplate,
            @Value("${kafka.topics.anomaly-alerts}") String anomalyAlertsTopic,
            @Value("${detector.window-size:30}") int windowSize,
            @Value("${detector.signal-window-size:10}") int signalWindowSize,
            @Value("${detector.z-score-threshold:2.0}") double zScoreThreshold,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate      = kafkaTemplate;
        this.anomalyAlertsTopic = anomalyAlertsTopic;
        this.windowSize         = windowSize;
        this.signalWindowSize   = signalWindowSize;
        this.zScoreThreshold    = zScoreThreshold;

        this.detectorRegistry = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(Duration.ofHours(1))
                .build();

        this.seenEventIds = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build();

        // Item 10: register metrics
        this.anomaliesDetectedCounter = Counter.builder("detector.anomalies.detected")
                .description("Total anomalies detected across all services")
                .register(meterRegistry);
        this.duplicatesDroppedCounter = Counter.builder("detector.events.duplicates.dropped")
                .description("Events dropped due to duplicate eventId (idempotency guard)")
                .register(meterRegistry);
        this.publishFailedCounter = Counter.builder("detector.anomalies.publish.failed")
                .description("Anomaly events that failed to publish to Kafka")
                .register(meterRegistry);

        // Gauge for live registry size — helps detect unbounded growth before OOM
        Gauge.builder("detector.registry.size", detectorRegistry, Cache::estimatedSize)
                .description("Number of active per-service detector instances")
                .register(meterRegistry);
    }

    /**
     * Entry point called by the Kafka consumer for each incoming log event.
     * Deserialises, deduplicates, detects, and publishes — in that order.
     *
     * Item 2: takes typed LogEventMessage — no more Map<String,Object> parsing.
     * Item 4: skips duplicate eventIds from retried producers.
     */
    public void process(LogEventMessage message) {
        try {
            // Item 4: idempotency guard — drop retried messages we've already processed
            if (message.eventId() != null && seenEventIds.getIfPresent(message.eventId()) != null) {
                duplicatesDroppedCounter.increment();
                log.debug("Dropping duplicate event [eventId={}]", message.eventId());
                return;
            }
            if (message.eventId() != null) {
                seenEventIds.put(message.eventId(), Boolean.TRUE);
            }

            LogEvent event = toLogEvent(message);
            log.info("Processing log event from service {}: level={}", event.serviceId(), event.level());

            AnomalyDetector detector = detectorFor(event.serviceId());
            Optional<AnomalyEvent> anomaly = detector.evaluate(event);

            anomaly.ifPresent(a -> {
                anomaliesDetectedCounter.increment();
                log.warn("[{}] Anomaly detected by {}: {} (value={}, threshold={})",
                        a.serviceId(), detector.name(), a.description(),
                        a.detectedValue(), a.threshold());
                publishAnomaly(a);
            });

        } catch (Exception ex) {
            log.error("Failed to process log event [eventId={}, serviceId={}]: {}",
                    message.eventId(), message.serviceId(), ex.getMessage());
            // Re-throw so the consumer's @RetryableTopic handler routes to DLT after retries
            throw ex;
        }
    }

    /**
     * Returns the number of active service detectors. Used by the warmup health indicator.
     */
    public long registrySize() {
        return detectorRegistry.estimatedSize();
    }

    private LogEvent toLogEvent(LogEventMessage msg) {
        if (msg.level() == null) {
            throw new IllegalArgumentException("Missing required field 'level' in log event message");
        }
        LogLevel level;
        try {
            level = LogLevel.valueOf(msg.level());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown log level '" + msg.level() + "' in log event message");
        }
        return new LogEvent(
                msg.eventId(),
                msg.serviceId(),
                level,
                msg.message(),
                msg.timestamp() != null ? msg.timestamp() : Instant.now(),
                msg.normalizedMessage() != null ? msg.normalizedMessage() : ""
        );
    }

    private AnomalyDetector detectorFor(String serviceId) {
        return detectorRegistry.get(serviceId,
                id -> new ZScoreSpikeDetector(windowSize, signalWindowSize, zScoreThreshold));
    }

    private void publishAnomaly(AnomalyEvent anomaly) {
        // Item 11: log and meter every failed publish so ops can alert on the metric
        kafkaTemplate.send(anomalyAlertsTopic, anomaly.serviceId(), anomaly)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        publishFailedCounter.increment();
                        log.error("Failed to publish anomaly event [anomalyId={}, serviceId={}]: {}",
                                anomaly.anomalyId(), anomaly.serviceId(), ex.getMessage());
                    } else {
                        log.info("Anomaly published [anomalyId={}, partition={}, offset={}]",
                                anomaly.anomalyId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
