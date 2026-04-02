package com.logpipeline.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.LogEventMessage;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for anomaly-detector-service (Item 16).
 *
 * Verifies: Kafka consumer → DetectorService → Kafka producer.
 * Uses @EmbeddedKafka to avoid requiring a running broker.
 *
 * What this catches that unit tests don't:
 *   - @KafkaListener topic / groupId misconfiguration
 *   - Wrong deserializer / LogEventMessage field mapping
 *   - Anomaly event published to the correct topic with correct serviceId key
 *   - schemaVersion field is preserved end-to-end
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 3,   // must match TopicConfig which creates anomaly-alerts with 3 partitions
        brokerProperties = {"listeners=PLAINTEXT://localhost:${kafka.embedded.port:19093}",
                            "port=${kafka.embedded.port:19093}"},
        topics = {"processed-logs", "anomaly-alerts", "processed-logs-dlt"}
)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "kafka.topics.processed-logs=processed-logs",
        "kafka.topics.anomaly-alerts=anomaly-alerts",
        "kafka.replication-factor=1",
        "kafka.consumer.max-poll-records=100",
        "detector.window-size=5",
        "detector.signal-window-size=2",
        "detector.z-score-threshold=2.0"
})
@DirtiesContext
class DetectorIntegrationTest {

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Test
    @DisplayName("A spike pattern in processed-logs triggers an AnomalyEvent on anomaly-alerts")
    void spike_producesAnomalyEventOnAlertsTopic() throws Exception {
        BlockingQueue<ConsumerRecord<String, AnomalyEvent>> anomalies = listenToAnomalyAlerts();
        KafkaTemplate<String, LogEventMessage> producer = buildProducer();

        // Baseline: 3 INFO events
        publishLog(producer, "payment-service", "INFO");
        publishLog(producer, "payment-service", "INFO");
        publishLog(producer, "payment-service", "INFO");

        // Signal spike: 2 ERROR events → window full (3+2=5), all 2 signal = errors → fires
        publishLog(producer, "payment-service", "ERROR");
        publishLog(producer, "payment-service", "ERROR");

        // Wait for anomaly to appear (up to 10 s)
        ConsumerRecord<String, AnomalyEvent> anomalyRecord = anomalies.poll(10, TimeUnit.SECONDS);
        assertThat(anomalyRecord).isNotNull();

        AnomalyEvent anomaly = anomalyRecord.value();
        assertThat(anomaly.serviceId()).isEqualTo("payment-service");
        assertThat(anomaly.schemaVersion()).isEqualTo(AnomalyEvent.CURRENT_VERSION);
        assertThat(anomaly.anomalyId()).isNotBlank();
        assertThat(anomaly.severity()).isNotNull();
        assertThat(anomaly.detectedAt()).isNotNull();
        // Partition key on anomaly-alerts topic is serviceId
        assertThat(anomalyRecord.key()).isEqualTo("payment-service");
    }

    @Test
    @DisplayName("Pure INFO log stream produces no anomaly event")
    void allInfoEvents_noAnomalyProduced() throws Exception {
        BlockingQueue<ConsumerRecord<String, AnomalyEvent>> anomalies = listenToAnomalyAlerts();
        KafkaTemplate<String, LogEventMessage> producer = buildProducer();

        for (int i = 0; i < 10; i++) {
            publishLog(producer, "stable-service", "INFO");
        }

        // Wait 3 s — nothing should arrive
        ConsumerRecord<String, AnomalyEvent> anomaly = anomalies.poll(3, TimeUnit.SECONDS);
        assertThat(anomaly).isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void publishLog(KafkaTemplate<String, LogEventMessage> producer,
                            String serviceId, String level) {
        LogEventMessage msg = new LogEventMessage(
                LogEventMessage.CURRENT_VERSION,
                UUID.randomUUID().toString(),
                serviceId, level, "test message", Instant.now(), "test message");
        producer.send("processed-logs", serviceId, msg);
        producer.flush();
    }

    private KafkaTemplate<String, LogEventMessage> buildProducer() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonSerializer<LogEventMessage> valueSerializer = new JsonSerializer<>(mapper);
        valueSerializer.setAddTypeInfo(false);

        Map<String, Object> props = KafkaTestUtils.producerProps(embeddedKafka);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        DefaultKafkaProducerFactory<String, LogEventMessage> pf =
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), valueSerializer);
        return new KafkaTemplate<>(pf);
    }

    private BlockingQueue<ConsumerRecord<String, AnomalyEvent>> listenToAnomalyAlerts()
            throws InterruptedException {

        Map<String, Object> props = KafkaTestUtils.consumerProps(
                "detector-integration-" + System.nanoTime(), "false", embeddedKafka);
        // Use "latest" so each test only reads events published after its consumer subscribes.
        // "earliest" replays the anomaly from the spike test into the allInfoEvents test.
        // waitForAssignment() below ensures assignment is complete before any events are published.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonDeserializer<AnomalyEvent> valueDeserializer = new JsonDeserializer<>(AnomalyEvent.class, mapper);
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.addTrustedPackages("com.logpipeline.model");

        DefaultKafkaConsumerFactory<String, AnomalyEvent> cf =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), valueDeserializer);

        BlockingQueue<ConsumerRecord<String, AnomalyEvent>> queue = new LinkedBlockingQueue<>();
        ContainerProperties containerProps = new ContainerProperties("anomaly-alerts");
        containerProps.setMessageListener((MessageListener<String, AnomalyEvent>) queue::add);

        KafkaMessageListenerContainer<String, AnomalyEvent> container =
                new KafkaMessageListenerContainer<>(cf, containerProps);
        container.start();
        ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic());
        return queue;
    }
}
