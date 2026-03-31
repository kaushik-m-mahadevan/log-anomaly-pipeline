package com.logpipeline.consumer;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.service.AlertRoutingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Kafka listener for anomaly alerts.
 * Delegates immediately to AlertRoutingService — no business logic here.
 *
 * Item 5: Manual acknowledgment — the offset is committed only after route() returns
 * successfully. If the alert channel throws, the offset is NOT committed and the
 * event will be redelivered on the next poll (no silent drops).
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
    public void consume(@Payload(required = false) AnomalyEvent event, Acknowledgment ack, ConsumerRecord<String, AnomalyEvent> record) {
        log.debug("Received anomaly event [partition={}, offset={}, serviceId={}]",
                record.partition(), record.offset(), record.key());

        if (event == null) {
            log.warn("Received null anomaly event, skipping [partition={}, offset={}]",
                    record.partition(), record.offset());
            ack.acknowledge();
            return;
        }

        routingService.route(event);

        // Item 5: acknowledge only after successful routing
        ack.acknowledge();
    }
}
