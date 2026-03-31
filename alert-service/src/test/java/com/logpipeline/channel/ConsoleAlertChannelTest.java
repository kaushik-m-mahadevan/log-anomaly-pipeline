package com.logpipeline.channel;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.AnomalySeverity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ConsoleAlertChannelTest {

    private final ConsoleAlertChannel channel = new ConsoleAlertChannel();

    @Test
    void channelName_returnsConsole() {
        assertThat(channel.channelName()).isEqualTo("console");
    }

    @Test
    void send_doesNotThrow_forValidEvent() {
        assertThatNoException().isThrownBy(() -> channel.send(anomaly(AnomalySeverity.CRITICAL)));
    }

    @Test
    void send_doesNotThrow_forAllSeverityLevels() {
        for (AnomalySeverity severity : AnomalySeverity.values()) {
            assertThatNoException().isThrownBy(() -> channel.send(anomaly(severity)));
        }
    }

    @Test
    void send_doesNotThrow_whenDescriptionIsLong() {
        AnomalyEvent event = new AnomalyEvent(
                AnomalyEvent.CURRENT_VERSION,
                UUID.randomUUID().toString(),
                "svc",
                AnomalySeverity.HIGH,
                "A".repeat(500),
                0.9,
                0.5,
                Instant.now()
        );
        assertThatNoException().isThrownBy(() -> channel.send(event));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AnomalyEvent anomaly(AnomalySeverity severity) {
        return new AnomalyEvent(
                AnomalyEvent.CURRENT_VERSION,
                UUID.randomUUID().toString(),
                "test-service",
                severity,
                "Error rate spike detected: 75.0% of last 10 events",
                0.75,
                0.5,
                Instant.now()
        );
    }
}
