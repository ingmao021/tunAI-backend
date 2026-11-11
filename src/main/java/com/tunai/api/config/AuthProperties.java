package com.tunai.api.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Inicio de sesión. {@code users}: {@code usuario:hash,usuario:hash} (hash de {@code PasswordHash}); vacío = nadie
 * puede entrar. {@code tokenSecret}: firma de los tokens; vacío = se genera uno al arrancar y los tokens dejan de
 * servir al reiniciar.
 */
@ConfigurationProperties("tunai.auth")
public record AuthProperties(
        @DefaultValue("") String users,
        @DefaultValue("") String tokenSecret,
        @DefaultValue("30d") Duration sessionTtl) {}
