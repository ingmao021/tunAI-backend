package com.tunai.api.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.domain.exception.InvalidTokenException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthenticationServiceTest {

    private final AuthenticationService service =
            new AuthenticationService(token -> token.equals("bueno") ? Optional.of("Demo") : Optional.empty());

    @Test
    void aValidTokenIdentifiesTheAccount() {
        assertThat(service.authenticate(" bueno ").value()).isEqualTo("demo");
    }

    @Test
    void invalidMissingOrBlankTokensAreRejected() {
        assertThatThrownBy(() -> service.authenticate("malo")).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.authenticate(null)).isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.authenticate("  ")).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void theVerifierIsRequired() {
        assertThatThrownBy(() -> new AuthenticationService(null)).isInstanceOf(NullPointerException.class);
    }
}
