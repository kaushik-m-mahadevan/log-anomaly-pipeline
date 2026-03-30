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
     * Declares the topic if it does not already exist.
     * Partition count of 3 allows up to 3 parallel detector consumer instances
     * without rebalancing overhead; replication factor of 1 is appropriate for local dev.
     */
    @Bean
    public NewTopic processedLogsTopic() {
        return TopicBuilder.name(processedLogsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
