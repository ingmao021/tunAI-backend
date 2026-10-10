package com.tunai.api.adapter.in.web;

import com.tunai.api.domain.model.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resuelve la IP real del cliente (RF-BE-022), en este orden:
 *
 * <ol>
 *   <li>Header de confianza puesto por el proxy de borde ({@code CF-Connecting-IP} en Render, que va detrás de
 *       Cloudflare). Cloudflare lo sobrescribe, así que el cliente no puede falsificarlo.
 *   <li>{@code X-Forwarded-For}: el valor que agregó el primero de los {@code trustedProxies} proxies de confianza,
 *       contando desde la derecha. El primer valor de la lista nunca se usa: lo controla el cliente.
 *   <li>La IP de la conexión TCP.
 * </ol>
 */
public class ClientIpResolver implements HandlerMethodArgumentResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);
    private static final ClientIp UNKNOWN = new ClientIp("0.0.0.0");

    private final String trustedHeader;
    private final int trustedProxies;

    /**
     * @param trustedHeader header de IP puesto por el proxy de borde; vacío o nulo para no usarlo
     * @param trustedProxies proxies de confianza que agregan valores a {@code X-Forwarded-For}
     */
    public ClientIpResolver(String trustedHeader, int trustedProxies) {
        if (trustedProxies < 1) {
            throw new IllegalArgumentException("trustedProxies debe ser al menos 1: " + trustedProxies);
        }
        this.trustedHeader = trustedHeader == null || trustedHeader.isBlank() ? null : trustedHeader.trim();
        this.trustedProxies = trustedProxies;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ClientIp.class.equals(parameter.getParameterType());
    }

    @Override
    public ClientIp resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        return resolve(webRequest.getNativeRequest(HttpServletRequest.class));
    }

    ClientIp resolve(HttpServletRequest request) {
        if (trustedHeader != null) {
            Optional<ClientIp> fromHeader = ClientIp.parse(request.getHeader(trustedHeader));
            if (fromHeader.isPresent()) {
                log.debug("IP del cliente {} desde {}", fromHeader.get().masked(), trustedHeader);
                return fromHeader.get();
            }
        }
        Optional<ClientIp> fromForwarded = fromForwardedFor(request);
        if (fromForwarded.isPresent()) {
            log.debug(
                    "IP del cliente {} desde X-Forwarded-For",
                    fromForwarded.get().masked());
            return fromForwarded.get();
        }
        ClientIp remote = ClientIp.parse(request.getRemoteAddr()).orElse(UNKNOWN);
        log.debug("IP del cliente {} desde la conexión", remote.masked());
        return remote;
    }

    private Optional<ClientIp> fromForwardedFor(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders("X-Forwarded-For"))) {
            for (String hop : header.split(",")) {
                if (!hop.isBlank()) {
                    hops.add(hop.trim());
                }
            }
        }
        int index = hops.size() - trustedProxies;
        return index >= 0 ? ClientIp.parse(hops.get(index)) : Optional.empty();
    }
}
