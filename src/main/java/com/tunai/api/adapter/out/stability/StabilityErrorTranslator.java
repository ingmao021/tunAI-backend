package com.tunai.api.adapter.out.stability;

import com.tunai.api.domain.exception.DomainException;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderRejectedException;
import com.tunai.api.domain.exception.ProviderUnavailableException;
import java.time.Duration;
import java.util.Locale;

/**
 * Capa anticorrupción (§13.3): traduce las respuestas de error de Stability a excepciones del dominio, para que sus
 * códigos no lleguen al cliente (RF-BE-014).
 *
 * <p>Stability no publica el catálogo de errores de este endpoint (P-BE-04), así que la traducción es defensiva:
 *
 * <ul>
 *   <li>400, 413, 422 → la imagen o sus parámetros fueron rechazados ({@code 422 PROVIDER_REJECTED}).
 *   <li>403 → rechazo por moderación si el cuerpo lo menciona; si no, problema de la clave ({@code 503}).
 *   <li>401, 402, 404 → el servicio no está disponible para nosotros: clave, créditos o endpoint ({@code 503}).
 *   <li>429 → límite de tasa ({@code 503}, con {@code Retry-After} si viene).
 *   <li>5xx y cualquier otro → fallo del proveedor ({@code 502 PROVIDER_ERROR}).
 * </ul>
 */
final class StabilityErrorTranslator {

    /** Máximo de caracteres del cuerpo de error que se incluyen en el mensaje interno (nunca llega al cliente). */
    static final int MAX_BODY_CHARS = 500;

    DomainException translate(int status, String body, String retryAfterHeader) {
        String summary = "Stability respondió " + status + ": " + abbreviate(body);
        return switch (status) {
            case 400, 413, 422 -> new ProviderRejectedException(summary);
            case 403 ->
                mentionsModeration(body)
                        ? new ProviderRejectedException(summary)
                        : new ProviderUnavailableException(summary);
            case 401, 402, 404 -> new ProviderUnavailableException(summary);
            case 429 -> new ProviderUnavailableException(summary, parseRetryAfter(retryAfterHeader));
            default -> new ProviderFailureException(summary);
        };
    }

    private static boolean mentionsModeration(String body) {
        return body != null && body.toLowerCase(Locale.ROOT).contains("moderation");
    }

    /** Solo el formato en segundos; una fecha HTTP o un valor inválido se ignoran. */
    static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String abbreviate(String body) {
        if (body == null || body.isBlank()) {
            return "(sin cuerpo)";
        }
        String compact = body.strip().replaceAll("\\s+", " ");
        return compact.length() <= MAX_BODY_CHARS ? compact : compact.substring(0, MAX_BODY_CHARS) + "…";
    }
}
