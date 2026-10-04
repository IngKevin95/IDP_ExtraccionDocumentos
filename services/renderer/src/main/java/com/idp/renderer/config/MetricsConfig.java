package com.idp.renderer.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class MetricsConfig {

    @Bean
    MeterBinder heapUsagePercent() {
        return registry -> Gauge.builder("renderer.memory.heap_usage_percent", () -> {
                    Runtime rt = Runtime.getRuntime();
                    return 100.0 * (rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
                })
                .register(registry);
    }
}
