package com.tunai.api.config;

import com.tunai.api.adapter.out.tripo.TripoOptions;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración de Tripo API v3 (§13.9, Anexo A). La clave solo es obligatoria con el proveedor {@code tripo}; esa
 * regla la aplica {@link GeneratorConfiguration}.
 *
 * @param apiKey clave de Tripo; nunca se registra ni se muestra (RNF-BE-006)
 * @param faceLimit máximo de caras del modelo; sin límite, v3.1 puede devolver cientos de miles y superar el tamaño
 *     máximo del GLB
 * @param minBalance créditos de Tripo disponibles que exige cada generación antes de empezar; debe ser al menos lo que
 *     cuesta un modelo con estos parámetros (40)
 * @param pollInterval cada cuánto se consulta la tarea (Tripo recomienda 1–2 s)
 * @param maxWait plazo de toda la generación (saldo, subida, tarea y descarga); debe quedar por debajo de los ~100 s
 *     del proxy de Render y de los 120 s del cliente
 */
@Validated
@ConfigurationProperties("tunai.tripo")
public record TripoProperties(
        String apiKey,
        @NotNull @DefaultValue("https://openapi.tripo3d.ai/v3") URI baseUrl,
        @NotBlank @DefaultValue("v3.1-20260211") String model,
        @Min(TripoOptions.MIN_FACES) @Max(TripoOptions.MAX_FACES) @DefaultValue("200000") int faceLimit,
        @NotNull @DefaultValue("detailed") TripoOptions.TextureQuality textureQuality,
        @NotNull @DefaultValue("standard") TripoOptions.GeometryQuality geometryQuality,
        @DefaultValue("true") boolean pbr,
        @Min(0) @DefaultValue("40") int minBalance,
        @NotNull @DefaultValue("10s") Duration connectTimeout,
        @NotNull @DefaultValue("30s") Duration readTimeout,
        @NotNull @DefaultValue("2s") Duration pollInterval,
        @NotNull @DefaultValue("85s") Duration maxWait) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public TripoOptions options() {
        return new TripoOptions(model, faceLimit, textureQuality, geometryQuality, pbr);
    }

    /** Nunca incluye la clave: el {@code toString} de un record la mostraría en logs y errores de arranque. */
    @Override
    public String toString() {
        return "TripoProperties[apiKey=" + (hasApiKey() ? "****" : "(vacía)") + ", baseUrl=" + baseUrl + ", model="
                + model + ", faceLimit=" + faceLimit + ", textureQuality=" + textureQuality
                + ", geometryQuality=" + geometryQuality + ", pbr=" + pbr + ", minBalance=" + minBalance
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + ", pollInterval="
                + pollInterval + ", maxWait=" + maxWait + "]";
    }
}
