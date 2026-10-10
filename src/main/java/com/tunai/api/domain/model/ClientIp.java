package com.tunai.api.domain.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * IP del cliente, normalizada, para el límite por IP (RN-10). En los logs solo se usa {@link #masked()}
 * (RNF-BE-009).
 */
public record ClientIp(String value) {

    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-f:.]{2,45}");

    public ClientIp {
        Objects.requireNonNull(value, "value");
        if (!isIpLiteral(value)) {
            throw new IllegalArgumentException("No es una IP literal");
        }
    }

    /**
     * Normaliza un valor tomado de un header: quita espacios, corchetes de IPv6 y pasa a minúsculas. No resuelve
     * nombres DNS.
     *
     * @return vacío si el valor no es una IP literal válida
     */
    public static Optional<ClientIp> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return isIpLiteral(normalized) ? Optional.of(new ClientIp(normalized)) : Optional.empty();
    }

    /** IPv4 con el último octeto oculto; IPv6 con solo los tres primeros grupos. */
    public String masked() {
        if (IPV4.matcher(value).matches()) {
            return value.substring(0, value.lastIndexOf('.')) + ".x";
        }
        String[] groups = value.split(":", -1);
        return String.join(":", Arrays.copyOf(groups, Math.min(3, groups.length))) + ":x";
    }

    private static boolean isIpLiteral(String candidate) {
        var ipv4 = IPV4.matcher(candidate);
        if (ipv4.matches()) {
            for (int group = 1; group <= 4; group++) {
                if (Integer.parseInt(ipv4.group(group)) > 255) {
                    return false;
                }
            }
            return true;
        }
        return candidate.indexOf(':') >= 0 && IPV6.matcher(candidate).matches();
    }

    @Override
    public String toString() {
        return masked();
    }
}
