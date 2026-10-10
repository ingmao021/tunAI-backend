package com.tunai.api.adapter.in.web;

import com.tunai.api.domain.model.InstallationId;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Inyecta un {@link InstallationId} validado en cualquier endpoint que lo declare (RF-BE-004). Si el header falta o no
 * es un UUID, lanza {@code InvalidInstallationIdException} → {@code 400 INVALID_INSTALLATION_ID}.
 */
public class InstallationIdArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String HEADER = "X-Installation-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return InstallationId.class.equals(parameter.getParameterType());
    }

    @Override
    public InstallationId resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        return InstallationId.parse(webRequest.getHeader(HEADER));
    }
}
