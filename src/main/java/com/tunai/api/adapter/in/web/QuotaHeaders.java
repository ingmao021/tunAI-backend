package com.tunai.api.adapter.in.web;

import com.tunai.api.domain.model.QuotaStatus;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

/**
 * Headers de cuota (RF-BE-015). {@code X-Quota-Reset} va en ISO-8601 UTC. {@code X-Credits-Balance} y
 * {@code X-Credits-Per-Model} llevan el saldo de créditos de la instalación y el costo de cada modelo.
 */
final class QuotaHeaders {

    static final String LIMIT = "X-Quota-Limit";
    static final String REMAINING = "X-Quota-Remaining";
    static final String RESET = "X-Quota-Reset";
    static final String CREDITS = "X-Credits-Balance";
    static final String CREDITS_PER_MODEL = "X-Credits-Per-Model";

    private QuotaHeaders() {}

    static void write(HttpHeaders headers, QuotaStatus status) {
        headers.set(LIMIT, String.valueOf(status.limit()));
        headers.set(REMAINING, String.valueOf(status.remaining()));
        headers.set(RESET, status.resetsAt().toString());
        headers.set(CREDITS, String.valueOf(status.credits()));
        headers.set(CREDITS_PER_MODEL, String.valueOf(status.creditsPerModel()));
    }

    static void write(HttpServletResponse response, QuotaStatus status) {
        response.setHeader(LIMIT, String.valueOf(status.limit()));
        response.setHeader(REMAINING, String.valueOf(status.remaining()));
        response.setHeader(RESET, status.resetsAt().toString());
        response.setHeader(CREDITS, String.valueOf(status.credits()));
        response.setHeader(CREDITS_PER_MODEL, String.valueOf(status.creditsPerModel()));
    }
}
