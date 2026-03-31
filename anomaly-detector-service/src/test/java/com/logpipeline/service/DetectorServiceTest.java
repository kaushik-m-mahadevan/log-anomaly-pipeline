package com.logpipeline.service;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.LogEventMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DetectorService — focuses on orchestration concerns:
 * payload parsing, Kafka publishing, per-service isolation, idempotency, and error handling.
 *
 * Detection logic is tested exhaustively in ZScoreSpikeDetectorTest.
 *
 * Configuration: windowSize=15, signalWindowSize=5, zScoreThreshold=2.0
 *   → baseline = 10 events, signal = 5 events
 *
 * Trigger recipe:
 *   10 × INFO  (baseline → 0% errors)
 *   5  × ERROR (signal  → 100% errors)
 *   Z = (1.0 − 0.0) / MIN_STD_DEV(0.01) = 100  →  well above threshold
 */
@ExtendWith(MockitoExtension.class)
class DetectorServiceTest {

    private static final String TOPIC    = "anomaly-alerts";
    private static final int    WINDOW   = 15;
    private static final int    SIGNAL   = 5;
    private static final double Z_THRESH = 2.0;

    @Mock KafkaTemplate<String, AnomalyEvent> kafkaTemplate;

    private SimpleMeterRegistry meterRegistry;
    DetectorService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new DetectorService(kafkaTemplate, TOPIC, WINDOW, SIGNAL, Z_THRESH, meterRegistry);
        lenient().when(kafkaTemplate.send(anyString(), anyString(), any(AnomalyEvent.class)))
                .thenReturn(new CompletableFuture<>());
    }

    // ── No-anomaly paths ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Full window of INFO events produces no Kafka publish")
    void process_allInfoEvents_neverPublish() {
        for (int i = 0; i < WINDOW; i++) process("svc", "INFO");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Window not yet full — no anomaly even if all errors")
    void process_windowNotFull_noAnomalyYet() {
        for (int i = 0; i < WINDOW - 1; i++) process("svc", "ERROR");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Noisy service matching its baseline Z-score=0 — no publish")
    void process_noisyServiceMatchingBaseline_noPublish() {
        processN("svc", "ERROR", 6);
        processN("svc", "INFO",  4);
        processN("svc", "ERROR", 3);
        processN("svc", "INFO",  2);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    // ── Anomaly detected paths ────────────────────────────────────────────────

    @Test
    @DisplayName("Pristine baseline + full error signal publishes anomaly to correct topic and key")
    void process_pristineBaselineFullSpike_publishesAnomaly() {
        processN("svc", "INFO",  10);
        processN("svc", "ERROR", 5);

        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq("svc"), any(AnomalyEvent.class));
    }

    @Test
    @DisplayName("FATAL events in signal zone trigger anomaly")
    void process_fatalInSignal_triggersAnomaly() {
        processN("svc", "INFO",  10);
        processN("svc", "FATAL", 5);

        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq("svc"), any(AnomalyEvent.class));
    }

    @Test
    @DisplayName("Published AnomalyEvent carries correct serviceId, threshold, schemaVersion, and non-null fields")
    void process_anomalyEvent_hasCorrectFields() {
        processN("payment-service", "INFO",  10);
        processN("payment-service", "ERROR", 5);

        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());

        AnomalyEvent anomaly = captor.getValue();
        assertThat(anomaly.serviceId()).isEqualTo("payment-service");
        assertThat(anomaly.detectedValue()).isGreaterThanOrEqualTo(Z_THRESH);
        assertThat(anomaly.threshold()).isEqualTo(Z_THRESH);
        assertThat(anomaly.anomalyId()).isNotBlank();
        assertThat(anomaly.severity()).isNotNull();
        assertThat(anomaly.description()).isNotBlank();
        assertThat(anomaly.detectedAt()).isNotNull();
        assertThat(anomaly.schemaVersion()).isEqualTo(AnomalyEvent.CURRENT_VERSION);
    }

    // ── Per-service detector isolation ────────────────────────────────────────

    @Test
    @DisplayName("Different serviceIds maintain independent detector state")
    void process_differentServices_independentDetectors() {
        processN("svc-a", "INFO",  10);
        processN("svc-a", "ERROR", 5);

        processN("svc-b", "INFO", 15);

        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq("svc-a"), any());
        verify(kafkaTemplate, never()).send(eq(TOPIC), eq("svc-b"), any());
    }

    @Test
    @DisplayName("Two spiking services each publish exactly one anomaly independently")
    void process_twoSpikingServices_eachPublishOnce() {
        processN("svc-a", "INFO",  10);
        processN("svc-a", "ERROR", 5);

        processN("svc-b", "INFO",  10);
        processN("svc-b", "ERROR", 5);

        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq("svc-a"), any());
        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq("svc-b"), any());
    }

    // ── Idempotency (Item 4) ──────────────────────────────────────────────────

    @Test
    @DisplayName("Duplicate eventId is dropped — Kafka is not called twice for the same event")
    void process_duplicateEventId_droppedSecondTime() {
        String sharedId = UUID.randomUUID().toString();
        LogEventMessage first  = message("svc", "INFO", sharedId);
        LogEventMessage second = message("svc", "INFO", sharedId);

        // Two calls with same eventId — only one should reach the detector
        service.process(first);
        service.process(second);

        // Neither causes a publish (window not full), but we can verify the
        // duplicate-drop counter was incremented
        assertThat(meterRegistry.counter("detector.events.duplicates.dropped").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Events with null eventId are not deduplicated and all pass through")
    void process_nullEventId_notDeduped() {
        // Two events with null eventId should both be processed (no dedup on null)
        service.process(message("svc", "INFO", null));
        service.process(message("svc", "INFO", null));

        assertThat(meterRegistry.counter("detector.events.duplicates.dropped").count())
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("Distinct eventIds are each processed — no false deduplication")
    void process_distinctEventIds_allProcessed() {
        for (int i = 0; i < WINDOW; i++) {
            service.process(message("svc", "INFO", UUID.randomUUID().toString()));
        }

        assertThat(meterRegistry.counter("detector.events.duplicates.dropped").count())
                .isEqualTo(0.0);
    }

    // ── Metrics (Item 10) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Detected anomaly increments the anomalies-detected counter")
    void process_anomalyDetected_incrementsCounter() {
        processN("svc", "INFO",  10);
        processN("svc", "ERROR", 5);

        assertThat(meterRegistry.counter("detector.anomalies.detected").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("Registry size gauge reflects number of active service detectors")
    void registrySize_reflectsActiveDetectors() {
        processN("svc-a", "INFO", 1);
        processN("svc-b", "INFO", 1);

        assertThat(service.registrySize()).isGreaterThanOrEqualTo(2L);
    }

    // ── Timestamp parsing ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Provided timestamp is passed through correctly")
    void process_providedTimestamp_parsedSuccessfully() {
        LogEventMessage msg = new LogEventMessage(
                LogEventMessage.CURRENT_VERSION,
                UUID.randomUUID().toString(), "svc", "INFO",
                "test", Instant.parse("2024-06-15T09:30:00Z"), "test");
        service.process(msg); // must not throw
    }

    @Test
    @DisplayName("Null timestamp is handled gracefully (falls back to Instant.now)")
    void process_nullTimestamp_handledGracefully() {
        LogEventMessage msg = new LogEventMessage(
                LogEventMessage.CURRENT_VERSION,
                UUID.randomUUID().toString(), "svc", "INFO",
                "test", null, "test");
        service.process(msg); // must not throw
    }

    // ── Validation / error handling ───────────────────────────────────────────

    @Test
    @DisplayName("Null level field throws IllegalArgumentException and is re-thrown")
    void process_nullLevel_throwsAndRethrows() {
        LogEventMessage msg = new LogEventMessage(
                LogEventMessage.CURRENT_VERSION,
                UUID.randomUUID().toString(), "svc", null,
                "test", Instant.now(), "test");

        assertThatThrownBy(() -> service.process(msg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level");
    }

    @Test
    @DisplayName("Unknown level string throws IllegalArgumentException with the bad value in message")
    void process_unknownLevel_throwsAndRethrows() {
        LogEventMessage msg = new LogEventMessage(
                LogEventMessage.CURRENT_VERSION,
                UUID.randomUUID().toString(), "svc", "VERBOSE",
                "test", Instant.now(), "test");

        assertThatThrownBy(() -> service.process(msg))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VERBOSE");
    }

    @Test
    @DisplayName("Exception in one call does not corrupt detector state for subsequent calls")
    void process_exceptionInOneCall_doesNotCorruptSubsequentCalls() {
        LogEventMessage bad = new LogEventMessage(
                LogEventMessage.CURRENT_VERSION,
                UUID.randomUUID().toString(), "svc", null,
                "test", Instant.now(), "test");

        try { service.process(bad); } catch (Exception ignored) {}

        for (int i = 0; i < WINDOW; i++) process("svc", "INFO");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void process(String serviceId, String level) {
        service.process(message(serviceId, level, UUID.randomUUID().toString()));
    }

    private void processN(String serviceId, String level, int n) {
        for (int i = 0; i < n; i++) process(serviceId, level);
    }

    private LogEventMessage message(String serviceId, String level, String eventId) {
        return new LogEventMessage(
                LogEventMessage.CURRENT_VERSION,
                eventId,
                serviceId,
                level,
                "test message",
                Instant.now(),
                "test message"
        );
    }
}
