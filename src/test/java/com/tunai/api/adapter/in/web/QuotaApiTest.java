package com.tunai.api.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tunai.api.TestImages;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * F-BE-02 y la cuota en el flujo de generación (CA-BE-03). Cada prueba usa su propia instalación e IP; el límite global
 * se sube para que las pruebas no interfieran entre sí (el global se prueba en el contrato del puerto).
 */
@SpringBootTest(
        properties = {
            "tunai.generator.fake.delay=0s",
            "tunai.quota.per-installation=2",
            "tunai.quota.per-ip=3",
            "tunai.quota.global-daily=1000"
        })
@AutoConfigureMockMvc
class QuotaApiTest {

    @Autowired
    MockMvcTester mvc;

    private final String installation = UUID.randomUUID().toString();
    private final String ip = "190.24." + ThreadLocalRandom.current().nextInt(256) + "."
            + ThreadLocalRandom.current().nextInt(256);

    @Test
    void quotaEndpointReturnsTheContractWithoutConsumingQuota() {
        MvcTestResult first = quota(installation, ip);
        quota(installation, ip);

        assertThat(first)
                .hasStatusOk()
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .headers()
                .hasValue("X-Quota-Limit", 2)
                .hasValue("X-Quota-Remaining", 2)
                .containsHeader("X-Quota-Reset");
        assertThat(first)
                .bodyJson()
                .hasPathSatisfying("$.limit", v -> assertThat(v).asNumber().isEqualTo(2))
                .hasPathSatisfying("$.remaining", v -> assertThat(v).asNumber().isEqualTo(2))
                .hasPathSatisfying(
                        "$.resetsAt", v -> assertThat(v).asString().matches("\\d{4}-\\d{2}-\\d{2}T05:00:00Z"));
        assertThat(quota(installation, ip))
                .bodyJson()
                .hasPathSatisfying("$.remaining", v -> assertThat(v).asNumber().isEqualTo(2));
    }

    @Test
    void quotaEndpointRequiresTheInstallationId() {
        MvcTestResult result = mvc.get().uri("/api/v1/quota").exchange();

        assertThat(result).hasStatus(400).bodyJson().hasPathSatisfying("$.code", v -> assertThat(v)
                .asString()
                .isEqualTo("INVALID_INSTALLATION_ID"));
    }

    @Test
    void theThirdSuccessfulGenerationOfTheDayIsRejectedWith429() {
        assertThat(generate(installation, ip)).hasStatusOk().headers().hasValue("X-Quota-Remaining", 1);
        assertThat(generate(installation, ip)).hasStatusOk().headers().hasValue("X-Quota-Remaining", 0);

        MvcTestResult third = generate(installation, ip);

        assertThat(third)
                .hasStatus(429)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .headers()
                .hasValue("X-Quota-Remaining", 0)
                .containsHeaders("Retry-After", "X-Quota-Limit", "X-Quota-Reset");
        assertThat(third)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo("QUOTA_EXCEEDED"));
        assertThat(quota(installation, ip))
                .bodyJson()
                .hasPathSatisfying("$.remaining", v -> assertThat(v).asNumber().isEqualTo(0));
    }

    @Test
    void anInvalidImageDoesNotConsumeQuota() {
        MvcTestResult invalid = mvc.post()
                .uri("/api/v1/generations")
                .header("X-Installation-Id", installation)
                .header("CF-Connecting-IP", ip)
                .multipart()
                .file("image", TestImages.png(100, 100))
                .exchange();

        assertThat(invalid).hasStatus(400);
        assertThat(quota(installation, ip))
                .bodyJson()
                .hasPathSatisfying("$.remaining", v -> assertThat(v).asNumber().isEqualTo(2));
    }

    @Test
    void inventedInstallationIdsAreStoppedByTheIpLimit() {
        generate(UUID.randomUUID().toString(), ip);
        generate(UUID.randomUUID().toString(), ip);
        generate(UUID.randomUUID().toString(), ip);

        MvcTestResult fourth = generate(UUID.randomUUID().toString(), ip);

        assertThat(fourth).hasStatus(429).headers().hasValue("X-Quota-Limit", 3).hasValue("X-Quota-Remaining", 0);
    }

    @Test
    void theClientCannotEscapeTheIpLimitWithASpoofedForwardedFor() {
        for (int i = 0; i < 3; i++) {
            generateSpoofing(UUID.randomUUID().toString(), ip, "6.6.6." + i);
        }

        assertThat(generateSpoofing(UUID.randomUUID().toString(), ip, "7.7.7.7"))
                .hasStatus(429);
    }

    private MvcTestResult quota(String installationId, String clientIp) {
        return mvc.get()
                .uri("/api/v1/quota")
                .header("X-Installation-Id", installationId)
                .header("CF-Connecting-IP", clientIp)
                .exchange();
    }

    private MvcTestResult generate(String installationId, String clientIp) {
        return mvc.post()
                .uri("/api/v1/generations")
                .header("X-Installation-Id", installationId)
                .header("CF-Connecting-IP", clientIp)
                .multipart()
                .file("image", TestImages.jpeg(640, 512))
                .exchange();
    }

    private MvcTestResult generateSpoofing(String installationId, String clientIp, String spoofed) {
        return mvc.post()
                .uri("/api/v1/generations")
                .header("X-Installation-Id", installationId)
                .header("CF-Connecting-IP", clientIp)
                .header("X-Forwarded-For", spoofed + ", " + clientIp)
                .multipart()
                .file("image", TestImages.jpeg(640, 512))
                .exchange();
    }
}
