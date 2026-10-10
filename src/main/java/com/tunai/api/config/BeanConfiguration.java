package com.tunai.api.config;

import com.tunai.api.adapter.in.web.ImageRequestMapper;
import com.tunai.api.adapter.out.imageio.ImageIoDimensionsReader;
import com.tunai.api.application.port.in.GenerateModelUseCase;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.application.service.GenerateModelService;
import com.tunai.api.domain.model.GenerationOptions;
import com.tunai.api.domain.model.ImageDimensionsReader;
import com.tunai.api.domain.model.ImagePolicy;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/** Ensambla los casos de uso con sus puertos (§13.4, principio D). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ImageProperties.class, GenerationProperties.class})
public class BeanConfiguration {

    /** Margen para los encabezados del formulario multipart por encima del tamaño de la imagen (RNF-BE-007). */
    static final DataSize MULTIPART_OVERHEAD = DataSize.ofMegabytes(1);

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
    GenerateModelUseCase generateModelUseCase(ModelGeneratorPort generator, GenerationProperties properties) {
        // Los parámetros de SF3D se vuelven configurables con el adaptador de Stability (Fase 4, RF-BE-012)
        return new GenerateModelService(
                generator,
                GenerationOptions.DEFAULTS,
                properties.maxModelBytes().toBytes());
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
}
