package com.tunai.api.adapter.in.web;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Catálogo de errores del contrato (§15.4). El campo {@code code} es estable; el cliente decide por él. */
enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Petición inválida"),
    INVALID_INSTALLATION_ID(HttpStatus.BAD_REQUEST, "ID de instalación inválido"),
    INVALID_IMAGE(HttpStatus.BAD_REQUEST, "Imagen inválida"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Recurso no encontrado"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Método no permitido"),
    IMAGE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Imagen demasiado grande"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Formato no soportado"),
    PROVIDER_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, "Imagen rechazada"),
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Cuota diaria agotada"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno"),
    PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "Error del proveedor"),
    SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "Servicio ocupado"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible"),
    PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Tiempo agotado con el proveedor");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    HttpStatus status() {
        return status;
    }

    String title() {
        return title;
    }

    /** URI estable del tipo de problema (RFC 9457), sin depender de un dominio propio. */
    URI type() {
        return URI.create("urn:tunai:problem:" + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
