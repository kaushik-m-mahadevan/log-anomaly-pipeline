# Log Anomaly Detection Pipeline

A production-grade, event-driven microservices system that ingests application logs in batches, detects error rate anomalies in real time using a sliding-window algorithm, and routes structured alerts through a dedicated notification service — containerised with Docker and designed for Kubernetes deployment.

> Built to demonstrate distributed systems design patterns: async messaging, stateful stream processing, and clean microservice architecture.

---

## Architecture overview

```
┌─────────────────────┐      processed-logs topic      ┌──────────────────────────┐
│  log-ingestion-     │ ─────────────────────────────► │  anomaly-detector-       │
│  service            │                                │  service                 │
│                     │                                │                          │
│  REST API (batch)   │                                │  Sliding-window detector │
│  Kafka Producer     │                                │  Kafka Consumer Group    │
│  Structured log DTO │                                │  Stateful error rate     │
└─────────────────────┘                                └──────────────┬───────────┘
                                                                      │
                                                         anomaly-alerts topic
                                                                      │
                                                                      ▼
                                                       ┌──────────────────────────┐
                                                       │  alert-service           │
                                                       │                          │
                                                       │  Kafka Consumer          │
                                                       │  Alert routing & fanout  │
                                                       │  Structured alert log    │
                                                       └──────────────────────────┘
```

### Why this architecture?

| Decision | Choice | Rationale |
|---|---|---|
| Messaging | Apache Kafka | Durable, replayable event log; decouples producers from consumers; enables independent scaling per tier |
| Detection algorithm | Sliding-window error rate | Maintains a fixed-size window of recent events per service; fires when error proportion exceeds a configurable threshold |
| Topic design | One topic per event type | Avoids the anti-pattern of a single catch-all topic; enables per-topic retention and consumer group isolation |
| Scaling target | Detector service | The stateful detection tier is the correct scaling bottleneck — not ingestion or alerting |
| Dead-letter handling | `@RetryableTopic` with DLT | Poison messages are retried with exponential backoff then routed to a dead-letter topic — not silently dropped |
| Alert extensibility | Strategy pattern (`AlertChannel`) | New delivery channels (email, webhook, PagerDuty) are added by implementing one interface — zero changes to routing logic |

---

## Services

### `log-ingestion-service`
Exposes a REST API that accepts batches of structured log events and publishes them to the `processed-logs` Kafka topic.

- `POST /api/v1/logs/batch` — accepts up to 500 events per request
- `POST /api/v1/logs` — convenience single-event endpoint
- Bean validation on all inbound fields (`@NotBlank`, `@Pattern` on log level)
- Partition key on `serviceId` — ensures all logs from the same service land on the same partition
- Idempotent Kafka producer — `acks=all`, `enable.idempotence=true`
- Returns `202 Accepted` — fire-and-forget; does not wait for downstream processing

### `anomaly-detector-service`
Consumes from `processed-logs`, evaluates each event against a per-service sliding window, and publishes `AnomalyEvent`s to `anomaly-alerts` when an error rate spike is detected.

- `ErrorRateSpikeDetector` — pure domain class, zero Spring dependencies, fully unit-testable
- One detector instance per `serviceId`, lazily initialised and stored in a `ConcurrentHashMap`
- Window size and error rate threshold are externalised via environment variables
- `@RetryableTopic` — non-blocking retries with exponential backoff; exhausted messages routed to `processed-logs-dlt`
- `DetectorService` owns all Kafka I/O — the detector interface knows nothing about messaging (SRP)

### `alert-service`
Consumes anomaly events and fans out to all registered alert channels.

- `AlertChannel` interface — Strategy pattern; each channel is an independent `@Component`
- `ConsoleAlertChannel` — MVP implementation; structured log output with severity, service, and description
- `AlertRoutingService` — Spring injects all `AlertChannel` implementations automatically; adding a new channel requires zero changes here
- Designed for extension to email, SMS, webhook, or PagerDuty without touching routing logic

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.2 |
| Messaging | Apache Kafka 3.6 |
| Containerisation | Docker + Docker Compose |
| Orchestration | Kubernetes (Minikube) — manifests planned for v2 |
| Build | Maven (multi-module parent POM) |
| Testing | JUnit 5, Mockito |

---

## Project structure

```
log-anomaly-pipeline/
│
├── log-ingestion-service/
│   └── src/main/java/com/logpipeline/
│       ├── config/       KafkaProducerConfig, TopicConfig
│       ├── controller/   LogIngestionController
│       ├── dto/          LogEventRequest, LogEventBatchRequest, LogEventMessage
│       └── service/      LogPublisherService
│
├── anomaly-detector-service/
│   └── src/main/java/com/logpipeline/
│       ├── config/       KafkaConsumerConfig, KafkaProducerConfig, TopicConfig
│       ├── consumer/     LogEventConsumer
│       ├── detector/     AnomalyDetector (interface), ErrorRateSpikeDetector
│       ├── model/        LogEvent, LogLevel, AnomalyEvent, AnomalySeverity
│       └── service/      DetectorService
│
├── alert-service/
│   └── src/main/java/com/logpipeline/
│       ├── channel/      AlertChannel (interface), ConsoleAlertChannel
│       ├── config/       KafkaConsumerConfig
│       ├── consumer/     AnomalyEventConsumer
│       ├── model/        AnomalyEvent
│       └── service/      AlertRoutingService
│
├── docker/
│   └── docker-compose.yml   # Kafka + Zookeeper + all three services
│
└── docs/
    └── diagrams/
```

---

## Key design decisions & trade-offs

### Stateful detection without a stateful framework
`ErrorRateSpikeDetector` maintains an in-memory circular buffer (`ArrayDeque`) per `serviceId`. This deliberately avoids the operational complexity of Kafka Streams or Flink while still demonstrating the core challenge of stateful stream processing. The trade-off — window state is lost on pod restart or rebalance — is explicitly acknowledged. The production path (externalising state to Redis keyed by partition) is documented as a roadmap item.

### Partition key strategy
Log events are published with `serviceId` as the Kafka partition key. This guarantees all logs from the same upstream service land on the same partition and are consumed by the same detector instance — preserving per-service window state without cross-partition coordination.

### Delivery guarantees
The consumer uses Kafka's auto-commit with `@RetryableTopic` for non-blocking retries. On repeated failure, the original message is routed to `processed-logs-dlt` (dead-letter topic) rather than being dropped. This is a deliberate trade-off: simpler than manual offset management while still providing a recoverable failure path.

### Alert channel extensibility
`AlertRoutingService` depends on `List<AlertChannel>` — Spring injects every `@Component` that implements the interface. Adding email or webhook delivery means implementing `AlertChannel` and annotating with `@Component`. This is the Open/Closed principle in practice: the routing logic is closed for modification, open for extension.

---

## Running locally

### Prerequisites
- Docker Desktop (running)
- Java 21
- Maven 3.9+

### Step 1 — start Kafka

```bash
docker compose -f docker/docker-compose.yml up zookeeper kafka -d
```

Wait until both show `healthy`:

```bash
docker compose -f docker/docker-compose.yml ps
```

### Step 2 — start the services

Open three separate terminals from the project root:

```bash
# Terminal 1
cd log-ingestion-service && mvn spring-boot:run

# Terminal 2
cd anomaly-detector-service && mvn spring-boot:run

# Terminal 3
cd alert-service && mvn spring-boot:run
```

### Step 3 — trigger an anomaly

Send a batch of ERROR events to breach the detection threshold. With default settings (`window-size=20`, `error-rate-threshold=0.4`), you need 8+ errors in a 20-event window.

```bash
curl -X POST http://localhost:8080/api/v1/logs/batch \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      { "serviceId": "payment-service", "level": "ERROR", "message": "Timeout #1" },
      { "serviceId": "payment-service", "level": "ERROR", "message": "Timeout #2" },
      { "serviceId": "payment-service", "level": "ERROR", "message": "Timeout #3" },
      { "serviceId": "payment-service", "level": "ERROR", "message": "Timeout #4" },
      { "serviceId": "payment-service", "level": "ERROR", "message": "Timeout #5" },
      { "serviceId": "payment-service", "level": "ERROR", "message": "Timeout #6" },
      { "serviceId": "payment-service", "level": "ERROR", "message": "Timeout #7" },
      { "serviceId": "payment-service", "level": "ERROR", "message": "Timeout #8" },
      { "serviceId": "payment-service", "level": "INFO",  "message": "Recovered" },
      { "serviceId": "payment-service", "level": "INFO",  "message": "Recovered" }
    ]
  }'
```

Watch the `alert-service` terminal for the anomaly banner.

**Tip:** For faster local testing, lower the window size in `anomaly-detector-service/src/main/resources/application.yml`:

```yaml
detector:
  window-size: 5
  error-rate-threshold: 0.4
```

3 errors in 5 events (60%) will trigger an alert.

### Step 4 — inspect Kafka topics directly

```bash
# List all topics
docker exec -it <kafka-container-name> kafka-topics.sh \
  --list --bootstrap-server localhost:9092

# Watch the processed-logs topic live
docker exec -it <kafka-container-name> kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic processed-logs \
  --from-beginning

# Watch the anomaly-alerts topic live
docker exec -it <kafka-container-name> kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic anomaly-alerts \
  --from-beginning
```

---

## Detection algorithm

`ErrorRateSpikeDetector` uses a sliding window over the most recent N log events per service:

```
window = circular buffer of N boolean observations (true = ERROR or FATAL)
error_rate = count(errors in window) / window_size

if error_rate >= threshold → publish AnomalyEvent with severity classification:
  >= 90% → CRITICAL
  >= 70% → HIGH
  >= 50% → MEDIUM
  >= 40% → LOW
```

The window fills before detection begins — no false positives on cold start. Detection is skipped until the buffer holds `window_size` observations.

**Planned upgrade:** Replace with a Z-score algorithm so the detector adapts to each service's natural baseline rather than using a fixed threshold. A service that normally errors 30% of the time will not alert on its own baseline — only on statistically significant deviations.

---

## What this project demonstrates

- **Async, decoupled service communication** via Kafka — services have zero runtime dependencies on each other
- **Stateful stream processing** — per-service sliding window with partition affinity via Kafka key routing
- **Production-grade Kafka configuration** — idempotent producers, `@RetryableTopic` with exponential backoff, dead-letter topic routing
- **Clean architecture** — detector is a pure domain interface; Kafka I/O never leaks into business logic
- **Extensibility by design** — Strategy pattern on alert channels; Open/Closed principle enforced structurally

---

## Roadmap

- [ ] Kubernetes manifests — Deployments, ConfigMaps, HPA on detector service
- [ ] Z-score detector — statistically adaptive baseline per service
- [ ] Externalise window state to Redis for stateless horizontal scaling
- [ ] Prometheus metrics (`/actuator/prometheus`) + Grafana dashboard
- [ ] Webhook alert channel — first extension of `AlertChannel` beyond console
- [ ] Schema Registry + Avro serialization for `LogEvent` and `AnomalyEvent`