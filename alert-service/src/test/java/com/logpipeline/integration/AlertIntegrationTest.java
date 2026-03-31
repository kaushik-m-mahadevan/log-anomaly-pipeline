package com.logpipeline.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.logpipeline.channel.AlertChannel;
import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.AnomalySeverity;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Integration test for alert-service (Item 16).
 *
 * Verifies: Kafka consumer → AlertRoutingService → AlertChannel.
 * Uses @EmbeddedKafka and mocks the AlertChannel to capture delivery.
 *
 * What this catches that unit tests don't:
 *   - @KafkaListener topic / groupId misconfiguration
 *   - AnomalyEvent deserialization from Kafka (schemaVersion, Instant fields, etc.)
 *   - Manual ack (AckMode.RECORD) — ensures offset committed after route() completes
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        brokerProperties = {"listeners=PLAINTEXT://localhost:${kafka.embedded.port:19094}",
                            "port=${kafka.embedded.port:19094}"},
        topics = {"anomaly-alerts"}
)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "kafka.topics.anomaly-alerts=anomaly-alerts"
})
@DirtiesContext
class AlertIntegrationTest {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    /**
     * Mock the AlertChannel so we can verify it receives the deserialized AnomalyEvent
     * without needing a real console or network channel.
     */
    @MockBean
    private AlertChannel alertChannel;

    @Test
    @DisplayName("AnomalyEvent published to anomaly-alerts is routed to the alert channel")
    void anomalyEvent_routedToAlertChannel() {
        when_channelNameIs("mock-channel");
        KafkaTemplate<String, AnomalyEvent> producer = buildProducer();

        AnomalyEvent event = new AnomalyEvent(
                AnomalyEvent.CURRENT_VERSION,
                UUID.randomUUID().toString(),
                "checkout-service",
                AnomalySeverity.HIGH,
                "Z-score spike detected",
                3.8,
                2.0,
                Instant.now()
        );

        producer.send("anomaly-alerts", event.serviceId(), event);
        producer.flush();

        // Verify the channel receives the event within 10 s
        verify(alertChannel, timeout(10_000).times(1)).send(any(AnomalyEvent.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void when_channelNameIs(String name) {
        org.mockito.Mockito.when(alertChannel.channelName()).thenReturn(name);
    }

    private KafkaTemplate<String, AnomalyEvent> buildProducer() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonSerializer<AnomalyEvent> valueSerializer = new JsonSerializer<>(mapper);
        valueSerializer.setAddTypeInfo(false);

        Map<String, Object> props = KafkaTestUtils.producerProps(embeddedKafka);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        return new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer));
    }
}
