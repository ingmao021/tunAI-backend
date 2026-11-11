package com.tunai.api.adapter.in.web;

import com.tunai.api.application.port.in.AuthenticateUseCase;
import com.tunai.api.domain.model.AccountId;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Inyecta la {@link AccountId} de la sesión en cualquier endpoint que la declare. Lee {@code Authorization: Bearer
 * <token>}; si falta o no es válido, lanza {@code InvalidTokenException} → {@code 401 UNAUTHORIZED}.
 */
public class AccountIdArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final AuthenticateUseCase authenticate;

    public AccountIdArgumentResolver(AuthenticateUseCase authenticate) {
        this.authenticate = authenticate;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return AccountId.class.equals(parameter.getParameterType());
    }

    @Override
    public AccountId resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        String header = webRequest.getHeader(HEADER);
        String token = header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())
                ? header.substring(PREFIX.length())
                : null;
        return authenticate.authenticate(token);
    }
}
