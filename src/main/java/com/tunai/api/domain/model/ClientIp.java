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
    private static final Pattern HEX_GROUP = Pattern.compile("[0-9a-f]{1,4}");

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

    /**
     * Clave del límite por IP (RN-10). IPv4 cuenta por dirección. IPv6 cuenta por su prefijo {@code /64} en forma
     * canónica: un mismo cliente suele recibir todo un {@code /64} y podría rotar direcciones para esquivar el límite,
     * y una misma dirección admite varias escrituras ({@code ::1} y {@code 0:0::1}). Una IPv4 mapeada
     * ({@code ::ffff:1.2.3.4}) cuenta como la IPv4.
     */
    public String quotaKey() {
        if (IPV4.matcher(value).matches()) {
            return value;
        }
        int[] groups = ipv6Groups(value);
        if (groups == null) {
            return value;
        }
        if (isIpv4Mapped(groups)) {
            return (groups[6] >> 8) + "." + (groups[6] & 0xff) + "." + (groups[7] >> 8) + "." + (groups[7] & 0xff);
        }
        return String.format("%x:%x:%x:%x::/64", groups[0], groups[1], groups[2], groups[3]);
    }

    private static boolean isIpv4Mapped(int[] groups) {
        for (int i = 0; i < 5; i++) {
            if (groups[i] != 0) {
                return false;
            }
        }
        return groups[5] == 0xffff;
    }

    /** Los 8 grupos de 16 bits de una IPv6, o {@code null} si el texto no es una IPv6 bien formada. */
    private static int[] ipv6Groups(String ip) {
        String text = ip;
        int[] embeddedIpv4 = null;
        if (text.indexOf('.') >= 0) {
            int lastColon = text.lastIndexOf(':');
            embeddedIpv4 = ipv4Groups(text.substring(lastColon + 1));
            if (embeddedIpv4 == null) {
                return null;
            }
            text = text.substring(0, lastColon + 1) + "0:0";
        }
        String[] halves = text.split("::", -1);
        if (halves.length > 2) {
            return null;
        }
        String[] head = halves[0].isEmpty() ? new String[0] : halves[0].split(":", -1);
        String[] tail = halves.length == 2 && !halves[1].isEmpty() ? halves[1].split(":", -1) : new String[0];
        int explicit = head.length + tail.length;
        if (halves.length == 1 ? explicit != 8 : explicit > 7) {
            return null;
        }
        int[] groups = new int[8];
        for (int i = 0; i < head.length; i++) {
            groups[i] = hexGroup(head[i]);
        }
        for (int i = 0; i < tail.length; i++) {
            groups[8 - tail.length + i] = hexGroup(tail[i]);
        }
        for (int group : groups) {
            if (group < 0) {
                return null;
            }
        }
        if (embeddedIpv4 != null) {
            groups[6] = embeddedIpv4[0];
            groups[7] = embeddedIpv4[1];
        }
        return groups;
    }

    /** Una IPv4 como los dos grupos de 16 bits que ocupa al final de una IPv6. */
    private static int[] ipv4Groups(String text) {
        var ipv4 = IPV4.matcher(text);
        if (!ipv4.matches()) {
            return null;
        }
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            octets[i] = Integer.parseInt(ipv4.group(i + 1));
            if (octets[i] > 255) {
                return null;
            }
        }
        return new int[] {octets[0] << 8 | octets[1], octets[2] << 8 | octets[3]};
    }

    /** Valor de un grupo de 1 a 4 dígitos hexadecimales, o {@code -1} si no lo es. */
    private static int hexGroup(String group) {
        return HEX_GROUP.matcher(group).matches() ? Integer.parseInt(group, 16) : -1;
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
