package com.tunai.api.adapter.out.auth;

import com.tunai.api.application.port.out.CredentialVerifierPort;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Credenciales definidas por el operador (variable {@code TUNAI_AUTH_USERS}): {@code usuario:hash,usuario:hash}, con
 * el hash de {@link PasswordHash}. No hay registro de usuarios ni contraseñas en texto plano.
 */
public class ConfiguredCredentialVerifier implements CredentialVerifierPort {

    /** Hash de relleno: un usuario desconocido cuesta lo mismo que uno conocido con mala contraseña. */
    private static final String FILLER = PasswordHash.hash("relleno");

    private final Map<String, String> hashes;

    public ConfiguredCredentialVerifier(String users) {
        this.hashes = parse(users);
    }

    @Override
    public boolean verify(String username, String password) {
        String stored = hashes.get(username.toLowerCase(Locale.ROOT));
        if (stored == null) {
            PasswordHash.matches(password, FILLER);
            return false;
        }
        return PasswordHash.matches(password, stored);
    }

    public int userCount() {
        return hashes.size();
    }

    private static Map<String, String> parse(String users) {
        Map<String, String> result = new HashMap<>();
        if (users == null || users.isBlank()) {
            return result;
        }
        for (String entry : users.split(",")) {
            int separator = entry.indexOf(':');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("TUNAI_AUTH_USERS: cada entrada debe ser usuario:hash");
            }
            result.put(
                    entry.substring(0, separator).strip().toLowerCase(Locale.ROOT),
                    entry.substring(separator + 1).strip());
        }
        return result;
    }
}
