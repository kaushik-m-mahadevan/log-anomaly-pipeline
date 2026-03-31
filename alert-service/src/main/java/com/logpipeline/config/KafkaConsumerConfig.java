package com.logpipeline.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.logpipeline.model.AnomalyEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Bean
    public ConsumerFactory<String, AnomalyEvent> consumerFactory() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonDeserializer<AnomalyEvent> deserializer =
                new JsonDeserializer<>(AnomalyEvent.class, mapper);
        deserializer.addTrustedPackages("com.logpipeline.model");
        deserializer.setUseTypeHeaders(false);

        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,      bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,               groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,      "earliest",
                // Item 5: disable auto-commit so an alert channel failure doesn't silently drop messages
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,     false
        ), new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AnomalyEvent>
            alertKafkaListenerContainerFactory(
                    ConsumerFactory<String, AnomalyEvent> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, AnomalyEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // Item 5: MANUAL_IMMEDIATE — offset committed only when the listener calls ack.acknowledge().
        // AckMode.RECORD is an automatic mode and does not inject the Acknowledgment parameter.
        // MANUAL_IMMEDIATE commits the offset instantly on ack.acknowledge(), which is what we want:
        // each record is committed right after route() succeeds, not batched with others.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
