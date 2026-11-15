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
import com.tunai.api.adapter.out.auth.HmacSessionIssuer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
            "tunai.tripo.task-deadline=30s",
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

    @Autowired
    HmacSessionIssuer issuer;

    /** Para que cada prueba use una foto distinta. */
    private static final AtomicInteger PHOTOS = new AtomicInteger();

    private final String installation = UUID.randomUUID().toString();

    /**
     * Una foto distinta por prueba: tras un timeout la generación sigue en segundo plano dentro del mismo contexto de
     * Spring, y otra prueba con la misma foto se uniría a ella en vez de empezar la suya.
     */
    private final byte[] photo = TestImages.jpeg(640 + PHOTOS.incrementAndGet(), 512);

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

    /**
     * El fallo que gastó créditos sin entregar nada: Tripo tarda más que la espera de la petición. Ahora el 504 avisa
     * que la generación sigue, y la retoma del cliente recoge el modelo de <b>la misma</b> tarea.
     */
    @Test
    void aTimeoutIsResumableAndTheResumeDeliversTheModelOfTheSameTask() {
        byte[] glb = "glTF-resumed-model".getBytes(StandardCharsets.US_ASCII);
        uploadAndCreateOk();
        taskIs("running", "");

        MvcTestResult timedOut = generate();

        assertError(timedOut, 504, "PROVIDER_TIMEOUT");
        assertThat(timedOut)
                .bodyJson()
                .hasPathSatisfying("$.resumable", v -> assertThat(v).isEqualTo(true));
        assertThat(timedOut).headers().containsHeader("Retry-After");

        taskIs("success", ",\"output\":{\"model_url\":\"" + tripo.baseUrl() + MODEL_PATH + "\"}");
        tripo.stubFor(get(urlEqualTo(MODEL_PATH))
                .willReturn(aResponse().withStatus(200).withBody(glb)));

        MvcTestResult resumed = resume();

        assertThat(resumed).hasStatusOk().hasContentType("model/gltf-binary");
        assertThat(resumed.getResponse().getContentAsByteArray()).isEqualTo(glb);
        assertThat(remaining()).isZero();
        tripo.verify(1, postRequestedFor(urlEqualTo(FILES_URL)));
        tripo.verify(1, postRequestedFor(urlEqualTo(CREATE_URL)));
    }

    /** El {@code Reintentar} de la app 1.0.1 no envía el header: también se une a la tarea en vez de crear otra. */
    @Test
    void aManualRetryWithTheSamePhotoJoinsTheRunningTask() {
        byte[] glb = "glTF-retried-model".getBytes(StandardCharsets.US_ASCII);
        uploadAndCreateOk();
        taskIs("running", "");
        assertError(generate(), 504, "PROVIDER_TIMEOUT");

        taskIs("success", ",\"output\":{\"model_url\":\"" + tripo.baseUrl() + MODEL_PATH + "\"}");
        tripo.stubFor(get(urlEqualTo(MODEL_PATH))
                .willReturn(aResponse().withStatus(200).withBody(glb)));

        MvcTestResult retried = generate();

        assertThat(retried).hasStatusOk();
        assertThat(retried.getResponse().getContentAsByteArray()).isEqualTo(glb);
        tripo.verify(1, postRequestedFor(urlEqualTo(CREATE_URL)));
    }

    /**
     * Sin nada que retomar (el servicio se reinició y perdió la generación), la retoma automática no crea otra tarea:
     * no toca Tripo y no gasta la cuota del usuario.
     */
    @Test
    void aResumeWithNothingPendingAnswers502AndNeverTouchesTheProvider() {
        uploadAndCreateOk();
        taskIs("success", ",\"output\":{\"model_url\":\"" + tripo.baseUrl() + MODEL_PATH + "\"}");

        assertError(resume(), 502, "PROVIDER_ERROR");

        assertThat(tripo.getAllServeEvents()).isEmpty();
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
                .header("Authorization", "Bearer " + issuer.issue(installation).token())
                .multipart()
                .file("image", photo)
                .exchange();
    }

    /** La misma foto, marcada como retoma automática del cliente. */
    private MvcTestResult resume() {
        return mvc.post()
                .uri("/api/v1/generations")
                .header("Authorization", "Bearer " + issuer.issue(installation).token())
                .header(GenerationController.RESUME_HEADER, "true")
                .multipart()
                .file("image", photo)
                .exchange();
    }

    private int remaining() {
        MvcTestResult quota = mvc.get()
                .uri("/api/v1/quota")
                .header("Authorization", "Bearer " + issuer.issue(installation).token())
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
