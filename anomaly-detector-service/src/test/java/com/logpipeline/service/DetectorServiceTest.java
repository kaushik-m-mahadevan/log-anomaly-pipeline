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

@ExtendWith(MockitoExtension.class)
class DetectorServiceTest {

    private static final String TOPIC    = "anomaly-alerts";
    private static final int    WINDOW   = 3;
    private static final double THRESHOLD = 0.5; // 50% — 2/3 errors triggers

    @Mock KafkaTemplate<String, AnomalyEvent> kafkaTemplate;

    DetectorService service;

    @BeforeEach
    void setUp() {
        service = new DetectorService(kafkaTemplate, TOPIC, WINDOW, THRESHOLD);
        lenient().when(kafkaTemplate.send(anyString(), anyString(), any(AnomalyEvent.class)))
                .thenReturn(new CompletableFuture<>());
    }

    // ── No-anomaly paths ──────────────────────────────────────────────────────

    @Test
    @DisplayName("INFO events below threshold produce no Kafka publish")
    void process_infoEvents_neverPublish() {
        process("svc", "INFO");
        process("svc", "INFO");
        process("svc", "INFO");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Single error in window of 3 stays below 50% threshold")
    void process_singleError_belowThreshold_noPublish() {
        process("svc", "ERROR");
        process("svc", "INFO");
        process("svc", "INFO");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Window not yet full — no anomaly even if all errors")
    void process_windowNotFull_noAnomalyYet() {
        // window = 3, only 2 events sent
        process("svc", "ERROR");
        process("svc", "ERROR");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    // ── Anomaly detected paths ────────────────────────────────────────────────

    @Test
    @DisplayName("2 errors in window of 3 (66%) exceeds 50% threshold — publishes anomaly")
    void process_errorsAboveThreshold_publishesAnomaly() {
        process("svc", "ERROR");
        process("svc", "ERROR");
        process("svc", "INFO"); // 2/3 = 66% ≥ 50%

        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq("svc"), any(AnomalyEvent.class));
    }

    @Test
    @DisplayName("All FATAL events in full window triggers anomaly")
    void process_allFatal_triggersAnomaly() {
        process("svc", "FATAL");
        process("svc", "FATAL");
        process("svc", "FATAL");

        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq("svc"), any(AnomalyEvent.class));
    }

    @Test
    @DisplayName("Published anomaly carries correct serviceId and rate fields")
    void process_anomalyEvent_hasCorrectFields() {
        process("payment-service", "ERROR");
        process("payment-service", "ERROR");
        process("payment-service", "INFO");

        ArgumentCaptor<AnomalyEvent> captor = ArgumentCaptor.forClass(AnomalyEvent.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());

        AnomalyEvent anomaly = captor.getValue();
        assertThat(anomaly.serviceId()).isEqualTo("payment-service");
        assertThat(anomaly.detectedValue()).isGreaterThanOrEqualTo(THRESHOLD);
        assertThat(anomaly.threshold()).isEqualTo(THRESHOLD);
        assertThat(anomaly.anomalyId()).isNotBlank();
        assertThat(anomaly.detectedAt()).isNotNull();
    }

    // ── Per-service detector isolation ────────────────────────────────────────

    @Test
    @DisplayName("Different serviceIds maintain independent sliding windows")
    void process_differentServices_independentDetectors() {
        // svc-a gets 2 errors → anomaly
        process("svc-a", "ERROR");
        process("svc-a", "ERROR");
        process("svc-a", "INFO"); // 2/3 = 66%

        // svc-b only gets 1 error → no anomaly
        process("svc-b", "ERROR");
        process("svc-b", "INFO");
        process("svc-b", "INFO"); // 1/3 = 33%

        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq("svc-a"), any());
        verify(kafkaTemplate, never()).send(eq(TOPIC), eq("svc-b"), any());
    }

    // ── Timestamp parsing ─────────────────────────────────────────────────────

    @Test
    @DisplayName("ISO-8601 timestamp string is parsed without error")
    void process_timestampAsIsoString_parsedSuccessfully() {
        Map<String, Object> payload = payload("svc", "INFO");
        payload.put("timestamp", "2024-06-15T09:30:00Z");

        service.process(payload); // should not throw
    }

    @Test
    @DisplayName("Epoch-millis timestamp (Number) is parsed without error")
    void process_timestampAsEpochMillis_parsedSuccessfully() {
        Map<String, Object> payload = payload("svc", "INFO");
        payload.put("timestamp", System.currentTimeMillis());

        service.process(payload); // should not throw
    }

    @Test
    @DisplayName("Missing timestamp falls back to Instant.now() without error")
    void process_missingTimestamp_fallsBackToNow() {
        Map<String, Object> payload = payload("svc", "INFO");
        payload.remove("timestamp");

        service.process(payload); // should not throw
    }

    // ── Validation / error handling ───────────────────────────────────────────

    @Test
    @DisplayName("Null level field throws IllegalArgumentException and is re-thrown")
    void process_nullLevel_throwsAndRethrows() {
        Map<String, Object> payload = payload("svc", "INFO");
        payload.put("level", null);

        assertThatThrownBy(() -> service.process(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("level");
    }

    @Test
    @DisplayName("Unknown level string throws IllegalArgumentException and is re-thrown")
    void process_unknownLevel_throwsAndRethrows() {
        Map<String, Object> payload = payload("svc", "INFO");
        payload.put("level", "VERBOSE");

        assertThatThrownBy(() -> service.process(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VERBOSE");
    }

    @Test
    @DisplayName("Exception is re-thrown after logging — consumer can route to DLT")
    void process_exception_isRethrown() {
        Map<String, Object> badPayload = new HashMap<>();
        badPayload.put("serviceId", "svc");
        // no "level" key at all

        assertThatThrownBy(() -> service.process(badPayload))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("Exception in one call does not corrupt detector state for subsequent calls")
    void process_exceptionInOneCall_doesNotCorruptSubsequentCalls() {
        Map<String, Object> bad = new HashMap<>();
        bad.put("serviceId", "svc");
        bad.put("level", null);

        // First call throws
        try { service.process(bad); } catch (Exception ignored) {}

        // Subsequent valid calls proceed normally
        process("svc", "INFO");
        process("svc", "INFO");
        process("svc", "INFO");

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void process(String serviceId, String level) {
        service.process(payload(serviceId, level));
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
