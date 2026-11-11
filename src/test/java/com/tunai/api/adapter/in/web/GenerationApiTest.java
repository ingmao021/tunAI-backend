package com.tunai.api.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tunai.api.TestImages;
import com.tunai.api.adapter.out.auth.HmacSessionIssuer;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.domain.model.GeneratedModel;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * F-BE-01 de extremo a extremo con un generador simulado (flujos A1 y A2); el adaptador real de Tripo se prueba en
 * {@link TripoGenerationApiTest}. Los límites se suben para que la cuota no interfiera; la cuota se prueba en
 * {@link QuotaApiTest}.
 */
@SpringBootTest(
        properties = {"tunai.quota.initial-credits=15000", "tunai.quota.per-ip=1000", "tunai.quota.global-daily=1000"})
@AutoConfigureMockMvc
class GenerationApiTest {

    private static final String URI = "/api/v1/generations";
    private static final String ACCOUNT = "demo";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    HmacSessionIssuer issuer;

    @MockitoBean
    ModelGeneratorPort generator;

    @BeforeEach
    void stubGenerator() {
        when(generator.generate(any()))
                .thenReturn(GeneratedModel.fromGlb("glTF-model".getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * El registro publica por intervalos ({@code count()} devuelve el intervalo anterior), así que aquí se verifica que
     * los medidores existan con sus etiquetas; el conteo exacto lo prueba {@code MeteredModelGeneratorTest}.
     */
    @Test
    void eachGenerationIsMeasured() {
        generate(ACCOUNT, TestImages.jpeg(800, 600));

        assertThat(meterRegistry
                        .find("tunai.generations")
                        .tags("provider", "tripo", "result", "success")
                        .counter())
                .isNotNull();
        assertThat(meterRegistry
                        .find("tunai.provider.latency")
                        .tags("provider", "tripo", "result", "success")
                        .timer())
                .isNotNull();
    }

    @Test
    void returnsTheGlbForAValidPhoto() {
        MvcTestResult result = generate(ACCOUNT, TestImages.jpeg(800, 600));

        assertThat(result).hasStatusOk().hasContentType("model/gltf-binary");
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(Arrays.copyOf(body, 4)).isEqualTo("glTF".getBytes(StandardCharsets.US_ASCII));
        assertThat(result.getResponse().getContentLengthLong()).isEqualTo(body.length);
        assertThat(result)
                .headers()
                .containsHeaders("X-Quota-Limit", "X-Quota-Remaining", "X-Quota-Reset", "X-Trace-Id");
    }

    @Test
    void acceptsPng() {
        assertThat(generate(ACCOUNT, TestImages.png(512, 512))).hasStatusOk();
    }

    @Test
    void missingTokenIsRejected() {
        assertError(generateWithAuthorization(null, TestImages.jpeg(800, 600)), 401, "UNAUTHORIZED");
    }

    @Test
    void invalidTokenIsRejected() {
        assertError(generateWithAuthorization("Bearer basura", TestImages.jpeg(800, 600)), 401, "UNAUTHORIZED");
    }

    @Test
    void theTokenIsCheckedBeforeTheImage() {
        assertError(
                generateWithAuthorization("Bearer basura", "not an image".getBytes(StandardCharsets.UTF_8)),
                401,
                "UNAUTHORIZED");
    }

    @Test
    void bytesThatAreNotAnImageAreRejected() {
        assertError(generate(ACCOUNT, "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)), 400, "INVALID_IMAGE");
    }

    @Test
    void anImageTooSmallIsRejected() {
        assertError(generate(ACCOUNT, TestImages.png(800, 300)), 400, "INVALID_IMAGE");
    }

    @Test
    void anotherImageFormatIsUnsupported() {
        assertError(generate(ACCOUNT, TestImages.webpHeader()), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void theContentTypeOfThePartIsNotTrusted() {
        MvcTestResult result = mvc.post()
                .uri(URI)
                .header("Authorization", bearer(ACCOUNT))
                .multipart()
                .file(new org.springframework.mock.web.MockMultipartFile(
                        "image", "carro.png", "image/png", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                .exchange();

        assertError(result, 400, "INVALID_IMAGE");
    }

    @Test
    void aMissingImagePartIsRejected() {
        MvcTestResult result = mvc.post()
                .uri(URI)
                .header("Authorization", bearer(ACCOUNT))
                .multipart()
                .param("other", "x")
                .exchange();

        assertError(result, 400, "INVALID_IMAGE");
    }

    @Test
    void aNonMultipartRequestIsRejected() {
        MvcTestResult result = mvc.post()
                .uri(URI)
                .header("Authorization", bearer(ACCOUNT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertError(result, 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void unknownRoutesAnswerProblemJson() {
        assertError(mvc.get().uri("/api/v1/nope").exchange(), 404, "NOT_FOUND");
    }

    @Test
    void wrongMethodAnswersProblemJsonWithAllow() {
        MvcTestResult result = mvc.get().uri(URI).exchange();

        assertError(result, 405, "METHOD_NOT_ALLOWED");
        assertThat(result).headers().containsHeader("Allow");
    }

    private MvcTestResult generate(String account, byte[] image) {
        return generateWithAuthorization(bearer(account), image);
    }

    private MvcTestResult generateWithAuthorization(String authorization, byte[] image) {
        var request = mvc.post().uri(URI);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return request.multipart().file("image", image).exchange();
    }

    private String bearer(String account) {
        return "Bearer " + issuer.issue(account).token();
    }

    private static void assertError(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo(code));
    }
}
