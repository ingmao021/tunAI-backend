package com.tunai.api.application.service;

import com.tunai.api.application.port.in.AuthenticateUseCase;
import com.tunai.api.application.port.out.SessionVerifierPort;
import com.tunai.api.domain.exception.InvalidTokenException;
import com.tunai.api.domain.model.AccountId;
import java.util.Objects;

public class AuthenticationService implements AuthenticateUseCase {

    private final SessionVerifierPort sessions;

    public AuthenticationService(SessionVerifierPort sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public AccountId authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException();
        }
        return sessions.verify(token.strip()).map(AccountId::new).orElseThrow(InvalidTokenException::new);
    }
}
