package com.tunai.api.config;

import com.tunai.api.adapter.in.web.ImageRequestMapper;
import com.tunai.api.adapter.out.concurrency.SemaphoreConcurrencyLimiter;
import com.tunai.api.adapter.out.imageio.ImageIoDimensionsReader;
import com.tunai.api.adapter.out.quota.InMemoryQuotaStore;
import com.tunai.api.application.port.in.GenerateModelUseCase;
import com.tunai.api.application.port.in.GetQuotaUseCase;
import com.tunai.api.application.port.out.ConcurrencyLimiterPort;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.application.port.out.QuotaStorePort;
import com.tunai.api.application.service.GenerateModelService;
import com.tunai.api.application.service.GetQuotaService;
import com.tunai.api.domain.model.ImageDimensionsReader;
import com.tunai.api.domain.model.ImagePolicy;
import com.tunai.api.domain.model.QuotaLimits;
import jakarta.servlet.MultipartConfigElement;
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
@EnableConfigurationProperties({ImageProperties.class, GenerationProperties.class, QuotaProperties.class})
public class BeanConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BeanConfiguration.class);

    /** Margen para los encabezados del formulario multipart por encima del tamaño de la imagen (RNF-BE-007). */
    static final DataSize MULTIPART_OVERHEAD = DataSize.ofMegabytes(1);

    /** Generaciones reales que cubre el saldo gratuito (RN-11); por encima se advierte al arrancar (§13.8). */
    static final int REAL_BUDGET_GENERATIONS = 2;

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
    QuotaStorePort quotaStore(QuotaProperties quota, GeneratorProperties generator, Clock clock) {
        QuotaLimits limits =
                new QuotaLimits(quota.perInstallation(), quota.perIp(), quota.globalDaily(), quota.timezone());
        logEffectiveLimits(limits, generator.provider());
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
            StabilityProperties stability) {
        return new GenerateModelService(
                concurrency,
                quota,
                generator,
                stability.generationOptions(),
                properties.maxModelBytes().toBytes());
    }

    @Bean
    GetQuotaUseCase getQuotaUseCase(QuotaStorePort quota) {
        return new GetQuotaService(quota);
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
    private static void logEffectiveLimits(QuotaLimits limits, GeneratorProperties.Provider provider) {
        log.info(
                "Cuota diaria: {} por instalación, {} por IP, {} global; reinicio a las 00:00 {} (proveedor {})",
                limits.perInstallation(),
                limits.perIp(),
                limits.globalDaily(),
                limits.zone(),
                provider);
        if (provider == GeneratorProperties.Provider.STABILITY && limits.globalDaily() > REAL_BUDGET_GENERATIONS) {
            log.warn(
                    "Proveedor stability con límite global {} > {}: se pueden gastar más créditos de los presupuestados."
                            + " Revisa TUNAI_QUOTA_GLOBAL_DAILY (Anexo E)",
                    limits.globalDaily(),
                    REAL_BUDGET_GENERATIONS);
        }
    }
}
