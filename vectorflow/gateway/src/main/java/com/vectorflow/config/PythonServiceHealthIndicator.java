package com.vectorflow.config;

import com.vectorflow.service.VectorSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Adds the Python AI service to /actuator/health. */
@Component("pythonService")
@RequiredArgsConstructor
public class PythonServiceHealthIndicator implements HealthIndicator {

    private final VectorSearchService vectorSearchService;

    @Override
    public Health health() {
        return vectorSearchService.healthCheck() ? Health.up().build() : Health.down().build();
    }
}
