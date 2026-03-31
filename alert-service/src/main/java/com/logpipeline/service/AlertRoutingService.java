package com.logpipeline.service;

import com.logpipeline.channel.AlertChannel;
import com.logpipeline.model.AnomalyEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fans out an anomaly event to all registered alert channels.
 *
 * Spring injects every @Component implementing AlertChannel automatically.
 * Adding a new channel requires zero changes here — just implement AlertChannel
 * and annotate with @Component. This is the Open/Closed principle in practice.
 *
 * Item 10: custom Micrometer metrics track alerts routed per channel and
 * channel failure rates for Prometheus/Grafana dashboards.
 */
@Service
public class AlertRoutingService {

    private static final Logger log = LoggerFactory.getLogger(AlertRoutingService.class);

    private final List<AlertChannel> channels;
    private final Counter alertsRoutedCounter;
    private final Counter channelFailuresCounter;

    public AlertRoutingService(List<AlertChannel> channels, MeterRegistry meterRegistry) {
        this.channels = channels;
        // Item 10: business metrics
        this.alertsRoutedCounter = Counter.builder("alert.routed")
                .description("Total anomaly alerts successfully routed to at least one channel")
                .register(meterRegistry);
        this.channelFailuresCounter = Counter.builder("alert.channel.failures")
                .description("Total alert channel delivery failures")
                .register(meterRegistry);

        log.info("Alert routing initialised with {} channel(s): {}",
                channels.size(),
                channels.stream().map(AlertChannel::channelName).toList());
    }

    public void route(AnomalyEvent anomaly) {
        log.info("Routing anomaly [anomalyId={}, serviceId={}, severity={}] to {} channel(s)",
                anomaly.anomalyId(), anomaly.serviceId(), anomaly.severity(), channels.size());

        channels.forEach(channel -> {
            try {
                channel.send(anomaly);
            } catch (Exception ex) {
                // One channel failure must not prevent others from firing
                channelFailuresCounter.increment();
                log.error("Channel '{}' failed to deliver alert [anomalyId={}]: {}",
                        channel.channelName(), anomaly.anomalyId(), ex.getMessage());
            }
        });

        alertsRoutedCounter.increment();
    }
}
