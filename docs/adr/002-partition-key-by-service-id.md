# ADR-002: Kafka Partition Key = serviceId

**Status:** Accepted
**Date:** 2026-03-31

## Context

`DetectorService` maintains one `ZScoreSpikeDetector` instance per `serviceId`. Each detector holds a stateful sliding window. If events from the same service arrive on different partitions (and therefore different consumer threads), the window would be split across two detector instances, making the Z-score meaningless.

## Decision

Use `serviceId` as the Kafka partition key for both the `processed-logs` and `anomaly-alerts` topics.

This guarantees:
1. All events from the same service land on the same partition.
2. Only one consumer thread ever calls `detectorFor(serviceId)` at a time (within a single consumer group instance).
3. `ZScoreSpikeDetector` does not need to be thread-safe.

## Consequences

- **Ordering**: per-service ordering is preserved within a partition.
- **Hot partitions**: if one service generates vastly more traffic than others, its partition will be hotter. Mitigate by sharding `serviceId` space if this becomes a problem.
- **serviceId validation**: the partition key must never be null or blank. `LogPublisherService.publish()` enforces this with an explicit guard.
- **Thread safety contract**: `ZScoreSpikeDetector` is intentionally NOT thread-safe. This is safe because of the partition-key guarantee above. If that guarantee is ever removed (e.g. switching to round-robin), add synchronisation.
