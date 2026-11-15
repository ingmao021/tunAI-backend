package com.tunai.api.config;

import com.tunai.api.adapter.in.web.ClientIpResolver;
import com.tunai.api.adapter.in.web.InstallationIdArgumentResolver;
import com.tunai.api.adapter.in.web.TraceIdFilter;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientIpProperties.class)
public class WebConfiguration implements WebMvcConfigurer {

    private final ClientIpProperties clientIp;

    public WebConfiguration(ClientIpProperties clientIp) {
        this.clientIp = clientIp;
    }

    @Bean
    FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new InstallationIdArgumentResolver());
        resolvers.add(new ClientIpResolver(clientIp.trustedHeader(), clientIp.trustedProxies()));
    }
}
