package com.logpipeline.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.logpipeline.model.AnomalyEvent;
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
    public ProducerFactory<String, AnomalyEvent> anomalyProducerFactory() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonSerializer<AnomalyEvent> serializer = new JsonSerializer<>(mapper);
        serializer.setAddTypeInfo(false);

        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,    bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,   true,
                ProducerConfig.ACKS_CONFIG,                 "all",
                // Item 7: prevent Kafka listener threads from hanging indefinitely on a slow broker
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,   10_000, // 10 s per attempt
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,  30_000  // 30 s total
        ), new StringSerializer(), serializer);
    }

    @Bean
    public KafkaTemplate<String, AnomalyEvent> anomalyKafkaTemplate(
            ProducerFactory<String, AnomalyEvent> anomalyProducerFactory) {
        return new KafkaTemplate<>(anomalyProducerFactory);
    }
}
