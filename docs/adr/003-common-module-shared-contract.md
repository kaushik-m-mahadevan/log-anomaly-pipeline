# ADR-003: Shared `common` Module for Inter-Service Contracts

**Status:** Accepted
**Date:** 2026-03-31

## Context

`AnomalyEvent` and `LogEventMessage` are serialized to Kafka and consumed by a different microservice. Originally each service kept its own copy of these types. The copies drifted silently — a field rename in one service compiled fine but broke deserialization in the other, with no compile-time signal.

Additionally, `DetectorService` deserialised `LogEventMessage` from Kafka as `Map<String,Object>`, which meant a field rename in the producer would result in `null` values being silently passed through rather than a compile error.

## Decision

Create a `common` Maven module containing:
- `LogEventMessage` — wire contract between `log-ingestion-service` and `anomaly-detector-service`
- `AnomalyEvent` — wire contract between `anomaly-detector-service` and `alert-service`
- `AnomalySeverity` — shared enum

Both services declare `common` as a compile-scoped dependency. Kafka consumers now deserialize directly to the typed record rather than `Map<String,Object>`.

A `schemaVersion` field is added to both records (currently `"1"`). Consumers check this field and can park unknown versions to the DLT before processing.

## Consequences

- **Compile-time safety**: renaming a field in `common` breaks compilation in all consumers immediately, not at runtime.
- **Coupling**: the three services now share a compile-time dependency on `common`. This is an acceptable trade-off given they communicate via these exact types.
- **Evolution**: to add a field, use the expand-contract pattern: add the field as optional in `common`, deploy consumers first (they ignore the new field), then deploy producers, then make the field required.
- **Versioning**: `schemaVersion` allows consumers to detect and reject incompatible producers during a failed rolling deploy.
