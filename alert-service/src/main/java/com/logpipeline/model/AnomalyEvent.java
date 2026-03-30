package com.logpipeline.model;

import java.time.Instant;

public record AnomalyEvent(
        String anomalyId,
        String serviceId,
        String severity,
        String description,
        double detectedValue,
        double threshold,
        Instant detectedAt
) {}
