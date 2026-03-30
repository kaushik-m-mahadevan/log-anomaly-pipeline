package com.logpipeline.channel;

import com.logpipeline.model.AnomalyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MVP alert channel — structured console output.
 * Replace or supplement with email/SMS/webhook implementations later.
 */
@Component
public class ConsoleAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(ConsoleAlertChannel.class);

    @Override
    public void send(AnomalyEvent anomaly) {
        log.warn("""
                \n╔══════════════════════════════════════════════════════╗
                ║                  ANOMALY DETECTED                   ║
                ╠══════════════════════════════════════════════════════╣
                ║  Anomaly ID  : {}
                ║  Service     : {}
                ║  Severity    : {}
                ║  Description : {}
                ║  Detected At : {}
                ╚══════════════════════════════════════════════════════╝""",
                anomaly.anomalyId(),
                anomaly.serviceId(),
                anomaly.severity(),
                anomaly.description(),
                anomaly.detectedAt()
        );
    }

    @Override
    public String channelName() {
        return "console";
    }
}
