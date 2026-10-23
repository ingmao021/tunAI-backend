package com.tunai.api.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Cómo se obtiene la IP real del cliente (RF-BE-022).
 *
 * @param trustedHeader header de IP puesto por el proxy de borde; vacío para no usarlo
 * @param trustedProxies proxies de confianza que agregan valores a {@code X-Forwarded-For}
 */
@Validated
@ConfigurationProperties("tunai.client-ip")
public record ClientIpProperties(
        @DefaultValue("CF-Connecting-IP") String trustedHeader,
        @Min(1) @DefaultValue("1") int trustedProxies) {}
