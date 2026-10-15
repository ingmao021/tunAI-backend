package com.tunai.api.adapter.out.tripo;

import com.tunai.api.domain.exception.DomainException;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderRejectedException;
import com.tunai.api.domain.exception.ProviderUnavailableException;
import java.time.Duration;

/**
 * Capa anticorrupción (§13.9): traduce los errores de Tripo a excepciones del dominio, para que sus códigos no lleguen
 * al cliente (RF-BE-014). Tripo publica su catálogo, así que se decide primero por el {@code code} del cuerpo y, si no
 * es conocido, por el estado HTTP.
 *
 * <ul>
 *   <li>2003, 2004, 2008 (archivo vacío, formato, contenido prohibido) → {@code 422 PROVIDER_REJECTED}.
 *   <li>2, 1000, 1001 (clave), 2010 (sin créditos), 2015 (versión retirada) → {@code 503 SERVICE_UNAVAILABLE}.
 *   <li>1007, 2000 (tasa o concurrencia) → {@code 503}, con {@code Retry-After} si viene.
 *   <li>Cualquier otro, incluido 2002 (parámetro no soportado: error de configuración nuestro) → por estado HTTP, y si
 *       no encaja, {@code 502 PROVIDER_ERROR}.
 * </ul>
 */
final class TripoErrorTranslator {

    /** Máximo de caracteres del cuerpo de error que se incluyen en el mensaje interno (nunca llega al cliente). */
    static final int MAX_BODY_CHARS = 500;

    DomainException translate(String step, int status, Integer code, String body, String retryAfterHeader) {
        String summary = "Tripo (" + step + ") respondió " + status + (code != null ? " code " + code : "") + ": "
                + abbreviate(body);
        if (code != null) {
            switch (code) {
                case 2003, 2004, 2008 -> {
                    return new ProviderRejectedException(summary);
                }
                case 2, 1000, 1001, 2010, 2015 -> {
                    return new ProviderUnavailableException(summary);
                }
                case 1007, 2000 -> {
                    return new ProviderUnavailableException(summary, parseRetryAfter(retryAfterHeader));
                }
                default -> {
                    // se decide por el estado HTTP
                }
            }
        }
        return switch (status) {
            case 413, 422 -> new ProviderRejectedException(summary);
            case 401, 402, 403, 404 -> new ProviderUnavailableException(summary);
            case 429 -> new ProviderUnavailableException(summary, parseRetryAfter(retryAfterHeader));
            default -> new ProviderFailureException(summary);
        };
    }

    /**
     * Estados finales sin modelo (Task Lifecycle). Solo {@code banned} es culpa de la foto. En {@code failed} y
     * {@code cancelled} Tripo devuelve los créditos congelados; en {@code banned} la tarea no llega a generarse.
     * Cualquier otro ({@code expired} o uno desconocido) se trata como cobrado, para que la cuota diaria lo cuente.
     */
    DomainException translateTaskStatus(String taskId, String status) {
        String summary = "La tarea " + taskId + " de Tripo terminó en estado " + status;
        return switch (status) {
            case "banned" -> new ProviderRejectedException(summary);
            case "failed", "cancelled" -> new ProviderFailureException(summary);
            default -> new ProviderFailureException(summary + " (Tripo puede cobrarla)", null, true);
        };
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
