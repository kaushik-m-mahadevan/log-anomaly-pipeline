package com.logpipeline.channel;

import com.logpipeline.model.AnomalyEvent;
import com.logpipeline.model.AnomalySeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Alert channel that posts to a Slack Incoming Webhook.
 *
 * Enabled only when {@code slack.webhook-url} is set (non-empty).
 * Uses the Java 21 built-in HttpClient — no extra dependencies required.
 *
 * Message format: one attachment per anomaly with severity-coded color,
 * service/description fields, and detected value vs threshold.
 */
@Component
@ConditionalOnExpression("'${slack.webhook-url:}'.length() > 0")
public class SlackAlertChannel implements AlertChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackAlertChannel.class);

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

    private final String webhookUrl;
    private final HttpClient httpClient;

    public SlackAlertChannel(
            @Value("${slack.webhook-url}") String webhookUrl,
            HttpClient httpClient) {
        this.webhookUrl = webhookUrl;
        this.httpClient = httpClient;
    }

    @Override
    public void send(AnomalyEvent anomaly) {
        String payload = buildPayload(anomaly);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Slack webhook returned non-200 status [status={}, body={}]",
                        response.statusCode(), response.body());
            } else {
                log.debug("Slack alert sent [serviceId={}, severity={}]",
                        anomaly.serviceId(), anomaly.severity());
            }
        } catch (Exception e) {
            log.error("Failed to send Slack alert [serviceId={}, anomalyId={}]",
                    anomaly.serviceId(), anomaly.anomalyId(), e);
            // Re-throw so AlertRoutingService records the channel failure metric
            // and the Kafka offset is NOT committed (MANUAL_IMMEDIATE ack mode)
            throw new RuntimeException("Slack alert delivery failed", e);
        }
    }

    @Override
    public String channelName() {
        return "slack";
    }

    String buildPayload(AnomalyEvent anomaly) {
        String color = severityColor(anomaly.severity());
        String title = severityTitle(anomaly.severity(), anomaly.serviceId());
        // Escape any double-quotes or backslashes in user-facing strings
        String serviceId    = jsonEscape(anomaly.serviceId());
        String description  = jsonEscape(anomaly.description());
        String anomalyId    = jsonEscape(anomaly.anomalyId());

        return """
                {
                  "attachments": [
                    {
                      "color": "%s",
                      "title": "%s",
                      "fields": [
                        { "title": "Service",      "value": "%s", "short": true  },
                        { "title": "Severity",     "value": "%s", "short": true  },
                        { "title": "Description",  "value": "%s", "short": false },
                        { "title": "Detected",     "value": "%.4f (threshold %.4f)", "short": false },
                        { "title": "Anomaly ID",   "value": "%s", "short": false },
                        { "title": "Detected At",  "value": "%s", "short": false }
                      ],
                      "footer": "log-anomaly-pipeline"
                    }
                  ]
                }
                """.formatted(
                color,
                jsonEscape(title),
                serviceId,
                anomaly.severity(),
                description,
                anomaly.detectedValue(), anomaly.threshold(),
                anomalyId,
                anomaly.detectedAt()
        );
    }

    static String severityColor(AnomalySeverity severity) {
        return switch (severity) {
            case CRITICAL -> "#FF0000";
            case HIGH     -> "#FF6600";
            case MEDIUM   -> "#FFB300";
            case LOW      -> "#36A64F";
        };
    }

    static String severityTitle(AnomalySeverity severity, String serviceId) {
        List<String> messages = switch (severity) {
            case CRITICAL -> List.of(
                    ":skull: *%s* has completely lost the plot",
                    ":fire: *%s* is currently on fire. Literally.",
                    ":rotating_light: *%s* said 'hold my beer' and crashed",
                    ":sos: *%s* is having a very bad time right now"
            );
            case HIGH -> List.of(
                    ":warning: *%s* is not doing great, chief",
                    ":grimacing: *%s* is... concerning",
                    ":eyes: *%s* is acting up again",
                    ":face_with_monocle: Something smells off in *%s*"
            );
            case MEDIUM -> List.of(
                    ":thinking_face: *%s* is being a little weird",
                    ":slightly_frowning_face: *%s* raised an eyebrow",
                    ":man-shrugging: *%s* is giving mixed signals",
                    ":chart_with_upwards_trend: *%s* went a bit rogue"
            );
            case LOW -> List.of(
                    ":information_source: *%s* sneezed",
                    ":wave: *%s* just wanted some attention",
                    ":shrug: Tiny blip in *%s*, probably fine",
                    ":cricket: *%s* whispered something suspicious"
            );
        };
        int idx = ThreadLocalRandom.current().nextInt(messages.size());
        return messages.get(idx).formatted(serviceId);
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
