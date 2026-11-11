package com.tunai.api.adapter.in.web;

import com.tunai.api.application.port.in.LoginUseCase;
import com.tunai.api.domain.model.AuthSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/auth/login}: usuario y contraseña a cambio de un token de sesión. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final LoginUseCase login;

    public AuthController(LoginUseCase login) {
        this.login = login;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        AuthSession session = login.login(request.username(), request.password());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new LoginResponse(
                        session.username(), session.token(), session.expiresAt().toString()));
    }

    public record LoginRequest(String username, String password) {}

    /** Contrato: {@code { "username": "demo", "token": "...", "expiresAt": "2026-12-11T05:00:00Z" }}. */
    public record LoginResponse(String username, String token, String expiresAt) {}
}
