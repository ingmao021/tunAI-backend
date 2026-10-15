package com.tunai.api.config;

import com.tunai.api.adapter.out.tripo.TripoAdapter;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Ensambla el {@link ModelGeneratorPort}: Tripo API v3 es el único proveedor de generación 3D (§13.9). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TripoProperties.class, GenerationProperties.class})
public class GeneratorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(GeneratorConfiguration.class);

    /** RNF-BE-015: la app no arranca si falta la clave. */
    @Bean
    ModelGeneratorPort modelGenerator(TripoProperties tripo, GenerationProperties generation) {
        if (!tripo.hasApiKey()) {
            throw new IllegalStateException("La generación 3D requiere la variable de entorno TRIPO_API_KEY");
        }
        log.warn(
                "Generador TRIPO: cada generación exitosa gasta créditos reales. Endpoint {}, modelo {}, {} caras,"
                        + " textura {}, geometría {}, PBR {}, saldo mínimo {}, consulta cada {}, plazo total {}",
                tripo.baseUrl(),
                tripo.model(),
                tripo.faceLimit(),
                tripo.textureQuality(),
                tripo.geometryQuality(),
                tripo.pbr(),
                tripo.minBalance(),
                tripo.pollInterval(),
                tripo.maxWait());
        return TripoAdapter.create(
                tripo.baseUrl(),
                tripo.apiKey(),
                tripo.connectTimeout(),
                tripo.readTimeout(),
                tripo.options(),
                tripo.minBalance(),
                tripo.pollInterval(),
                tripo.maxWait(),
                generation.maxModelBytes().toBytes());
    }
}
