package com.tunai.api.adapter.in.web;

import com.tunai.api.application.port.in.GetQuotaUseCase;
import com.tunai.api.domain.model.ClientIp;
import com.tunai.api.domain.model.InstallationId;
import com.tunai.api.domain.model.QuotaStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** F-BE-02: {@code GET /api/v1/quota} (RF-BE-002). No consume cuota. */
@RestController
@RequestMapping("/api/v1")
public class QuotaController {

    private final GetQuotaUseCase getQuota;

    public QuotaController(GetQuotaUseCase getQuota) {
        this.getQuota = getQuota;
    }

    @GetMapping("/quota")
    public ResponseEntity<QuotaResponse> quota(InstallationId installationId, ClientIp clientIp) {
        QuotaStatus status = getQuota.getQuota(installationId, clientIp);
        HttpHeaders headers = new HttpHeaders();
        QuotaHeaders.write(headers, status);
        return ResponseEntity.ok().headers(headers).body(QuotaResponse.of(status));
    }

    /** Contrato §15.2: {@code { "limit": 2, "remaining": 1, "resetsAt": "2026-10-04T05:00:00Z" }}. */
    public record QuotaResponse(int limit, int remaining, String resetsAt) {

        static QuotaResponse of(QuotaStatus status) {
            return new QuotaResponse(
                    status.limit(), status.remaining(), status.resetsAt().toString());
        }
    }
}
