package com.logpipeline.service;

import com.logpipeline.channel.AlertChannel;
import com.logpipeline.model.AnomalyEvent;
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
 */
@Service
public class AlertRoutingService {

    private static final Logger log = LoggerFactory.getLogger(AlertRoutingService.class);

    private final List<AlertChannel> channels;

    public AlertRoutingService(List<AlertChannel> channels) {
        this.channels = channels;
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
                log.error("Channel '{}' failed to deliver alert [anomalyId={}]: {}",
                        channel.channelName(), anomaly.anomalyId(), ex.getMessage());
            }
        });
    }
}
