package com.tunai.api.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.domain.exception.InvalidCredentialsException;
import com.tunai.api.domain.model.AuthSession;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginServiceTest {

    private final LoginService service = new LoginService(
            (user, password) -> user.equals("demo") && password.equals("clave"),
            user -> new AuthSession(user, "token-" + user, Instant.parse("2026-12-01T00:00:00Z")));

    @Test
    void validCredentialsIssueASession() {
        AuthSession session = service.login("  demo ", "clave");

        assertThat(session.username()).isEqualTo("demo");
        assertThat(session.token()).isEqualTo("token-demo");
    }

    @Test
    void wrongPasswordIsRejected() {
        assertThatThrownBy(() -> service.login("demo", "mala")).isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void blankOrMissingFieldsAreRejected() {
        assertThatThrownBy(() -> service.login(null, "clave")).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.login("  ", "clave")).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.login("demo", null)).isInstanceOf(InvalidCredentialsException.class);
        assertThatThrownBy(() -> service.login("demo", "")).isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void collaboratorsAreRequired() {
        assertThatThrownBy(() -> new LoginService(null, user -> null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoginService((u, p) -> true, null)).isInstanceOf(NullPointerException.class);
    }
}
