package com.logpipeline.service;

import com.logpipeline.dto.LogEventMessage;
import com.logpipeline.dto.LogEventRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Transforms inbound log requests into canonical Kafka messages and publishes them.
 *
 * Partition key strategy: serviceId — guarantees all logs from the same upstream
 * service land on the same partition, preserving per-service ordering and enabling
 * the detector's per-service sliding window without cross-partition coordination.
 */
@Service
public class LogPublisherService {

    private static final Logger log = LoggerFactory.getLogger(LogPublisherService.class);

    private final KafkaTemplate<String, LogEventMessage> kafkaTemplate;
    private final String processedLogsTopic;

    public LogPublisherService(
            KafkaTemplate<String, LogEventMessage> kafkaTemplate,
            @Value("${kafka.topics.processed-logs}") String processedLogsTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.processedLogsTopic = processedLogsTopic;
    }

    /**
     * Publishes a batch of log events asynchronously.
     * Each event is sent independently so a single failure does not block the batch.
     *
     * @param requests validated inbound log events
     * @return list of futures — callers can join if synchronous confirmation is needed
     */
    public List<CompletableFuture<SendResult<String, LogEventMessage>>> publishBatch(
            List<LogEventRequest> requests) {

        log.info("Starting to publish batch of {} log events to topic {}", requests.size(), processedLogsTopic);
        List<CompletableFuture<SendResult<String, LogEventMessage>>> futures = requests.stream()
                .map(this::toMessage)
                .map(this::publish)
                .toList();
        log.info("Completed setting up publication for {} events", requests.size());
        return futures;
    }

    private LogEventMessage toMessage(LogEventRequest request) {
        return new LogEventMessage(
                UUID.randomUUID().toString(),
                request.serviceId(),
                request.level(),
                request.message(),
                request.resolvedTimestamp(),
                normalize(request.message())
        );
    }

    private String normalize(String message) {
        return message.trim().toLowerCase();
    }

    private CompletableFuture<SendResult<String, LogEventMessage>> publish(LogEventMessage message) {
        CompletableFuture<SendResult<String, LogEventMessage>> future =
                kafkaTemplate.send(processedLogsTopic, message.serviceId(), message);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish log event [eventId={}, serviceId={}]: {}",
                        message.eventId(), message.serviceId(), ex.getMessage());
            } else {
                log.info("Successfully published log event [eventId={}, serviceId={}, partition={}, offset={}]",
                        message.eventId(), message.serviceId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });

        return future;
    }
}
