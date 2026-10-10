package com.tunai.api.config;

import com.tunai.api.domain.model.GenerationOptions;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración de Stable Fast 3D (RF-BE-012, RNF-BE-002, Anexo A). La clave solo es obligatoria con el proveedor
 * {@code stability}; esa regla la aplica {@link GeneratorConfiguration}.
 *
 * @param apiKey clave de Stability; nunca se registra ni se muestra (RNF-BE-006)
 * @param vertexCount vértices objetivo; {@code -1} usa el valor por defecto del proveedor
 */
@Validated
@ConfigurationProperties("tunai.stability")
public record StabilityProperties(
        String apiKey,
        @NotNull @DefaultValue("https://api.stability.ai") URI baseUrl,
        @DefaultValue("1024") int textureResolution,
        @DecimalMin("0.1") @DecimalMax("1.0") @DefaultValue("0.85") double foregroundRatio,
        @NotNull @DefaultValue("none") GenerationOptions.Remesh remesh,
        @Min(-1) @DefaultValue("-1") int vertexCount,
        @NotNull @DefaultValue("10s") Duration connectTimeout,
        @NotNull @DefaultValue("60s") Duration readTimeout) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Valida los valores permitidos al construir el objeto de dominio. */
    public GenerationOptions generationOptions() {
        return new GenerationOptions(textureResolution, foregroundRatio, remesh);
    }

    /** Nunca incluye la clave: el {@code toString} de un record la mostraría en logs y errores de arranque. */
    @Override
    public String toString() {
        return "StabilityProperties[apiKey=" + (hasApiKey() ? "****" : "(vacía)") + ", baseUrl=" + baseUrl
                + ", textureResolution=" + textureResolution + ", foregroundRatio=" + foregroundRatio + ", remesh="
                + remesh + ", vertexCount=" + vertexCount + ", connectTimeout=" + connectTimeout + ", readTimeout="
                + readTimeout + "]";
    }
}
