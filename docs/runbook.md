# Log Anomaly Pipeline — Operations Runbook

## Services

| Service | Port | Topic (consumes) | Topic (produces) |
|---------|------|-----------------|-----------------|
| log-ingestion-service | 8080 | — | `processed-logs` |
| anomaly-detector-service | 8081 | `processed-logs` | `anomaly-alerts` |
| alert-service | 8082 | `anomaly-alerts` | — |

## Health Checks

All services expose `/actuator/health`. Key indicators:

| Service | Indicator | OUT_OF_SERVICE means |
|---------|-----------|---------------------|
| anomaly-detector-service | `detectorWarmup` | Pod restarted; sliding windows are empty. No anomalies will fire until each service fills `detector.window-size` events. Normal after deploy; check if it persists > 5 min. |

## Key Metrics (Prometheus)

| Metric | Service | Alert suggestion |
|--------|---------|-----------------|
| `log.events.published_total` | ingestion | Alert if rate drops to 0 for > 2 min |
| `log.events.publish.failed_total` | ingestion | Alert if > 5/min |
| `detector.anomalies.detected_total` | detector | Monitor for sudden spikes (pipeline health) |
| `detector.events.duplicates.dropped_total` | detector | Alert if > 100/min (producer retry storm) |
| `detector.anomalies.publish.failed_total` | detector | Alert if > 0 (anomalies being lost) |
| `detector.registry.size` | detector | Alert if > 8,000 (approaching Caffeine max of 10,000) |
| `alert.routed_total` | alert | Should track `detector.anomalies.detected_total` |
| `alert.channel.failures_total` | alert | Alert if > 0 (channel delivery failing) |

## Common Incidents

### No anomalies detected after deploy
1. Check `/actuator/health` on anomaly-detector-service. If `detectorWarmup` is `OUT_OF_SERVICE`, the windows are warming up — wait for `detector.window-size` (default: 30) events per service.
2. Check `detector.anomalies.detected_total` — if it's rising, detection is working.
3. Check `processed-logs` consumer lag in Kafka. If lag is growing, the detector is behind.

### Alert spam (same anomaly firing repeatedly)
The edge-triggered cooldown in `ZScoreSpikeDetector` should suppress this. If alerts are repeating:
1. Check if `detector.events.duplicates.dropped_total` is rising (producer retry loop).
2. Check if the service is experiencing genuine repeated spikes (normal behavior).
3. Confirm `anomalyActive` state is resetting correctly — look for "re-arm" log messages.

### 429 Too Many Requests from ingestion endpoint
The `IngressRateLimiter` caps requests at `rate-limit.requests-per-second` (default: 1000).
Increase via `RATE_LIMIT_RPS` env var or coordinate with upstream to reduce request rate.

### Kafka offset committed but anomaly not published (silent loss)
This is the async-publish-without-transactions gap (Item 11). Check `detector.anomalies.publish.failed_total`. If > 0:
1. Check broker health.
2. The detector's `DELIVERY_TIMEOUT_MS` is 30 s — confirm the broker recovered within that window.
3. If persistent, consider enabling Kafka transactions (requires `isolation.level=read_committed` on all consumers).

## Configuration Reference

### Environment Variables

| Variable | Service | Default | Description |
|----------|---------|---------|-------------|
| `KAFKA_BOOTSTRAP_SERVERS` | all | `localhost:9092` | Kafka broker addresses |
| `KAFKA_REPLICATION_FACTOR` | ingestion, detector | `1` | Topic replication factor — set to `3` in prod |
| `KAFKA_MAX_POLL_RECORDS` | detector | `100` | Max events per poll cycle (backpressure) |
| `DETECTOR_WINDOW_SIZE` | detector | `30` | Total sliding window size |
| `DETECTOR_SIGNAL_WINDOW_SIZE` | detector | `10` | Signal zone size for Z-score |
| `DETECTOR_Z_SCORE_THRESHOLD` | detector | `2.0` | Z-score required to fire |
| `RATE_LIMIT_RPS` | ingestion | `1000` | Max HTTP requests per second |

## Deployment Notes

- All services use `server.shutdown: graceful` with a 30 s drain window. Kubernetes `terminationGracePeriodSeconds` should be ≥ 35 s to account for pre-stop hook overhead.
- Replication factor defaults to 1 for local dev. **Always set `KAFKA_REPLICATION_FACTOR=3` in staging and production.**
- The detector registry is bounded at 10,000 services with 1 h TTL (Caffeine). If you expect more than 8,000 unique `serviceId` values, increase `maximumSize` in `DetectorService`.
