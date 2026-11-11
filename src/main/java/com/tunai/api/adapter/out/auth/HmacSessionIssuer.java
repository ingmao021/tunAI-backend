package com.tunai.api.adapter.out.auth;

import com.tunai.api.application.port.out.SessionIssuerPort;
import com.tunai.api.application.port.out.SessionVerifierPort;
import com.tunai.api.domain.model.AuthSession;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Token sin estado: {@code usuario.expiración.firma}, firmado con HMAC-SHA256. No guarda sesiones en el servidor: la
 * validez se comprueba con la firma y la fecha.
 */
public class HmacSessionIssuer implements SessionIssuerPort, SessionVerifierPort {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int PARTS = 3;

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    public HmacSessionIssuer(byte[] secret, Duration ttl, Clock clock) {
        this.secret = secret.clone();
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public AuthSession issue(String username) {
        Instant expiresAt = clock.instant().plus(ttl).truncatedTo(ChronoUnit.SECONDS);
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String payload =
                base64.encodeToString(username.getBytes(StandardCharsets.UTF_8)) + "." + expiresAt.getEpochSecond();
        return new AuthSession(username, payload + "." + base64.encodeToString(sign(payload)), expiresAt);
    }

    @Override
    public Optional<String> verify(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != PARTS) {
            return Optional.empty();
        }
        String payload = parts[0] + "." + parts[1];
        byte[] expected = sign(payload);
        try {
            byte[] given = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, given)) {
                return Optional.empty();
            }
            if (Instant.ofEpochSecond(Long.parseLong(parts[1])).isBefore(clock.instant())) {
                return Optional.empty();
            }
            return Optional.of(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC no disponible", e);
        }
    }
}
