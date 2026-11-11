package com.tunai.api.config;

import com.tunai.api.adapter.in.web.ImageRequestMapper;
import com.tunai.api.adapter.out.auth.ConfiguredCredentialVerifier;
import com.tunai.api.adapter.out.auth.HmacSessionIssuer;
import com.tunai.api.adapter.out.concurrency.SemaphoreConcurrencyLimiter;
import com.tunai.api.adapter.out.imageio.ImageIoDimensionsReader;
import com.tunai.api.adapter.out.metrics.MeteredModelGenerator;
import com.tunai.api.adapter.out.quota.InMemoryQuotaStore;
import com.tunai.api.application.port.in.AuthenticateUseCase;
import com.tunai.api.application.port.in.GenerateModelUseCase;
import com.tunai.api.application.port.in.GetQuotaUseCase;
import com.tunai.api.application.port.in.LoginUseCase;
import com.tunai.api.application.port.out.ConcurrencyLimiterPort;
import com.tunai.api.application.port.out.CredentialVerifierPort;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.application.port.out.QuotaStorePort;
import com.tunai.api.application.port.out.SessionIssuerPort;
import com.tunai.api.application.port.out.SessionVerifierPort;
import com.tunai.api.application.service.AuthenticationService;
import com.tunai.api.application.service.GenerateModelService;
import com.tunai.api.application.service.GetQuotaService;
import com.tunai.api.application.service.LoginService;
import com.tunai.api.domain.model.ImageDimensionsReader;
import com.tunai.api.domain.model.ImagePolicy;
import com.tunai.api.domain.model.QuotaLimits;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.MultipartConfigElement;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/** Ensambla los casos de uso con sus puertos (§13.4, principio D). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    ImageProperties.class,
    GenerationProperties.class,
    QuotaProperties.class,
    AuthProperties.class
})
public class BeanConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BeanConfiguration.class);

    /** Margen para los encabezados del formulario multipart por encima del tamaño de la imagen (RNF-BE-007). */
    static final DataSize MULTIPART_OVERHEAD = DataSize.ofMegabytes(1);

    /** Generaciones que cubre el saldo de Tripo (100 créditos, ~40 por modelo); por encima se advierte al arrancar. */
    static final int REAL_BUDGET_GENERATIONS = 2;

    /** Etiqueta {@code provider} de las métricas (RNF-BE-012). */
    static final String PROVIDER = "tripo";

    private static final int TOKEN_SECRET_BYTES = 32;

    @Bean
    ImagePolicy imagePolicy(ImageProperties properties) {
        return new ImagePolicy(properties.maxBytes().toBytes(), properties.minSide());
    }

    @Bean
    ImageDimensionsReader imageDimensionsReader() {
        return new ImageIoDimensionsReader();
    }

    @Bean
    ImageRequestMapper imageRequestMapper(ImagePolicy policy, ImageDimensionsReader dimensionsReader) {
        return new ImageRequestMapper(policy, dimensionsReader);
    }

    @Bean
    QuotaStorePort quotaStore(QuotaProperties quota, Clock clock) {
        QuotaLimits limits = new QuotaLimits(
                quota.initialCredits(), quota.creditsPerModel(), quota.perIp(), quota.globalDaily(), quota.timezone());
        logEffectiveLimits(limits);
        return new InMemoryQuotaStore(limits, clock);
    }

    @Bean
    ConcurrencyLimiterPort concurrencyLimiter(GenerationProperties properties) {
        return new SemaphoreConcurrencyLimiter(properties.maxConcurrent());
    }

    @Bean
    GenerateModelUseCase generateModelUseCase(
            ConcurrencyLimiterPort concurrency,
            QuotaStorePort quota,
            ModelGeneratorPort generator,
            GenerationProperties properties,
            MeterRegistry meterRegistry) {
        ModelGeneratorPort metered = new MeteredModelGenerator(generator, meterRegistry, PROVIDER);
        return new GenerateModelService(
                concurrency, quota, metered, properties.maxModelBytes().toBytes());
    }

    @Bean
    GetQuotaUseCase getQuotaUseCase(QuotaStorePort quota) {
        return new GetQuotaService(quota);
    }

    @Bean
    CredentialVerifierPort credentialVerifier(AuthProperties auth) {
        ConfiguredCredentialVerifier verifier = new ConfiguredCredentialVerifier(auth.users());
        if (verifier.userCount() == 0) {
            log.warn("TUNAI_AUTH_USERS vacío: nadie podrá iniciar sesión (el hash se genera con PasswordHash.hash)");
        }
        return verifier;
    }

    @Bean
    HmacSessionIssuer sessionIssuer(AuthProperties auth, Clock clock) {
        byte[] secret;
        if (auth.tokenSecret().isBlank()) {
            log.warn("TUNAI_AUTH_TOKEN_SECRET vacío: se usa una clave aleatoria; las sesiones caducan al reiniciar");
            secret = new byte[TOKEN_SECRET_BYTES];
            new SecureRandom().nextBytes(secret);
        } else {
            secret = auth.tokenSecret().getBytes(StandardCharsets.UTF_8);
        }
        return new HmacSessionIssuer(secret, auth.sessionTtl(), clock);
    }

    @Bean
    AuthenticateUseCase authenticateUseCase(SessionVerifierPort sessions) {
        return new AuthenticationService(sessions);
    }

    @Bean
    LoginUseCase loginUseCase(CredentialVerifierPort credentials, SessionIssuerPort sessions) {
        return new LoginService(credentials, sessions);
    }

    /**
     * Límites de multipart derivados de {@code tunai.image.max-bytes}. El umbral igual al tamaño máximo de la petición
     * mantiene el archivo en memoria: Tomcat no escribe temporales en disco (RF-BE-018).
     */
    @Bean
    MultipartConfigElement multipartConfigElement(ImageProperties properties) {
        DataSize maxRequest = DataSize.ofBytes(properties.maxBytes().toBytes() + MULTIPART_OVERHEAD.toBytes());
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(properties.maxBytes());
        factory.setMaxRequestSize(maxRequest);
        factory.setFileSizeThreshold(maxRequest);
        return factory.createMultipartConfig();
    }

    /** §13.8: el operador ve al arrancar qué proveedor y qué límites quedaron activos. */
    private static void logEffectiveLimits(QuotaLimits limits) {
        log.info(
                "Cuota: {} créditos por cuenta, {} por modelo; diaria {} por IP, {} global; reinicio a las"
                        + " 00:00 {}",
                limits.initialCredits(),
                limits.creditsPerModel(),
                limits.perIp(),
                limits.globalDaily(),
                limits.zone());
        if (limits.globalDaily() > REAL_BUDGET_GENERATIONS) {
            log.warn(
                    "Límite global {} > {}: se pueden gastar más créditos de Tripo de los"
                            + " presupuestados."
                            + " Revisa TUNAI_QUOTA_GLOBAL_DAILY (Anexo E)",
                    limits.globalDaily(),
                    REAL_BUDGET_GENERATIONS);
        }
    }
}
