package com.logpipeline.consumer;

import com.logpipeline.model.LogEventMessage;
import com.logpipeline.service.DetectorService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for the processed-logs topic.
 *
 * Separation of concerns: this class handles only Kafka I/O and error handling.
 * All business logic lives in DetectorService — the listener is infrastructure.
 *
 * Item 2: The consumer record type is now LogEventMessage (shared via common module)
 * instead of Map<String,Object>. If the ingestion service renames a field, this
 * class will fail at deserialization time rather than silently routing null values.
 *
 * @RetryableTopic configures non-blocking retries with exponential backoff,
 * routing exhausted messages to a dead-letter topic automatically.
 */
@Component
public class LogEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(LogEventConsumer.class);

    private final DetectorService detectorService;

    public LogEventConsumer(DetectorService detectorService) {
        this.detectorService = detectorService;
    }

    @RetryableTopic(
        attempts = "3",
        backoff = @Backoff(delay = 1000, multiplier = 2.0),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltTopicSuffix = "-dlt"
    )
    @KafkaListener(
        topics = "${kafka.topics.processed-logs}",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(ConsumerRecord<String, LogEventMessage> record) {
        log.info("Received log event [partition={}, offset={}, key={}]",
            record.partition(), record.offset(), record.key());
        
        LogEventMessage message = record.value();
        if (message == null) {
            log.warn("Received null log event message, skipping [partition={}, offset={}]",
                    record.partition(), record.offset());
            return;
        }
        
        detectorService.process(message);
    }
}
