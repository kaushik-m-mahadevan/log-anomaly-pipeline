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

    @Bean
    public NewTopic anomalyAlertsTopic() {
        return TopicBuilder.name(anomalyAlertsTopic)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic processedLogsDlt() {
        return TopicBuilder.name(processedLogsDltTopic + "-dlt")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
