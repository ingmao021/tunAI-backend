package com.tunai.api.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Reloj inyectable: las pruebas usan uno fijo para simular el cambio de día (Anexo C). */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
