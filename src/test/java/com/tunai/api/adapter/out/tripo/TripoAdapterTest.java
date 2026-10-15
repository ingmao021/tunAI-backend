package com.tunai.api.adapter.out.tripo;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.tunai.api.TestImages;
import com.tunai.api.domain.exception.DomainException;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderRejectedException;
import com.tunai.api.domain.exception.ProviderTimeoutException;
import com.tunai.api.domain.exception.ProviderUnavailableException;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.ImageFormat;
import com.tunai.api.domain.model.VehicleImage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Anexo C: el adaptador se prueba solo contra WireMock; nunca contra Tripo real (RN-15). */
class TripoAdapterTest {

    private static final String API_KEY = "tsk_test-0123456789";
    private static final String TASK = "task_abc123";
    private static final String TASK_URL = "/v3/tasks/" + TASK;
    private static final String MODEL_PATH = "/cdn/output/model_pbr.glb";
    private static final byte[] GLB = glb(64);
    private static final VehicleImage JPEG = new VehicleImage(TestImages.jpeg(640, 512), ImageFormat.JPEG, 640, 512);
    private static final TripoOptions OPTIONS = new TripoOptions(
            "v3.1-20260211",
            200_000,
            TripoOptions.TextureQuality.DETAILED,
            TripoOptions.GeometryQuality.STANDARD,
            true);
    private static final int MIN_BALANCE = 40;

    @RegisterExtension
    static WireMockExtension tripo = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private TripoAdapter adapter(Duration readTimeout, Duration maxWait, long maxModelBytes) {
        return TripoAdapter.create(
                URI.create(tripo.baseUrl() + "/v3"),
                API_KEY,
                Duration.ofSeconds(2),
                readTimeout,
                OPTIONS,
                MIN_BALANCE,
                Duration.ofMillis(20),
                maxWait,
                maxModelBytes);
    }

    private TripoAdapter adapter() {
        return adapter(Duration.ofSeconds(5), Duration.ofSeconds(5), 1024);
    }

    @BeforeEach
    void balanceIsEnough() {
        balanceIs("100.00");
    }

    private static void balanceIs(String balance) {
        json("GET", "/v3/account/balance", 200, ok("{\"balance\":" + balance + ",\"frozen\":0.00}"));
    }

    private static boolean charged(Throwable e) {
        return ((DomainException) e).providerMayHaveCharged();
    }

    private static byte[] glb(int size) {
        byte[] data = new byte[size];
        System.arraycopy("glTF".getBytes(StandardCharsets.US_ASCII), 0, data, 0, 4);
        return data;
    }

    private static String ok(String data) {
        return "{\"code\":0,\"data\":" + data + "}";
    }

    private static String task(String status, String output) {
        return ok("{\"task_id\":\"" + TASK + "\",\"status\":\"" + status + "\",\"progress\":50"
                + (output != null ? ",\"output\":" + output : "") + ",\"credits_consumed\":30.00}");
    }

    private String modelUrl() {
        return tripo.baseUrl() + MODEL_PATH;
    }

    private static void json(String method, String url, int status, String body) {
        var response = aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
        tripo.stubFor(("GET".equals(method) ? get(urlEqualTo(url)) : post(urlEqualTo(url))).willReturn(response));
    }

    private static void uploadOk() {
        json("POST", "/v3/files", 200, ok("{\"file_token\":\"file_abc123\"}"));
    }

    private static void createOk() {
        json("POST", "/v3/generation/image-to-model", 200, ok("{\"task_id\":\"" + TASK + "\"}"));
    }

    private void taskSucceeds() {
        json("GET", TASK_URL, 200, task("success", "{\"model_url\":\"" + modelUrl() + "\"}"));
    }

    private static void modelReturns(byte[] body) {
        tripo.stubFor(get(urlEqualTo(MODEL_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "model/gltf-binary")
                        .withBody(body)));
    }

    @Test
    void uploadsCreatesPollsAndDownloadsTheGlb() {
        uploadOk();
        createOk();
        tripo.stubFor(get(urlEqualTo(TASK_URL))
                .inScenario("poll")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(task("queued", null)))
                .willSetStateTo("running"));
        tripo.stubFor(get(urlEqualTo(TASK_URL))
                .inScenario("poll")
                .whenScenarioStateIs("running")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(task("running", null)))
                .willSetStateTo("done"));
        tripo.stubFor(get(urlEqualTo(TASK_URL))
                .inScenario("poll")
                .whenScenarioStateIs("done")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(task("success", "{\"model_url\":\"" + modelUrl() + "\"}"))));
        modelReturns(GLB);

        GeneratedModel model = adapter().generate(JPEG);

        assertThat(model.toByteArray()).isEqualTo(GLB);
        tripo.verify(postRequestedFor(urlEqualTo("/v3/files"))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withHeader("Content-Type", equalTo("image/jpeg"))
                        .build()));
        tripo.verify(postRequestedFor(urlEqualTo("/v3/generation/image-to-model"))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withRequestBody(equalToJson("{\"input\":\"file_abc123\",\"model\":\"v3.1-20260211\",\"texture\":true,"
                        + "\"pbr\":true,\"texture_quality\":\"detailed\",\"geometry_quality\":\"standard\","
                        + "\"face_limit\":200000}")));
        tripo.verify(3, getRequestedFor(urlEqualTo(TASK_URL)));
    }

    /** La URL del modelo es de otro host (CDN): la clave no debe salir de la API (RNF-BE-006). */
    @Test
    void neverSendsTheKeyToTheModelUrl() {
        uploadOk();
        createOk();
        taskSucceeds();
        modelReturns(GLB);

        adapter().generate(JPEG);

        tripo.verify(getRequestedFor(urlEqualTo(MODEL_PATH)).withHeader("Authorization", absent()));
    }

    @Test
    void aBannedTaskIsARejectedPhoto() {
        uploadOk();
        createOk();
        json("GET", TASK_URL, 200, task("banned", null));

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderRejectedException.class)
                .hasMessageContaining(TASK)
                .matches(e -> !charged(e));
    }

    @Test
    void aFailedTaskIsAProviderFailure() {
        uploadOk();
        createOk();
        json("GET", TASK_URL, 200, task("failed", null));

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderFailureException.class)
                .matches(e -> !charged(e), "Tripo devuelve los créditos de una tarea fallida");
    }

    /** {@code expired} llega después de {@code success}: Tripo ya cobró. */
    @Test
    void anExpiredTaskMayHaveBeenCharged() {
        uploadOk();
        createOk();
        json("GET", TASK_URL, 200, task("expired", null));

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderFailureException.class)
                .matches(TripoAdapterTest::charged);
    }

    /** RN-17: el saldo es el tope del gasto que sobrevive a los reinicios; sin él no se sube ni se crea nada. */
    @Test
    void aBalanceBelowTheCostOfAModelMakesTheServiceUnavailable() {
        balanceIs("39.99");

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageContaining("39.99");
        tripo.verify(0, postRequestedFor(urlEqualTo("/v3/files")));
        tripo.verify(0, postRequestedFor(urlEqualTo("/v3/generation/image-to-model")));
    }

    @Test
    void aBalanceResponseWithoutBalanceIsAProviderFailure() {
        json("GET", "/v3/account/balance", 200, ok("{}"));

        assertThatThrownBy(() -> adapter().generate(JPEG)).isInstanceOf(ProviderFailureException.class);
        tripo.verify(0, postRequestedFor(urlEqualTo("/v3/files")));
    }

    @Test
    void contentPolicyIsARejectedPhoto() {
        uploadOk();
        json("POST", "/v3/generation/image-to-model", 400, "{\"code\":2008,\"message\":\"Content policy violation\"}");

        assertThatThrownBy(() -> adapter().generate(JPEG)).isInstanceOf(ProviderRejectedException.class);
    }

    /** Sin créditos o con la clave inválida el servicio no está disponible para nosotros; nunca llega a la tarea. */
    @Test
    void insufficientCreditsMakeTheServiceUnavailable() {
        uploadOk();
        json("POST", "/v3/generation/image-to-model", 403, "{\"code\":2010,\"message\":\"Insufficient credits\"}");

        assertThatThrownBy(() -> adapter().generate(JPEG)).isInstanceOf(ProviderUnavailableException.class);
        tripo.verify(0, getRequestedFor(urlEqualTo(TASK_URL)));
    }

    @Test
    void anInvalidKeyMakesTheServiceUnavailable() {
        json("POST", "/v3/files", 401, "{\"code\":2,\"status\":\"error\",\"message\":\"Authentication required\"}");

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessageNotContaining(API_KEY);
    }

    @Test
    void concurrencyLimitCarriesRetryAfter() {
        uploadOk();
        tripo.stubFor(post(urlEqualTo("/v3/generation/image-to-model"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "7")
                        .withBody("{\"code\":2000,\"message\":\"You have exceeded the limit of generation\"}")));

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderUnavailableException.class)
                .satisfies(e -> assertThat(((DomainException) e).retryAfter()).contains(Duration.ofSeconds(7)));
    }

    /** RN-13: si la tarea no termina a tiempo se avisa, pero nunca se crea otra (cada una cuesta créditos). */
    @Test
    void aTaskThatNeverFinishesTimesOutWithoutRetrying() {
        uploadOk();
        createOk();
        json("GET", TASK_URL, 200, task("running", null));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(5), Duration.ofMillis(200), 1024)
                        .generate(JPEG))
                .isInstanceOf(ProviderTimeoutException.class)
                .hasMessageContaining(TASK)
                .matches(TripoAdapterTest::charged);
        tripo.verify(1, postRequestedFor(urlEqualTo("/v3/generation/image-to-model")));
    }

    /**
     * El plazo cubre todo el flujo, también la descarga: una respuesta lenta no deja al cliente esperando más que el
     * proxy, aunque el timeout de lectura sea mayor.
     */
    @Test
    void theWholeGenerationHasAHardDeadline() {
        uploadOk();
        createOk();
        taskSucceeds();
        tripo.stubFor(get(urlEqualTo(MODEL_PATH))
                .willReturn(aResponse().withStatus(200).withBody(GLB).withFixedDelay(3000)));
        long start = System.nanoTime();

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(10), Duration.ofMillis(500), 1024)
                        .generate(JPEG))
                .isInstanceOf(ProviderTimeoutException.class)
                .hasMessageContaining(TASK)
                .matches(TripoAdapterTest::charged);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void aSlowResponseIsATimeout() {
        tripo.stubFor(post(urlEqualTo("/v3/files"))
                .willReturn(aResponse()
                        .withFixedDelay(1500)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ok("{\"file_token\":\"file_abc123\"}"))));

        assertThatThrownBy(() -> adapter(Duration.ofMillis(300), Duration.ofSeconds(5), 1024)
                        .generate(JPEG))
                .isInstanceOf(ProviderTimeoutException.class)
                .matches(e -> !charged(e), "la tarea no llegó a crearse");
    }

    /** Abandonar la tarea no evita que Tripo la cobre: un fallo pasajero al consultarla se reintenta. */
    @Test
    void aTransientErrorWhilePollingIsRetried() {
        uploadOk();
        createOk();
        tripo.stubFor(get(urlEqualTo(TASK_URL))
                .inScenario("poll")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503).withBody("<html>Service unavailable</html>"))
                .willSetStateTo("recovered"));
        tripo.stubFor(get(urlEqualTo(TASK_URL))
                .inScenario("poll")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(task("success", "{\"model_url\":\"" + modelUrl() + "\"}"))));
        modelReturns(GLB);

        assertThat(adapter().generate(JPEG).toByteArray()).isEqualTo(GLB);
        tripo.verify(2, getRequestedFor(urlEqualTo(TASK_URL)));
    }

    @Test
    void aPersistentServerErrorWhilePollingGivesUpAndMayHaveBeenCharged() {
        uploadOk();
        createOk();
        json("GET", TASK_URL, 500, "{\"code\":5000,\"message\":\"Internal error\"}");

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderFailureException.class)
                .matches(TripoAdapterTest::charged);
        tripo.verify(TripoAdapter.MAX_POLL_FAILURES + 1, getRequestedFor(urlEqualTo(TASK_URL)));
    }

    /** Clave revocada durante la consulta: reintentar no lo arregla. */
    @Test
    void anAuthErrorWhilePollingIsNotRetried() {
        uploadOk();
        createOk();
        json("GET", TASK_URL, 401, "{\"code\":1001,\"message\":\"Invalid key\"}");

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderFailureException.class)
                .matches(TripoAdapterTest::charged);
        tripo.verify(1, getRequestedFor(urlEqualTo(TASK_URL)));
    }

    @Test
    void aSuccessWithoutModelUrlIsAProviderFailure() {
        uploadOk();
        createOk();
        json("GET", TASK_URL, 200, task("success", "{\"rendered_image_url\":\"https://cdn.example/p.png\"}"));

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderFailureException.class)
                .hasMessageContaining("URL de modelo")
                .matches(TripoAdapterTest::charged);
    }

    @Test
    void anOversizedModelIsRejected() {
        uploadOk();
        createOk();
        taskSucceeds();
        modelReturns(glb(2048));

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderFailureException.class)
                .hasMessageContaining("RF-BE-013")
                .matches(TripoAdapterTest::charged);
    }

    @Test
    void aDownloadThatIsNotAGlbIsAProviderFailure() {
        uploadOk();
        createOk();
        taskSucceeds();
        modelReturns(Arrays.copyOf("<html>".getBytes(StandardCharsets.US_ASCII), 64));

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderFailureException.class)
                .matches(TripoAdapterTest::charged);
    }

    @Test
    void aFailedDownloadIsAProviderFailure() {
        uploadOk();
        createOk();
        taskSucceeds();
        tripo.stubFor(get(urlEqualTo(MODEL_PATH)).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderFailureException.class)
                .hasMessageContaining("403")
                .matches(TripoAdapterTest::charged);
        tripo.verify(1, getRequestedFor(urlEqualTo(MODEL_PATH)));
    }

    @Test
    void aServerErrorDownloadingIsRetriedOnce() {
        uploadOk();
        createOk();
        taskSucceeds();
        tripo.stubFor(get(urlEqualTo(MODEL_PATH))
                .inScenario("download")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(502))
                .willSetStateTo("recovered"));
        tripo.stubFor(get(urlEqualTo(MODEL_PATH))
                .inScenario("download")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withBody(GLB)));

        assertThat(adapter().generate(JPEG).toByteArray()).isEqualTo(GLB);
        tripo.verify(TripoAdapter.DOWNLOAD_ATTEMPTS, getRequestedFor(urlEqualTo(MODEL_PATH)));
    }

    @Test
    void aDownloadThatKeepsFailingMayHaveBeenCharged() {
        uploadOk();
        createOk();
        taskSucceeds();
        tripo.stubFor(get(urlEqualTo(MODEL_PATH)).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> adapter().generate(JPEG))
                .isInstanceOf(ProviderFailureException.class)
                .matches(TripoAdapterTest::charged);
        tripo.verify(TripoAdapter.DOWNLOAD_ATTEMPTS, getRequestedFor(urlEqualTo(MODEL_PATH)));
    }

    @Test
    void aSuccessfulHttpResponseWithAnErrorCodeIsTranslated() {
        json("POST", "/v3/files", 200, "{\"code\":2004,\"message\":\"Unsupported file type\"}");

        assertThatThrownBy(() -> adapter().generate(JPEG)).isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void aResponseThatIsNotJsonIsAProviderFailure() {
        tripo.stubFor(post(urlEqualTo("/v3/files"))
                .willReturn(aResponse().withStatus(502).withBody("<html>Bad gateway</html>")));

        assertThatThrownBy(() -> adapter().generate(JPEG)).isInstanceOf(ProviderFailureException.class);
    }

    @Test
    void sendsPngWithItsContentType() {
        uploadOk();
        createOk();
        taskSucceeds();
        modelReturns(GLB);
        VehicleImage png = new VehicleImage(TestImages.png(512, 512), ImageFormat.PNG, 512, 512);

        adapter().generate(png);

        tripo.verify(postRequestedFor(urlEqualTo("/v3/files"))
                .withRequestBodyPart(aMultipart()
                        .withName("file")
                        .withHeader("Content-Type", equalTo("image/png"))
                        .build()));
    }
}
