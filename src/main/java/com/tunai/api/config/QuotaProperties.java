package com.tunai.api.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Límites diarios de generaciones exitosas (RN-02, RN-10, RN-11, Anexo A). Los valores por defecto (2) son los de la
 * demo; en desarrollo se suben por variables de entorno (§13.8).
 */
@Validated
@ConfigurationProperties("tunai.quota")
public record QuotaProperties(
        @Min(1) @DefaultValue("2") int perInstallation,
        @Min(1) @DefaultValue("2") int perIp,
        @Min(1) @DefaultValue("2") int globalDaily,
        @NotNull @DefaultValue("America/Bogota") ZoneId timezone) {}
