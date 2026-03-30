package com.logpipeline.channel;

import com.logpipeline.model.AnomalyEvent;

/**
 * Strategy interface for alert delivery channels.
 *
 * Each channel implementation (console, email, SMS, webhook, PagerDuty)
 * handles one delivery mechanism. AlertRoutingService holds a list of
 * active channels and fans out to all of them.
 *
 * To add a new channel: implement this interface and annotate with @Component.
 * Zero changes required in AlertRoutingService or the Kafka consumer.
 */
public interface AlertChannel {
    void send(AnomalyEvent anomaly);
    String channelName();
}
