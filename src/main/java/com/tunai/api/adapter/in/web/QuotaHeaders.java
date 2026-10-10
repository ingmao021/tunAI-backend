package com.tunai.api.adapter.in.web;

import com.tunai.api.domain.model.QuotaStatus;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

/** Headers de cuota (RF-BE-015). {@code X-Quota-Reset} va en ISO-8601 UTC. */
final class QuotaHeaders {

    static final String LIMIT = "X-Quota-Limit";
    static final String REMAINING = "X-Quota-Remaining";
    static final String RESET = "X-Quota-Reset";

    private QuotaHeaders() {}

    static void write(HttpHeaders headers, QuotaStatus status) {
        headers.set(LIMIT, String.valueOf(status.limit()));
        headers.set(REMAINING, String.valueOf(status.remaining()));
        headers.set(RESET, status.resetsAt().toString());
    }

    static void write(HttpServletResponse response, QuotaStatus status) {
        response.setHeader(LIMIT, String.valueOf(status.limit()));
        response.setHeader(REMAINING, String.valueOf(status.remaining()));
        response.setHeader(RESET, status.resetsAt().toString());
    }
}
