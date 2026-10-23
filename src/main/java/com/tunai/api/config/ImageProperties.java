package com.tunai.api.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Límites de la foto (RN-04, Anexo A).
 *
 * @param maxBytes tamaño máximo; también fija los límites de multipart de Spring (RNF-BE-007)
 * @param minSide lado menor mínimo en píxeles
 */
@Validated
@ConfigurationProperties("tunai.image")
public record ImageProperties(
        @NotNull @DefaultValue("10MB") DataSize maxBytes,
        @Min(1) @DefaultValue("512") int minSide) {}
