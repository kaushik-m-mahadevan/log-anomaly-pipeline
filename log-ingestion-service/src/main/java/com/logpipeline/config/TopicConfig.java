package com.logpipeline.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class TopicConfig {

    @Value("${kafka.topics.processed-logs}")
    private String processedLogsTopic;

    /**
     * Replication factor is parameterised (Item 8) so staging/prod clusters use 3
     * without requiring a code change. Local dev keeps 1 via the default.
     *
     * Partition count of 3 allows up to 3 parallel detector consumer instances
     * without rebalancing overhead.
     */
    @Value("${kafka.replication-factor:1}")
    private int replicationFactor;

    @Bean
    public NewTopic processedLogsTopic() {
        return TopicBuilder.name(processedLogsTopic)
                .partitions(3)
                .replicas(replicationFactor)
                .build();
    }
}
