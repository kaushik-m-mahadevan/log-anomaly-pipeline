package com.logpipeline.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class TopicConfig {

    @Value("${kafka.topics.anomaly-alerts}")
    private String anomalyAlertsTopic;

    @Value("${kafka.topics.processed-logs}")
    private String processedLogsDltTopic;

    /** Item 8: parameterised — set to 3 in staging/prod via KAFKA_REPLICATION_FACTOR env var. */
    @Value("${kafka.replication-factor:1}")
    private int replicationFactor;

    @Bean
    public NewTopic anomalyAlertsTopic() {
        return TopicBuilder.name(anomalyAlertsTopic)
                .partitions(3)
                .replicas(replicationFactor)
                .build();
    }

    @Bean
    public NewTopic processedLogsDlt() {
        return TopicBuilder.name(processedLogsDltTopic + "-dlt")
                .partitions(1)
                .replicas(replicationFactor)
                .build();
    }
}
