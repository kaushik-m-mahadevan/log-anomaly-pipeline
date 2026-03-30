package com.logpipeline.consumer;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.service.AlertRoutingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for anomaly alerts.
 * Delegates immediately to AlertRoutingService — no business logic here.
 */
@Component
public class AnomalyEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AnomalyEventConsumer.class);

    private final AlertRoutingService routingService;

    public AnomalyEventConsumer(AlertRoutingService routingService) {
        this.routingService = routingService;
    }

    @KafkaListener(
            topics = "${kafka.topics.anomaly-alerts}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "alertKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, AnomalyEvent> record) {
        log.debug("Received anomaly event [partition={}, offset={}, serviceId={}]",
                record.partition(), record.offset(), record.key());

        routingService.route(record.value());
    }
}
