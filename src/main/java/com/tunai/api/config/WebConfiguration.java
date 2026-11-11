package com.tunai.api.config;

import com.tunai.api.adapter.in.web.AccountIdArgumentResolver;
import com.tunai.api.adapter.in.web.ClientIpResolver;
import com.tunai.api.adapter.in.web.TraceIdFilter;
import com.tunai.api.application.port.in.AuthenticateUseCase;
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
    private final AuthenticateUseCase authenticate;

    public WebConfiguration(ClientIpProperties clientIp, AuthenticateUseCase authenticate) {
        this.authenticate = authenticate;
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
        resolvers.add(new AccountIdArgumentResolver(authenticate));
        resolvers.add(new ClientIpResolver(clientIp.trustedHeader(), clientIp.trustedProxies()));
    }
}
