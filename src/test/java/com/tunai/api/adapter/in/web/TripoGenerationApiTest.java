package com.tunai.api.adapter.in.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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
 * Flujo F-BE-01 completo con Tripo apuntando a WireMock: éxito, y los fallos del proveedor liberan la cuota
 * (CA-BE-07). Nunca se llama a Tripo real (RN-15).
 */
@SpringBootTest(
        properties = {
            "tunai.tripo.api-key=tsk_test-e2e",
            "tunai.tripo.read-timeout=5s",
            "tunai.tripo.poll-interval=20ms",
            "tunai.tripo.max-wait=5s",
            "tunai.quota.initial-credits=15",
            "tunai.quota.per-ip=1000",
            "tunai.quota.global-daily=1000"
        })
@AutoConfigureMockMvc
class TripoGenerationApiTest {

    private static final String FILES_URL = "/v3/files";
    private static final String CREATE_URL = "/v3/generation/image-to-model";
    private static final String TASK_URL = "/v3/tasks/task_e2e";
    private static final String MODEL_PATH = "/cdn/model.glb";

    @RegisterExtension
    static WireMockExtension tripo = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void tripoUrl(DynamicPropertyRegistry registry) {
        registry.add("tunai.tripo.base-url", () -> tripo.baseUrl() + "/v3");
    }

    @Autowired
    MockMvcTester mvc;

    private final String installation = UUID.randomUUID().toString();

    @BeforeEach
    void reset() {
        tripo.resetAll();
        balanceIs("100.00");
    }

    @Test
    void aSuccessfulGenerationReturnsTheProviderGlb() {
        byte[] glb = "glTF-real-model".getBytes(StandardCharsets.US_ASCII);
        uploadAndCreateOk();
        taskIs("success", ",\"output\":{\"model_url\":\"" + tripo.baseUrl() + MODEL_PATH + "\"}");
        tripo.stubFor(get(urlEqualTo(MODEL_PATH))
                .willReturn(aResponse().withStatus(200).withBody(glb)));

        MvcTestResult result = generate();

        assertThat(result)
                .hasStatusOk()
                .hasContentType("model/gltf-binary")
                .headers()
                .hasValue("X-Quota-Remaining", 0);
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(glb);
    }

    @Test
    void aFailedTaskAnswers502AndDoesNotConsumeQuota() {
        uploadAndCreateOk();
        taskIs("failed", "");

        assertError(generate(), 502, "PROVIDER_ERROR");
        assertThat(remaining()).isEqualTo(1);
    }

    @Test
    void aBannedPhotoAnswers422AndDoesNotConsumeQuota() {
        uploadAndCreateOk();
        taskIs("banned", "");

        assertError(generate(), 422, "PROVIDER_REJECTED");
        assertThat(remaining()).isEqualTo(1);
    }

    @Test
    void noCreditsAnswers503AndDoesNotConsumeQuota() {
        json("POST", FILES_URL, 200, ok("{\"file_token\":\"file_e2e\"}"));
        json("POST", CREATE_URL, 403, "{\"code\":2010,\"message\":\"insufficient credit\"}");

        assertError(generate(), 503, "SERVICE_UNAVAILABLE");
        assertThat(remaining()).isEqualTo(1);
    }

    /** RN-17: sin saldo en Tripo no se sube la foto ni se crea la tarea. */
    @Test
    void aLowProviderBalanceAnswers503AndDoesNotConsumeQuota() {
        balanceIs("10.00");

        assertError(generate(), 503, "SERVICE_UNAVAILABLE");
        assertThat(remaining()).isEqualTo(1);
        tripo.verify(0, postRequestedFor(urlEqualTo(FILES_URL)));
    }

    /** La tarea pudo terminar y cobrarse: el usuario no pierde créditos (la cuota diaria sí lo cuenta). */
    @Test
    void aTaskThatNeverFinishesAnswers504AndDoesNotConsumeQuota() {
        uploadAndCreateOk();
        taskIs("running", "");

        assertError(generate(), 504, "PROVIDER_TIMEOUT");
        assertThat(remaining()).isEqualTo(1);
    }

    private static String ok(String data) {
        return "{\"code\":0,\"data\":" + data + "}";
    }

    private static void balanceIs(String balance) {
        json("GET", "/v3/account/balance", 200, ok("{\"balance\":" + balance + ",\"frozen\":0}"));
    }

    private static void uploadAndCreateOk() {
        json("POST", FILES_URL, 200, ok("{\"file_token\":\"file_e2e\"}"));
        json("POST", CREATE_URL, 200, ok("{\"task_id\":\"task_e2e\"}"));
    }

    private static void taskIs(String status, String extra) {
        json("GET", TASK_URL, 200, ok("{\"task_id\":\"task_e2e\",\"status\":\"" + status + "\"" + extra + "}"));
    }

    private static void json(String method, String url, int status, String body) {
        var response = aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
        tripo.stubFor(("GET".equals(method) ? get(urlEqualTo(url)) : post(urlEqualTo(url))).willReturn(response));
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
