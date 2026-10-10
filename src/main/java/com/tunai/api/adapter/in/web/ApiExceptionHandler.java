package com.tunai.api.adapter.in.web;

import com.tunai.api.domain.exception.DomainException;
import com.tunai.api.domain.exception.GlobalLimitReachedException;
import com.tunai.api.domain.exception.ImageTooLargeException;
import com.tunai.api.domain.exception.InvalidImageException;
import com.tunai.api.domain.exception.InvalidInstallationIdException;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderRejectedException;
import com.tunai.api.domain.exception.ProviderTimeoutException;
import com.tunai.api.domain.exception.ProviderUnavailableException;
import com.tunai.api.domain.exception.QuotaExceededException;
import com.tunai.api.domain.exception.ServiceBusyException;
import com.tunai.api.domain.exception.UnsupportedImageFormatException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Único punto de traducción de excepciones a {@code application/problem+json} (RNF-BE-010, RF-BE-016). Ningún stack
 * trace llega al cliente.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final Map<Class<? extends DomainException>, ErrorCode> DOMAIN_CODES = Map.ofEntries(
            Map.entry(InvalidInstallationIdException.class, ErrorCode.INVALID_INSTALLATION_ID),
            Map.entry(InvalidImageException.class, ErrorCode.INVALID_IMAGE),
            Map.entry(ImageTooLargeException.class, ErrorCode.IMAGE_TOO_LARGE),
            Map.entry(UnsupportedImageFormatException.class, ErrorCode.UNSUPPORTED_MEDIA_TYPE),
            Map.entry(ProviderRejectedException.class, ErrorCode.PROVIDER_REJECTED),
            Map.entry(QuotaExceededException.class, ErrorCode.QUOTA_EXCEEDED),
            Map.entry(ProviderFailureException.class, ErrorCode.PROVIDER_ERROR),
            Map.entry(ServiceBusyException.class, ErrorCode.SERVICE_BUSY),
            Map.entry(GlobalLimitReachedException.class, ErrorCode.SERVICE_UNAVAILABLE),
            Map.entry(ProviderUnavailableException.class, ErrorCode.SERVICE_UNAVAILABLE),
            Map.entry(ProviderTimeoutException.class, ErrorCode.PROVIDER_TIMEOUT));

    /** Mensajes fijos para errores cuyo detalle interno no debe llegar al cliente. */
    private static final Map<Class<? extends DomainException>, String> PUBLIC_DETAILS = Map.of(
            ProviderRejectedException.class, "El servicio de IA no pudo procesar esta imagen.",
            ProviderFailureException.class, "El servicio de IA falló. No se descontó la cuota.",
            GlobalLimitReachedException.class, "El servicio no está disponible en este momento.",
            ProviderUnavailableException.class, "El servicio no está disponible en este momento.",
            ProviderTimeoutException.class, "El servicio de IA tardó demasiado. No se descontó la cuota.");

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException e) {
        ErrorCode code = DOMAIN_CODES.getOrDefault(e.getClass(), ErrorCode.INTERNAL_ERROR);
        String detail = PUBLIC_DETAILS.getOrDefault(e.getClass(), e.getMessage());
        logDomain(code, e);
        ResponseEntity<ProblemDetail> response = problem(code, detail, e.retryAfter());
        if (e instanceof QuotaExceededException quotaExceeded) {
            // A4: el 429 lleva los headers de cuota además de Retry-After
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(response.getHeaders());
            QuotaHeaders.write(headers, quotaExceeded.status());
            return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
        }
        return response;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.info("{}: {}", ErrorCode.IMAGE_TOO_LARGE, e.getMessage());
        return problem(ErrorCode.IMAGE_TOO_LARGE, "La imagen supera el tamaño máximo permitido.", Optional.empty());
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    ResponseEntity<ProblemDetail> handleMissingImage(Exception e) {
        log.info("{}: {}", ErrorCode.INVALID_IMAGE, e.getMessage());
        return problem(ErrorCode.INVALID_IMAGE, "Falta el campo 'image' en el formulario multipart.", Optional.empty());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException e) {
        log.info("{}: {}", ErrorCode.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
        return problem(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "La petición debe ser multipart/form-data.", Optional.empty());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(NoResourceFoundException e) {
        return problem(ErrorCode.NOT_FOUND, "La ruta no existe.", Optional.empty());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethod(HttpRequestMethodNotSupportedException e) {
        ResponseEntity<ProblemDetail> response =
                problem(ErrorCode.METHOD_NOT_ALLOWED, "Método no permitido en esta ruta.", Optional.empty());
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .headers(e.getHeaders())
                .body(response.getBody());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        // Resto de errores propios de Spring MVC (por ejemplo, 406 o un header mal formado): se respeta su 4xx
        if (e instanceof ErrorResponse springError
                && springError.getStatusCode().is4xxClientError()) {
            return clientError(springError.getStatusCode(), e);
        }
        log.error("{}: error inesperado", ErrorCode.INTERNAL_ERROR, e);
        return problem(ErrorCode.INTERNAL_ERROR, "Ocurrió un error inesperado.", Optional.empty());
    }

    private static ResponseEntity<ProblemDetail> clientError(HttpStatusCode status, Exception e) {
        log.info("{} ({}): {}", ErrorCode.BAD_REQUEST, status.value(), e.getMessage());
        ProblemDetail body = problem(ErrorCode.BAD_REQUEST, "La petición no es válida.", Optional.empty())
                .getBody();
        body.setStatus(status.value());
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body);
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail, Optional<Duration> retryAfter) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(code.status(), detail);
        body.setType(code.type());
        body.setTitle(code.title());
        body.setProperty("code", code.name());
        body.setProperty("traceId", MDC.get(TraceIdFilter.MDC_KEY));
        ResponseEntity.BodyBuilder builder =
                ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON);
        retryAfter.ifPresent(d -> builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(d))));
        return builder.body(body);
    }

    /** {@code Retry-After} en segundos enteros, redondeado hacia arriba y nunca menor que 1. */
    static long retryAfterSeconds(Duration duration) {
        long seconds = duration.toSeconds() + (duration.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }

    private static void logDomain(ErrorCode code, DomainException e) {
        if (e instanceof ProviderUnavailableException) {
            // A9: clave inválida, sin créditos o límite de tasa; el operador debe actuar
            log.error("{}: {}", code, e.getMessage());
        } else if (code.status().is5xxServerError()) {
            log.warn("{}: {}", code, e.getMessage(), e.getCause());
        } else {
            log.info("{}: {}", code, e.getMessage());
        }
    }
}
