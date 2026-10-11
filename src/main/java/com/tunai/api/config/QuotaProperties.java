package com.tunai.api.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Límites de uso (RN-02, RN-10, RN-11, Anexo A). Cada instalación recibe {@code initialCredits} una vez y cada modelo
 * usa {@code creditsPerModel} (45 y 15: tres modelos). Los límites diarios por IP y global (2) son los de la demo; en
 * desarrollo se suben por variables de entorno (§13.8).
 */
@Validated
@ConfigurationProperties("tunai.quota")
public record QuotaProperties(
        @Min(0) @DefaultValue("45") int initialCredits,
        @Min(1) @DefaultValue("15") int creditsPerModel,
        @Min(1) @DefaultValue("2") int perIp,
        @Min(1) @DefaultValue("2") int globalDaily,
        @NotNull @DefaultValue("America/Bogota") ZoneId timezone) {}
