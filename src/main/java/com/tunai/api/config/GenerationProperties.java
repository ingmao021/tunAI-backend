package com.tunai.api.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Límites de la generación (Anexo A).
 *
 * @param maxModelBytes tamaño máximo del GLB devuelto por el proveedor (RF-BE-013)
 */
@Validated
@ConfigurationProperties("tunai.generation")
public record GenerationProperties(@NotNull @DefaultValue("20MB") DataSize maxModelBytes) {}
