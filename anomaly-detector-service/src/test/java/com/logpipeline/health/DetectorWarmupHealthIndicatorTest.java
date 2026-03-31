package com.logpipeline.health;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.LogEventMessage;
import com.logpipeline.service.DetectorService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DetectorWarmupHealthIndicatorTest {

    private static final int WINDOW  = 5;
    private static final int SIGNAL  = 2;
    private static final double Z    = 2.0;

    @Mock KafkaTemplate<String, AnomalyEvent> kafkaTemplate;

    DetectorService detectorService;
    DetectorWarmupHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        detectorService = new DetectorService(
                kafkaTemplate, "anomaly-alerts", WINDOW, SIGNAL, Z, new SimpleMeterRegistry());
        indicator = new DetectorWarmupHealthIndicator(detectorService);
        lenient().when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(new CompletableFuture<>());
    }

    @Test
    @DisplayName("Reports OUT_OF_SERVICE when no events have been received yet")
    void health_noEventsYet_outOfService() {
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(health.getDetails()).containsKey("status");
        assertThat(health.getDetails().get("status")).isEqualTo("warming-up");
        assertThat(health.getDetails().get("activeDetectors")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Reports UP once at least one service has sent events")
    void health_afterFirstEvent_up() {
        detectorService.process(infoMessage("svc-a"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("status")).isEqualTo("ready");
        assertThat((Long) health.getDetails().get("activeDetectors")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("Active detector count increases as new services appear")
    void health_multipleServices_countIncreasesCorrectly() {
        detectorService.process(infoMessage("svc-a"));
        detectorService.process(infoMessage("svc-b"));
        detectorService.process(infoMessage("svc-c"));

        Health health = indicator.health();

        assertThat((Long) health.getDetails().get("activeDetectors")).isGreaterThanOrEqualTo(3L);
    }

    @Test
    @DisplayName("UP state includes message-free details (no spurious warning text)")
    void health_upState_noWarningMessage() {
        detectorService.process(infoMessage("svc"));

        Health health = indicator.health();

        assertThat(health.getDetails()).doesNotContainKey("message");
    }

    @Test
    @DisplayName("OUT_OF_SERVICE state includes an explanatory message")
    void health_outOfService_containsHelpfulMessage() {
        Health health = indicator.health();

        assertThat(health.getDetails()).containsKey("message");
        assertThat(health.getDetails().get("message").toString()).contains("warming-up");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private LogEventMessage infoMessage(String serviceId) {
        return new LogEventMessage(
                LogEventMessage.CURRENT_VERSION,
                UUID.randomUUID().toString(),
                serviceId, "INFO", "test", Instant.now(), "test");
    }
}
