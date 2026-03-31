package com.logpipeline.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.logpipeline.model.LogEventMessage;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, LogEventMessage> producerFactory() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonSerializer<LogEventMessage> serializer = new JsonSerializer<>(mapper);
        serializer.setAddTypeInfo(false); // keep messages schema-agnostic

        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,    bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,   true,   // exactly-once producer semantics
                ProducerConfig.ACKS_CONFIG,                 "all",  // wait for all ISR replicas
                ProducerConfig.RETRIES_CONFIG,              3,
                ProducerConfig.LINGER_MS_CONFIG,            5,       // micro-batching for throughput
                // Item 7: prevent Tomcat threads from hanging indefinitely on a slow broker
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,   10_000, // 10 s per request attempt
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,  30_000  // 30 s total (must be ≥ request × retries)
        ), new org.apache.kafka.common.serialization.StringSerializer(), serializer);
    }

    @Bean
    public KafkaTemplate<String, LogEventMessage> kafkaTemplate(
            ProducerFactory<String, LogEventMessage> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
