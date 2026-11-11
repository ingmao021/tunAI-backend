package com.tunai.api.adapter.out.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.domain.model.AuthSession;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AuthAdaptersTest {

    private static final String SECRET = "secreto-de-prueba-con-mas-de-32-caracteres";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-11T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void hashMatchesOnlyTheRightPassword() {
        String stored = PasswordHash.hash("clave-correcta");

        assertThat(PasswordHash.matches("clave-correcta", stored)).isTrue();
        assertThat(PasswordHash.matches("otra", stored)).isFalse();
        assertThat(stored).doesNotContain("clave-correcta");
    }

    @Test
    void hashesOfTheSamePasswordDifferBecauseOfTheSalt() {
        assertThat(PasswordHash.hash("x")).isNotEqualTo(PasswordHash.hash("x"));
    }

    @Test
    void malformedStoredHashNeverMatches() {
        assertThat(PasswordHash.matches("x", "no-es-un-hash")).isFalse();
        assertThat(PasswordHash.matches("x", "abc:!!:!!")).isFalse();
    }

    @Test
    void verifierAcceptsConfiguredUsersCaseInsensitively() {
        String users = "Demo:" + PasswordHash.hash("clave1") + ", ana:" + PasswordHash.hash("clave2");
        ConfiguredCredentialVerifier verifier = new ConfiguredCredentialVerifier(users);

        assertThat(verifier.userCount()).isEqualTo(2);
        assertThat(verifier.verify("demo", "clave1")).isTrue();
        assertThat(verifier.verify("ANA", "clave2")).isTrue();
        assertThat(verifier.verify("demo", "clave2")).isFalse();
        assertThat(verifier.verify("nadie", "clave1")).isFalse();
    }

    @Test
    void verifierWithoutUsersRejectsEveryone() {
        ConfiguredCredentialVerifier verifier = new ConfiguredCredentialVerifier("");

        assertThat(verifier.userCount()).isZero();
        assertThat(verifier.verify("demo", "x")).isFalse();
        assertThat(new ConfiguredCredentialVerifier(null).userCount()).isZero();
    }

    @Test
    void verifierRejectsMalformedConfiguration() {
        assertThatThrownBy(() -> new ConfiguredCredentialVerifier("sin-hash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfiguredCredentialVerifier("demo:"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void issuerSignsAUsernameAndExpiry() {
        HmacSessionIssuer issuer =
                new HmacSessionIssuer(SECRET.getBytes(StandardCharsets.UTF_8), Duration.ofDays(30), CLOCK);

        AuthSession session = issuer.issue("demo");

        assertThat(session.username()).isEqualTo("demo");
        assertThat(session.expiresAt()).isEqualTo(Instant.parse("2026-12-11T10:00:00Z"));
        String[] parts = session.token().split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8))
                .isEqualTo("demo");
        assertThat(parts[1]).isEqualTo(String.valueOf(session.expiresAt().getEpochSecond()));
    }

    @Test
    void differentSecretsGiveDifferentSignatures() {
        Duration ttl = Duration.ofDays(1);
        String a = new HmacSessionIssuer("a".repeat(32).getBytes(StandardCharsets.UTF_8), ttl, CLOCK)
                .issue("demo")
                .token();
        String b = new HmacSessionIssuer("b".repeat(32).getBytes(StandardCharsets.UTF_8), ttl, CLOCK)
                .issue("demo")
                .token();

        assertThat(a).isNotEqualTo(b);
    }
}
