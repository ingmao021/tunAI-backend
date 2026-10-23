package com.tunai.api.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Límites de la generación (Anexo A).
 *
 * @param maxModelBytes tamaño máximo del GLB devuelto por el proveedor (RF-BE-013)
 * @param maxConcurrent generaciones simultáneas hacia el proveedor (RF-BE-011)
 */
@Validated
@ConfigurationProperties("tunai.generation")
public record GenerationProperties(
        @NotNull @DefaultValue("50MB") DataSize maxModelBytes,
        @Min(1) @DefaultValue("2") int maxConcurrent) {}
