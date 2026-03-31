package com.logpipeline.service;

import com.logpipeline.model.AnomalyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DetectorService — focuses on orchestration concerns:
 * payload parsing, Kafka publishing, per-service isolation, and error handling.
 *
 * Detection logic is tested exhaustively in ZScoreSpikeDetectorTest.
 *
 * Configuration: windowSize=15, signalWindowSize=5, zScoreThreshold=2.0
 *   → baseline = 10 events, signal = 5 events
 *
 * Trigger recipe used throughout:
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

    DetectorService service;

    @BeforeEach
    void setUp() {
        service = new DetectorService(kafkaTemplate, TOPIC, WINDOW, SIGNAL, Z_THRESH);
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
        // Send WINDOW-1 events — never reaches full window
        for (int i = 0; i < WINDOW - 1; i++) process("svc", "ERROR");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Noisy service matching its baseline Z-score=0 — no publish")
    void process_noisyServiceMatchingBaseline_noPublish() {
        // 6 ERROR + 4 INFO baseline → 60%
        // 3 ERROR + 2 INFO signal  → 60%
        // Z = (0.6 - 0.6) / stdDev = 0.0 → no fire
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
        // 10 INFO baseline + 5 ERROR signal → Z ≈ 100 → fires
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
    @DisplayName("Published AnomalyEvent carries correct serviceId, threshold, and non-null fields")
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
    }

    // ── Per-service detector isolation ────────────────────────────────────────

    @Test
    @DisplayName("Different serviceIds maintain independent detector state")
    void process_differentServices_independentDetectors() {
        // svc-a: clean baseline + full spike → fires
        processN("svc-a", "INFO",  10);
        processN("svc-a", "ERROR", 5);

        // svc-b: only INFO events → never fires
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

    // ── Timestamp parsing ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ISO-8601 timestamp string is parsed without error")
    void process_timestampAsIsoString_parsedSuccessfully() {
        Map<String, Object> p = payload("svc", "INFO");
        p.put("timestamp", "2024-06-15T09:30:00Z");
        service.process(p);
    }

    @Test
    @DisplayName("Epoch-millis timestamp (Number) is parsed without error")
    void process_timestampAsEpochMillis_parsedSuccessfully() {
        Map<String, Object> p = payload("svc", "INFO");
        p.put("timestamp", System.currentTimeMillis());
        service.process(p);
    }

    @Test
    @DisplayName("Missing timestamp falls back to Instant.now() without error")
    void process_missingTimestamp_fallsBackToNow() {
        Map<String, Object> p = payload("svc", "INFO");
        p.remove("timestamp");
        service.process(p);
    }

    // ── Validation / error handling ───────────────────────────────────────────

    @Test
    @DisplayName("Null level field throws IllegalArgumentException and is re-thrown")
    void process_nullLevel_throwsAndRethrows() {
        Map<String, Object> p = payload("svc", "INFO");
        p.put("level", null);

        assertThatThrownBy(() -> service.process(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level");
    }

    @Test
    @DisplayName("Unknown level string throws IllegalArgumentException with the bad value in message")
    void process_unknownLevel_throwsAndRethrows() {
        Map<String, Object> p = payload("svc", "INFO");
        p.put("level", "VERBOSE");

        assertThatThrownBy(() -> service.process(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VERBOSE");
    }

    @Test
    @DisplayName("Missing level key throws and is re-thrown to DLT handler")
    void process_missingLevelKey_throwsAndRethrows() {
        Map<String, Object> p = new HashMap<>();
        p.put("serviceId", "svc");

        assertThatThrownBy(() -> service.process(p))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Exception in one call does not corrupt detector state for subsequent calls")
    void process_exceptionInOneCall_doesNotCorruptSubsequentCalls() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("serviceId", "svc");
        bad.put("level", null);

        try { service.process(bad); } catch (Exception ignored) {}

        // Subsequent valid calls proceed normally — full window of INFO, no anomaly
        for (int i = 0; i < WINDOW; i++) process("svc", "INFO");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void process(String serviceId, String level) {
        service.process(payload(serviceId, level));
    }

    private void processN(String serviceId, String level, int n) {
        for (int i = 0; i < n; i++) process(serviceId, level);
    }

    private Map<String, Object> payload(String serviceId, String level) {
        Map<String, Object> m = new HashMap<>();
        m.put("eventId",           UUID.randomUUID().toString());
        m.put("serviceId",         serviceId);
        m.put("level",             level);
        m.put("message",           "test message");
        m.put("timestamp",         Instant.now().toString());
        m.put("normalizedMessage", "test message");
        return m;
    }
}
