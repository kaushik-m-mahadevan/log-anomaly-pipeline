package com.logpipeline.health;

import com.logpipeline.service.DetectorService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator that surfaces the detector warm-up state (Item 1).
 *
 * After a pod restart, every per-service sliding window is empty.
 * The first {@code windowSize} events for each service will not produce anomalies —
 * this "blind window" is a production safety concern operators must know about.
 *
 * This indicator reports:
 *   - UP:      at least one service has been seen (warm-up has started)
 *   - OUT_OF_SERVICE: no services seen yet (fresh restart, all windows cold)
 *
 * In a Kubernetes readiness probe config this can be used to delay traffic until
 * the service has processed at least one event, though the pod is still healthy
 * enough to receive traffic (the liveness probe remains UP regardless).
 *
 * The indicator is intentionally informational — it does not block deploys.
 */
@Component
public class DetectorWarmupHealthIndicator implements HealthIndicator {

    private final DetectorService detectorService;

    public DetectorWarmupHealthIndicator(DetectorService detectorService) {
        this.detectorService = detectorService;
    }

    @Override
    public Health health() {
        long activeDetectors = detectorService.registrySize();

        if (activeDetectors == 0) {
            return Health.outOfService()
                    .withDetail("status", "warming-up")
                    .withDetail("activeDetectors", 0L)
                    .withDetail("message",
                            "warming-up: no log events received yet. "
                          + "Sliding windows are cold — anomaly detection will begin "
                          + "once each service fills its window.")
                    .build();
        }

        return Health.up()
                .withDetail("status", "ready")
                .withDetail("activeDetectors", activeDetectors)
                .build();
    }
}
