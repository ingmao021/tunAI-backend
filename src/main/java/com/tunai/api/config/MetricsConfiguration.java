package com.tunai.api.config;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.logging.LoggingMeterRegistry;
import io.micrometer.core.instrument.logging.LoggingRegistryConfig;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Las métricas son internas (RNF-BE-008: Actuator solo expone {@code health}), así que se publican en el log. Solo se
 * registran las métricas {@code tunai.*} para no gastar memoria en las de la JVM, y solo se escriben cuando hubo
 * actividad en el intervalo.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MetricsConfiguration.MetricsProperties.class)
public class MetricsConfiguration {

    @Bean
    MeterRegistry meterRegistry(MetricsProperties properties) {
        LoggingRegistryConfig config = new LoggingRegistryConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public Duration step() {
                return properties.logInterval();
            }
        };
        LoggingMeterRegistry registry = new LoggingMeterRegistry(config, Clock.SYSTEM);
        registry.config()
                .meterFilter(MeterFilter.acceptNameStartsWith("tunai."))
                .meterFilter(MeterFilter.deny());
        return registry;
    }

    /** @param logInterval cada cuánto se escriben las métricas en el log ({@code TUNAI_METRICS_LOG_INTERVAL}) */
    @Validated
    @ConfigurationProperties("tunai.metrics")
    public record MetricsProperties(@NotNull @DefaultValue("5m") Duration logInterval) {}
}
