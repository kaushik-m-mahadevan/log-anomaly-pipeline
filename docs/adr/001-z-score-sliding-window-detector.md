# ADR-001: Z-Score Sliding Window Anomaly Detector

**Status:** Accepted
**Date:** 2026-03-31

## Context

The original `ErrorRateSpikeDetector` used a fixed error-rate threshold (e.g. 40 %).
This caused two classes of production problems:
- **False positives**: a noisy legacy service running at 60 % errors permanently triggers alerts.
- **False negatives**: a pristine service spiking from 0 % to 20 % is missed entirely.

## Decision

Replace the fixed-threshold detector with a Z-score sliding-window detector (`ZScoreSpikeDetector`).

The window is partitioned into two zones (oldest → newest):

```
┌──────────────────────────┬──────────────┐
│  BASELINE (windowSize-n) │  SIGNAL (n)  │
│  Establishes "normal"    │  What we test│
└──────────────────────────┴──────────────┘
```

Z-score = (signalRate − baselineRate) / stdDev(baselineRate)
stdDev uses the Bernoulli formula, floored at `MIN_STD_DEV = 0.01` to prevent division-by-zero on a perfectly stable baseline.

An anomaly fires when `Z ≥ zScoreThreshold` and is suppressed (edge-triggered) until the Z-score recovers below the threshold.

Severity mapping:
- CRITICAL: Z ≥ 5.0
- HIGH:     Z ≥ 3.5
- MEDIUM:   Z ≥ 2.5
- LOW:      Z ≥ threshold

## Configuration

| Property | Default | Env var |
|----------|---------|---------|
| `detector.window-size` | 30 | `DETECTOR_WINDOW_SIZE` |
| `detector.signal-window-size` | 10 | `DETECTOR_SIGNAL_WINDOW_SIZE` |
| `detector.z-score-threshold` | 2.0 | `DETECTOR_Z_SCORE_THRESHOLD` |

## Consequences

- **Better precision**: noisy services don't generate alerts for their normal noise level.
- **Better recall**: pristine services catch small absolute spikes that a fixed threshold misses.
- **Blind window**: after a pod restart, each service requires `windowSize` events before detection resumes. The `DetectorWarmupHealthIndicator` exposes this state via `/actuator/health`.
- **State is in-memory**: detector windows are lost on restart. This is acceptable for the current SLA; if zero blind window is required, persist snapshots to Redis.
