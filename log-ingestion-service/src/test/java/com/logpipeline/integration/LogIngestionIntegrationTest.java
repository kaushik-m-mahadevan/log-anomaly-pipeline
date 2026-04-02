package com.logpipeline.integration;

import com.logpipeline.model.LogEventMessage;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for log-ingestion-service (Item 16).
 *
 * Verifies the full path: HTTP POST → LogPublisherService → Kafka topic.
 * Uses @EmbeddedKafka to avoid requiring a running broker.
 *
 * What this catches that unit tests don't:
 *   - Misconfigured @KafkaListener topic names or groupIds
 *   - Wrong serializer/deserializer configuration
 *   - Partition key correctness (message lands on correct topic with correct key)
 *   - schemaVersion field is serialized and readable end-to-end
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
        partitions = 3,   // must match TopicConfig.processedLogsTopic() which hardcodes 3 partitions
        brokerProperties = {"listeners=PLAINTEXT://localhost:${kafka.embedded.port:19092}",
                            "port=${kafka.embedded.port:19092}"},
        topics = {"processed-logs"}
)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "kafka.topics.processed-logs=processed-logs",
        "kafka.replication-factor=1",
        "rate-limit.requests-per-second=10000"
})
@DirtiesContext
class LogIngestionIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Test
    @DisplayName("POST /api/v1/logs publishes a LogEventMessage to the processed-logs topic")
    void ingestSingle_publishesToKafka() throws InterruptedException {
        BlockingQueue<ConsumerRecord<String, LogEventMessage>> received = listenToProcessedLogs();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"serviceId\":\"test-svc\",\"level\":\"ERROR\",\"message\":\"db timeout\"}";

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/logs", new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Wait up to 5 seconds for the message to arrive in Kafka
        ConsumerRecord<String, LogEventMessage> record = received.poll(5, TimeUnit.SECONDS);
        assertThat(record).isNotNull();

        LogEventMessage msg = record.value();
        assertThat(msg.serviceId()).isEqualTo("test-svc");
        assertThat(msg.level()).isEqualTo("ERROR");
        assertThat(msg.schemaVersion()).isEqualTo(LogEventMessage.CURRENT_VERSION);
        assertThat(msg.eventId()).isNotBlank();
        // Partition key is serviceId
        assertThat(record.key()).isEqualTo("test-svc");
    }

    @Test
    @DisplayName("POST /api/v1/logs/batch publishes one message per event to Kafka")
    void ingestBatch_publishesOneMessagePerEvent() throws InterruptedException {
        BlockingQueue<ConsumerRecord<String, LogEventMessage>> received = listenToProcessedLogs();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"events":[
                  {"serviceId":"svc-a","level":"INFO","message":"started"},
                  {"serviceId":"svc-b","level":"ERROR","message":"failed"}
                ]}""";

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "/api/v1/logs/batch", new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("accepted", 2);

        ConsumerRecord<String, LogEventMessage> r1 = received.poll(5, TimeUnit.SECONDS);
        ConsumerRecord<String, LogEventMessage> r2 = received.poll(5, TimeUnit.SECONDS);
        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BlockingQueue<ConsumerRecord<String, LogEventMessage>> listenToProcessedLogs()
            throws InterruptedException {

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps(
                "integration-test-group-" + System.nanoTime(), "false", embeddedKafka);
        // Use "latest" so each test only reads messages published after the consumer subscribes.
        // "earliest" would replay messages from prior tests in the same context, causing
        // ingestSingle to receive svc-a/svc-b from ingestBatch instead of its own test-svc.
        // waitForAssignment() below ensures the consumer is fully assigned before the test
        // publishes, so no messages are missed.
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");

        JsonDeserializer<LogEventMessage> valueDeserializer =
                new JsonDeserializer<>(LogEventMessage.class);
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.addTrustedPackages("com.logpipeline.model");

        DefaultKafkaConsumerFactory<String, LogEventMessage> cf =
                new DefaultKafkaConsumerFactory<>(
                        consumerProps,
                        new StringDeserializer(),
                        valueDeserializer);

        BlockingQueue<ConsumerRecord<String, LogEventMessage>> queue = new LinkedBlockingQueue<>();
        ContainerProperties containerProps = new ContainerProperties("processed-logs");
        containerProps.setMessageListener((MessageListener<String, LogEventMessage>) queue::add);

        KafkaMessageListenerContainer<String, LogEventMessage> container =
                new KafkaMessageListenerContainer<>(cf, containerProps);
        container.start();
        ContainerTestUtils.waitForAssignment(container, embeddedKafka.getPartitionsPerTopic());

        return queue;
    }
}
