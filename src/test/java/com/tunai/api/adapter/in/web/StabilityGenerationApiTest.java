package com.tunai.api.adapter.in.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.tunai.api.TestImages;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Flujo F-BE-01 completo con el proveedor {@code stability} apuntando a WireMock: éxito, y los fallos del proveedor
 * liberan la cuota (CA-BE-07).
 */
@SpringBootTest(
        properties = {
            "tunai.generator.provider=stability",
            "tunai.stability.api-key=sk-test-e2e",
            "tunai.stability.read-timeout=2s",
            "tunai.quota.per-installation=1",
            "tunai.quota.per-ip=1000",
            "tunai.quota.global-daily=1000"
        })
@AutoConfigureMockMvc
class StabilityGenerationApiTest {

    private static final String PATH = "/v2beta/3d/stable-fast-3d";

    @RegisterExtension
    static WireMockExtension stability = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void stabilityUrl(DynamicPropertyRegistry registry) {
        registry.add("tunai.stability.base-url", stability::baseUrl);
    }

    @Autowired
    MockMvcTester mvc;

    private final String installation = UUID.randomUUID().toString();

    @BeforeEach
    void reset() {
        stability.resetAll();
    }

    @Test
    void aSuccessfulGenerationReturnsTheProviderGlb() {
        byte[] glb = "glTF-real-model".getBytes(StandardCharsets.US_ASCII);
        stability.stubFor(
                post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody(glb)));

        MvcTestResult result = generate();

        assertThat(result)
                .hasStatusOk()
                .hasContentType("model/gltf-binary")
                .headers()
                .hasValue("X-Quota-Remaining", 0);
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(glb);
    }

    @Test
    void aProviderErrorAnswers502AndDoesNotConsumeQuota() {
        stability.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertError(generate(), 502, "PROVIDER_ERROR");
        assertThat(remaining()).isEqualTo(1);
    }

    @Test
    void moderationAnswers422AndDoesNotConsumeQuota() {
        stability.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(403).withBody("{\"name\":\"content_moderation\"}")));

        assertError(generate(), 422, "PROVIDER_REJECTED");
        assertThat(remaining()).isEqualTo(1);
    }

    @Test
    void noCreditsAnswers503AndDoesNotConsumeQuota() {
        stability.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(402)));

        assertError(generate(), 503, "SERVICE_UNAVAILABLE");
        assertThat(remaining()).isEqualTo(1);
    }

    @Test
    void aTimeoutAnswers504AndDoesNotConsumeQuota() {
        stability.stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withBody("glTF").withFixedDelay(4000)));

        assertError(generate(), 504, "PROVIDER_TIMEOUT");
        assertThat(remaining()).isEqualTo(1);
    }

    private MvcTestResult generate() {
        return mvc.post()
                .uri("/api/v1/generations")
                .header("X-Installation-Id", installation)
                .multipart()
                .file("image", TestImages.jpeg(640, 512))
                .exchange();
    }

    private int remaining() {
        MvcTestResult quota = mvc.get()
                .uri("/api/v1/quota")
                .header("X-Installation-Id", installation)
                .exchange();
        return Integer.parseInt(quota.getResponse().getHeader("X-Quota-Remaining"));
    }

    private static void assertError(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo(code));
    }
}
