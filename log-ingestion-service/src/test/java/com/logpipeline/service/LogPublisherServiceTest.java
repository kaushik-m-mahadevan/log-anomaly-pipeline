package com.logpipeline.service;

import com.logpipeline.dto.LogEventRequest;
import com.logpipeline.model.LogEventMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogPublisherServiceTest {

    private static final String TOPIC = "processed-logs";

    @Mock(lenient = true) KafkaTemplate<String, LogEventMessage> kafkaTemplate;

    private SimpleMeterRegistry meterRegistry;
    LogPublisherService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new LogPublisherService(kafkaTemplate, TOPIC, meterRegistry);
        when(kafkaTemplate.send(anyString(), anyString(), any(LogEventMessage.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void publishBatch_sendsOneMessagePerRequest() {
        List<LogEventRequest> requests = List.of(
                new LogEventRequest("svc-a", "INFO",  "msg1", null),
                new LogEventRequest("svc-b", "ERROR", "msg2", null),
                new LogEventRequest("svc-c", "WARN",  "msg3", null)
        );

        service.publishBatch(requests);

        verify(kafkaTemplate, times(3)).send(anyString(), anyString(), any(LogEventMessage.class));
    }

    @Test
    void publishBatch_usesServiceIdAsPartitionKey() {
        String serviceId = "my-service";
        service.publishBatch(List.of(new LogEventRequest(serviceId, "INFO", "msg", null)));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), keyCaptor.capture(), any(LogEventMessage.class));
        assertThat(keyCaptor.getValue()).isEqualTo(serviceId);
    }

    @Test
    void publishBatch_publishesToCorrectTopic() {
        service.publishBatch(List.of(new LogEventRequest("svc", "INFO", "msg", null)));

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topicCaptor.capture(), anyString(), any(LogEventMessage.class));
        assertThat(topicCaptor.getValue()).isEqualTo(TOPIC);
    }

    @Test
    void publishBatch_normalizesMessage_trimmedAndLowercase() {
        service.publishBatch(List.of(new LogEventRequest("svc", "INFO", "  Hello World  ", null)));

        ArgumentCaptor<LogEventMessage> msgCaptor = ArgumentCaptor.forClass(LogEventMessage.class);
        verify(kafkaTemplate).send(anyString(), anyString(), msgCaptor.capture());

        LogEventMessage sent = msgCaptor.getValue();
        assertThat(sent.normalizedMessage()).isEqualTo("hello world");
        assertThat(sent.message()).isEqualTo("  Hello World  ");
    }

    @Test
    void publishBatch_assignsUniqueEventIds_toEachMessage() {
        List<LogEventRequest> requests = List.of(
                new LogEventRequest("svc", "INFO", "msg1", null),
                new LogEventRequest("svc", "INFO", "msg2", null),
                new LogEventRequest("svc", "INFO", "msg3", null)
        );

        service.publishBatch(requests);

        ArgumentCaptor<LogEventMessage> captor = ArgumentCaptor.forClass(LogEventMessage.class);
        verify(kafkaTemplate, times(3)).send(anyString(), anyString(), captor.capture());

        Set<String> ids = captor.getAllValues().stream()
                .map(LogEventMessage::eventId)
                .collect(Collectors.toSet());
        assertThat(ids).hasSize(3);
    }

    @Test
    void publishBatch_usesProvidedTimestamp() {
        Instant fixed = Instant.parse("2024-01-15T10:00:00Z");
        service.publishBatch(List.of(new LogEventRequest("svc", "INFO", "msg", fixed)));

        ArgumentCaptor<LogEventMessage> captor = ArgumentCaptor.forClass(LogEventMessage.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());
        assertThat(captor.getValue().timestamp()).isEqualTo(fixed);
    }

    @Test
    void publishBatch_returnsOneFuturePerRequest() {
        List<LogEventRequest> requests = List.of(
                new LogEventRequest("svc-a", "INFO", "msg1", null),
                new LogEventRequest("svc-b", "INFO", "msg2", null)
        );

        List<?> futures = service.publishBatch(requests);

        assertThat(futures).hasSize(2);
    }

    @Test
    void publishBatch_emptyList_sendsNothing() {
        service.publishBatch(List.of());

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    void publishBatch_preservesLevelFromRequest() {
        service.publishBatch(List.of(new LogEventRequest("svc", "FATAL", "msg", null)));

        ArgumentCaptor<LogEventMessage> captor = ArgumentCaptor.forClass(LogEventMessage.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());
        assertThat(captor.getValue().level()).isEqualTo("FATAL");
    }

    @Test
    void publishBatch_setsSchemaVersion_onEachMessage() {
        service.publishBatch(List.of(new LogEventRequest("svc", "INFO", "msg", null)));

        ArgumentCaptor<LogEventMessage> captor = ArgumentCaptor.forClass(LogEventMessage.class);
        verify(kafkaTemplate).send(anyString(), anyString(), captor.capture());
        assertThat(captor.getValue().schemaVersion()).isEqualTo(LogEventMessage.CURRENT_VERSION);
    }
}
