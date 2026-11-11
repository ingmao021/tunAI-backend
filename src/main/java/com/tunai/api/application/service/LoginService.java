package com.tunai.api.application.service;

import com.tunai.api.application.port.in.LoginUseCase;
import com.tunai.api.application.port.out.CredentialVerifierPort;
import com.tunai.api.application.port.out.SessionIssuerPort;
import com.tunai.api.domain.exception.InvalidCredentialsException;
import com.tunai.api.domain.model.AuthSession;
import java.util.Objects;

public class LoginService implements LoginUseCase {

    private final CredentialVerifierPort credentials;
    private final SessionIssuerPort sessions;

    public LoginService(CredentialVerifierPort credentials, SessionIssuerPort sessions) {
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public AuthSession login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new InvalidCredentialsException();
        }
        String normalized = username.strip();
        if (!credentials.verify(normalized, password)) {
            throw new InvalidCredentialsException();
        }
        return sessions.issue(normalized);
    }
}
