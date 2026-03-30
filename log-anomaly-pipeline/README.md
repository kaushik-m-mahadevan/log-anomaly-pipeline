# Log Anomaly Detection Pipeline

A production-grade, event-driven microservices system that ingests application logs, detects statistical anomalies in real time using a sliding-window Z-score algorithm, and routes alerts through a dedicated notification service — all orchestrated on Kubernetes with horizontal autoscaling.

> Built to demonstrate distributed systems design patterns: async messaging, stateful stream processing, and container orchestration at scale.

---

## Architecture overview

```
┌─────────────────────┐        raw-logs topic         ┌──────────────────────────┐
│  log-ingestion-     │ ─────────────────────────────► │  anomaly-detector-       │
│  service            │                                │  service                 │
│                     │                                │                          │
│  REST API           │                                │  Sliding-window Z-score  │
│  Kafka Producer     │                                │  Kafka Consumer Group    │
│  Structured log DTO │                                │  Stateful detection      │
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
| Detection algorithm | Sliding-window Z-score | Stateful, parameter-free baseline; adapts to traffic patterns without manual threshold tuning |
| Topic design | One topic per event type | Avoids the anti-pattern of a single catch-all topic; enables per-topic retention and consumer group isolation |
| Scaling target | Detector service (HPA) | The CPU-bound anomaly detection tier is the correct scaling bottleneck — not ingestion or alerting |
| Dead-letter handling | Dedicated DLT per topic | Poison messages are quarantined, not silently dropped — a production requirement |

---

## Services

### `log-ingestion-service`
Exposes a REST endpoint that accepts structured log events and publishes them to the `raw-logs` Kafka topic. Acts as the entry point to the pipeline.

- REST `POST /api/v1/logs` with `LogEventRequest` DTO validation
- `KafkaProducerConfig` with idempotent producer settings
- Partition key on `serviceId` — ensures log ordering per source service
- `application.yml` externalises all Kafka and topic config (no hardcoded broker URLs)

### `anomaly-detector-service`
Consumes from `raw-logs`, runs the Z-score detection algorithm over a sliding window of recent log-error rates, and publishes `AnomalyEvent`s to the `anomaly-alerts` topic when a threshold is breached.

- `SlidingWindowZScoreDetector` — pure domain class, zero framework dependencies, fully unit-testable
- Consumer group `detector-group` with manual offset commit for at-least-once delivery guarantees
- Dead-letter topic routing for malformed or unprocessable events
- `DetectorService` separates Kafka I/O from detection logic (SRP)

### `alert-service`
Consumes anomaly events and handles downstream fanout — structured logging, extensible to webhook or email delivery.

- Idiomatic `@KafkaListener` with typed deserialization
- Alert enrichment (severity classification, timestamp normalization)
- Designed for extension via Strategy pattern for multiple alert channels

---

## Tech stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.2 |
| Messaging | Apache Kafka 3.6 |
| Containerisation | Docker |
| Orchestration | Kubernetes (Minikube) |
| Autoscaling | Horizontal Pod Autoscaler (HPA) on detector |
| Config management | Kubernetes ConfigMaps + Secrets |
| Build | Maven (multi-module) |
| Testing | JUnit 5, Mockito, Spring Kafka Test (EmbeddedKafka) |

---

## Project structure

```
log-anomaly-pipeline/
│
├── log-ingestion-service/          # Kafka producer + REST entry point
│   └── src/main/java/com/logpipeline/
│       ├── config/                 # KafkaProducerConfig, TopicConfig
│       ├── controller/             # LogIngestionController
│       ├── dto/                    # LogEventRequest, LogEventMessage
│       └── service/                # LogPublisherService
│
├── anomaly-detector-service/       # Core detection logic + Kafka consumer
│   └── src/main/java/com/logpipeline/
│       ├── config/                 # KafkaConsumerConfig, KafkaProducerConfig
│       ├── consumer/               # LogEventConsumer (Kafka listener)
│       ├── detector/               # SlidingWindowZScoreDetector (pure domain)
│       ├── model/                  # AnomalyEvent, LogEvent, WindowMetrics
│       └── service/                # DetectorService (orchestrates pipeline)
│
├── alert-service/                  # Anomaly consumer + alert routing
│   └── src/main/java/com/logpipeline/
│       ├── config/                 # KafkaConsumerConfig
│       ├── consumer/               # AnomalyEventConsumer
│       └── service/                # AlertRoutingService
│
├── k8s/
│   ├── kafka/                      # Kafka + Zookeeper manifests
│   ├── ingestion/                  # Deployment, Service, ConfigMap
│   ├── detector/                   # Deployment, HPA, ConfigMap
│   └── alert/                      # Deployment, Service, ConfigMap
│
├── docker/
│   └── docker-compose.yml          # Local dev: all services + Kafka + Zookeeper
│
└── docs/
    └── diagrams/                   # Architecture and sequence diagrams
```

---

## Key design decisions & trade-offs

### Stateful detection without a stateful framework
The `SlidingWindowZScoreDetector` maintains an in-memory circular buffer per `serviceId`. This is a deliberate trade-off: it avoids the operational complexity of Kafka Streams or Flink while demonstrating the core algorithmic challenge of stateful stream processing. The trade-off (state lost on pod restart) is explicitly acknowledged and addressed in the [scaling notes](#scaling-and-state-considerations).

### At-least-once delivery
The detector consumer uses manual offset commits, flushed only after successful anomaly evaluation and downstream publish. This means a pod crash can cause re-processing of the last uncommitted batch — an acceptable duplicate-detection risk that is orders of magnitude safer than at-most-once (silent data loss).

### Partition key strategy
Log events are keyed by `serviceId` on publish. This guarantees that all logs from a given upstream service land on the same partition and are processed by the same detector consumer instance — preserving the per-service sliding window without cross-partition state sharing.

---

## Scaling and state considerations

The detector HPA is configured to scale on CPU utilisation. As replicas increase, Kafka's consumer group rebalancing automatically redistributes partitions. Because window state is held in-memory per-instance, a rebalance triggers a warm-up period where the new consumer rebuilds its window from recently consumed events.

**Production path:** For stateless scaling, the window state would be externalised to Redis (per-partition key). This is noted as a documented extension point in `DetectorService`.

---

## Running locally

### Prerequisites
- Docker Desktop
- Minikube + kubectl
- Java 21, Maven 3.9+

### Quick start (Docker Compose)

```bash
# Start Kafka, Zookeeper, and all three services
docker-compose -f docker/docker-compose.yml up --build

# Send a test log event
curl -X POST http://localhost:8080/api/v1/logs \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "payment-service",
    "level": "ERROR",
    "message": "Connection timeout to downstream",
    "timestamp": "2024-01-15T10:30:00Z"
  }'
```

### Kubernetes (Minikube)

```bash
# Start Minikube and enable metrics server (required for HPA)
minikube start
minikube addons enable metrics-server

# Deploy Kafka
kubectl apply -f k8s/kafka/

# Deploy services
kubectl apply -f k8s/ingestion/
kubectl apply -f k8s/detector/
kubectl apply -f k8s/alert/

# Watch HPA scale the detector under load
kubectl get hpa anomaly-detector-hpa --watch

# Expose ingestion service for local testing
minikube service log-ingestion-service --url
```

---

## Testing strategy

| Test type | Scope | Tool |
|---|---|---|
| Unit | `SlidingWindowZScoreDetector` — pure algorithm, no Spring context | JUnit 5 + Mockito |
| Unit | `DetectorService` — mocked Kafka template, verifies routing logic | JUnit 5 + Mockito |
| Integration | Kafka producer → consumer round-trip | `@EmbeddedKafka` (Spring Kafka Test) |
| Contract | `LogEventRequest` validation (field constraints, null checks) | JUnit 5 |

The detector algorithm is tested independently of all Kafka infrastructure — this is intentional. Coupling algorithm tests to a Kafka context would make the test suite slow and brittle.

---

## What this project demonstrates

This PoC is scoped to show specific distributed systems competencies, not to be a full production system:

- **Async, decoupled service communication** via Kafka — services have zero runtime dependencies on each other
- **Stateful stream processing** — sliding window algorithm with per-partition state affinity
- **Horizontal autoscaling** — Kubernetes HPA on the correct bottleneck tier
- **Production-grade Kafka configuration** — idempotent producers, manual commits, dead-letter topics, consumer group design
- **Externalized configuration** — no hardcoded infrastructure config; all Kafka and topic settings flow through ConfigMaps
- **Clean architecture** — detection algorithm is a pure domain class; Kafka I/O never leaks into business logic

---

## Roadmap / extension points

- [ ] Externalise window state to Redis for stateless horizontal scaling
- [ ] Add Prometheus metrics endpoint (`/actuator/prometheus`) + Grafana dashboard
- [ ] Implement webhook alert channel in `alert-service` (Strategy pattern slot already defined)
- [ ] Add Kafka Streams DSL variant of the detector for comparison
- [ ] Schema Registry + Avro serialization for `LogEvent` and `AnomalyEvent`
