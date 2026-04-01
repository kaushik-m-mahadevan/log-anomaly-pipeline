package com.logpipeline.channel;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.AnomalySeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackAlertChannelTest {

    private static final String WEBHOOK_URL = "https://hooks.slack.com/services/TEST/FAKE/URL";

    @Mock
    private HttpClient httpClient;

    @Mock
    @SuppressWarnings("rawtypes")
    private HttpResponse httpResponse;

    private SlackAlertChannel channel;

    @BeforeEach
    void setUp() {
        channel = new SlackAlertChannel(WEBHOOK_URL, httpClient);
    }

    @Test
    @DisplayName("channelName() returns 'slack'")
    void channelNameIsSlack() {
        assertThat(channel.channelName()).isEqualTo("slack");
    }

    @Test
    @DisplayName("send() POSTs to webhook and acknowledges on HTTP 200")
    @SuppressWarnings("unchecked")
    void sendPostsToWebhookOnSuccess() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);

        channel.send(anomalyEvent(AnomalySeverity.HIGH));

        verify(httpClient).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("send() logs error but does NOT throw on non-200 response")
    @SuppressWarnings("unchecked")
    void sendLogsErrorOnNon200() throws Exception {
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal Server Error");
        when(httpClient.send(any(HttpRequest.class), any())).thenReturn(httpResponse);

        // non-200 is logged but does not propagate — offset will still be committed
        channel.send(anomalyEvent(AnomalySeverity.MEDIUM));

        verify(httpClient).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("send() wraps IOException and re-throws so offset is not committed")
    @SuppressWarnings("unchecked")
    void sendRethrowsOnNetworkError() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any()))
                .thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> channel.send(anomalyEvent(AnomalySeverity.CRITICAL)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Slack alert delivery failed")
                .hasCauseInstanceOf(IOException.class);
    }

    // --- buildPayload tests (package-private method, exercised directly) ---

    @Test
    @DisplayName("buildPayload contains service ID, description, and severity")
    void payloadContainsKeyFields() {
        AnomalyEvent event = anomalyEvent(AnomalySeverity.HIGH);
        String payload = channel.buildPayload(event);

        assertThat(payload).contains(event.serviceId());
        assertThat(payload).contains(event.description());
        assertThat(payload).contains("HIGH");
    }

    @Test
    @DisplayName("buildPayload uses CRITICAL color #FF0000")
    void payloadColorCritical() {
        assertThat(channel.buildPayload(anomalyEvent(AnomalySeverity.CRITICAL)))
                .contains("#FF0000");
    }

    @Test
    @DisplayName("buildPayload uses HIGH color #FF6600")
    void payloadColorHigh() {
        assertThat(channel.buildPayload(anomalyEvent(AnomalySeverity.HIGH)))
                .contains("#FF6600");
    }

    @Test
    @DisplayName("buildPayload uses MEDIUM color #FFB300")
    void payloadColorMedium() {
        assertThat(channel.buildPayload(anomalyEvent(AnomalySeverity.MEDIUM)))
                .contains("#FFB300");
    }

    @Test
    @DisplayName("buildPayload uses LOW color #36A64F")
    void payloadColorLow() {
        assertThat(channel.buildPayload(anomalyEvent(AnomalySeverity.LOW)))
                .contains("#36A64F");
    }

    @Test
    @DisplayName("buildPayload escapes double-quotes in description to prevent JSON breakage")
    void payloadEscapesQuotesInDescription() {
        AnomalyEvent event = new AnomalyEvent(
                AnomalyEvent.CURRENT_VERSION,
                UUID.randomUUID().toString(),
                "svc-test",
                AnomalySeverity.LOW,
                "error rate \"spiked\" above threshold",
                0.85, 0.50,
                Instant.now()
        );
        String payload = channel.buildPayload(event);
        assertThat(payload).contains("\\\"spiked\\\"");
    }

    @Test
    @DisplayName("severityColor returns correct hex for each severity level")
    void severityColorMapping() {
        assertThat(SlackAlertChannel.severityColor(AnomalySeverity.CRITICAL)).isEqualTo("#FF0000");
        assertThat(SlackAlertChannel.severityColor(AnomalySeverity.HIGH)).isEqualTo("#FF6600");
        assertThat(SlackAlertChannel.severityColor(AnomalySeverity.MEDIUM)).isEqualTo("#FFB300");
        assertThat(SlackAlertChannel.severityColor(AnomalySeverity.LOW)).isEqualTo("#36A64F");
    }

    @Test
    @DisplayName("severityTitle always includes the serviceId")
    void severityTitleContainsServiceId() {
        for (AnomalySeverity s : AnomalySeverity.values()) {
            assertThat(SlackAlertChannel.severityTitle(s, "my-service"))
                    .contains("my-service");
        }
    }

    @Test
    @DisplayName("buildPayload title contains the serviceId from severityTitle")
    void payloadTitleContainsServiceId() {
        AnomalyEvent event = anomalyEvent(AnomalySeverity.CRITICAL);
        String payload = channel.buildPayload(event);
        assertThat(payload).contains(event.serviceId());
    }

    // -----------------------------------------------------------------------

    private static AnomalyEvent anomalyEvent(AnomalySeverity severity) {
        return new AnomalyEvent(
                AnomalyEvent.CURRENT_VERSION,
                UUID.randomUUID().toString(),
                "order-service",
                severity,
                "error rate exceeded threshold",
                0.75, 0.40,
                Instant.now()
        );
    }
}
