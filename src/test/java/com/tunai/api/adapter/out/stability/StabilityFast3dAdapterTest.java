package com.tunai.api.adapter.out.stability;

import static com.github.tomakehurst.wiremock.client.WireMock.aMultipart;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.tunai.api.TestImages;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderRejectedException;
import com.tunai.api.domain.exception.ProviderTimeoutException;
import com.tunai.api.domain.exception.ProviderUnavailableException;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.GenerationOptions;
import com.tunai.api.domain.model.ImageFormat;
import com.tunai.api.domain.model.VehicleImage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Anexo C: el adaptador se prueba solo contra WireMock; nunca contra Stability real (RN-15). */
class StabilityFast3dAdapterTest {

    private static final String API_KEY = "sk-test-0123456789";
    private static final byte[] GLB = glb(64);
    private static final VehicleImage JPEG = new VehicleImage(TestImages.jpeg(640, 512), ImageFormat.JPEG, 640, 512);

    @RegisterExtension
    static WireMockExtension stability = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private StabilityFast3dAdapter adapter(Duration readTimeout, long maxModelBytes, int vertexCount) {
        return StabilityFast3dAdapter.create(
                URI.create(stability.baseUrl()),
                API_KEY,
                Duration.ofSeconds(2),
                readTimeout,
                maxModelBytes,
                vertexCount);
    }

    private StabilityFast3dAdapter adapter() {
        return adapter(Duration.ofSeconds(5), 1024, -1);
    }

    private static byte[] glb(int size) {
        byte[] data = new byte[size];
        System.arraycopy("glTF".getBytes(StandardCharsets.US_ASCII), 0, data, 0, 4);
        return data;
    }

    private static void respond(int status, String body) {
        stability.stubFor(post(urlEqualTo(StabilityFast3dAdapter.PATH))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    @Test
    void sendsTheImageAndOptionsAndReturnsTheGlb() {
        stability.stubFor(post(urlEqualTo(StabilityFast3dAdapter.PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "model/gltf-binary")
                        .withBody(GLB)));

        GeneratedModel model =
                adapter().generate(JPEG, new GenerationOptions(2048, 0.7, GenerationOptions.Remesh.QUAD));

        assertThat(model.toByteArray()).isEqualTo(GLB);
        stability.verify(postRequestedFor(urlEqualTo(StabilityFast3dAdapter.PATH))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withHeader("Content-Type", containing("multipart/form-data"))
                .withRequestBodyPart(aMultipart()
                        .withName("image")
                        .withHeader("Content-Type", equalTo("image/jpeg"))
                        .withHeader("Content-Disposition", containing("filename=\"image.jpg\""))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("texture_resolution")
                        .withBody(equalTo("2048"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("foreground_ratio")
                        .withBody(equalTo("0.7"))
                        .build())
                .withRequestBodyPart(aMultipart()
                        .withName("remesh")
                        .withBody(equalTo("quad"))
                        .build()));
    }

    @Test
    void sendsTheImageBytesUnchanged() {
        stability.stubFor(post(urlEqualTo(StabilityFast3dAdapter.PATH))
                .willReturn(aResponse().withStatus(200).withBody(GLB)));

        adapter().generate(JPEG, GenerationOptions.DEFAULTS);

        byte[] sent = stability.getAllServeEvents().getFirst().getRequest().getParts().stream()
                .filter(part -> part.getName().equals("image"))
                .findFirst()
                .orElseThrow()
                .getBody()
                .asBytes();
        assertThat(sent).isEqualTo(JPEG.data());
    }

    @Test
    void omitsVertexCountByDefaultAndSendsItWhenConfigured() {
        stability.stubFor(post(urlEqualTo(StabilityFast3dAdapter.PATH))
                .willReturn(aResponse().withStatus(200).withBody(GLB)));

        adapter().generate(JPEG, GenerationOptions.DEFAULTS);
        adapter(Duration.ofSeconds(5), 1024, 5000).generate(JPEG, GenerationOptions.DEFAULTS);

        var requests = stability.getAllServeEvents();
        assertThat(requests).hasSize(2);
        assertThat(requests.stream().map(e -> e.getRequest().getBodyAsString()))
                .satisfiesExactlyInAnyOrder(
                        body -> assertThat(body).doesNotContain("vertex_count"), body -> assertThat(body)
                                .contains("name=\"vertex_count\"")
                                .contains("5000"));
    }

    @Test
    void aSuccessfulResponseThatIsNotAGlbIsAProviderFailure() {
        respond(200, "{\"finish_reason\":\"CONTENT_FILTERED\"}");

        assertThatThrownBy(() -> adapter().generate(JPEG, GenerationOptions.DEFAULTS))
                .isInstanceOf(ProviderFailureException.class);
    }

    @Test
    void aModelOverTheMaximumIsRejectedWithoutReadingItAll() {
        stability.stubFor(post(urlEqualTo(StabilityFast3dAdapter.PATH))
                .willReturn(aResponse().withStatus(200).withBody(glb(4096))));

        assertThatThrownBy(() -> adapter(Duration.ofSeconds(5), 1024, -1).generate(JPEG, GenerationOptions.DEFAULTS))
                .isInstanceOf(ProviderFailureException.class)
                .hasMessageContaining("1024");
    }

    @Test
    void moderationIsARejection() {
        respond(403, "{\"id\":\"e1\",\"name\":\"content_moderation\",\"errors\":[\"flagged\"]}");

        assertThatThrownBy(() -> adapter().generate(JPEG, GenerationOptions.DEFAULTS))
                .isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void anInvalidKeyOrNoCreditsMakesTheServiceUnavailable() {
        respond(401, "{\"name\":\"unauthorized\"}");
        assertThatThrownBy(() -> adapter().generate(JPEG, GenerationOptions.DEFAULTS))
                .isInstanceOf(ProviderUnavailableException.class);

        respond(402, "{\"name\":\"payment_required\"}");
        assertThatThrownBy(() -> adapter().generate(JPEG, GenerationOptions.DEFAULTS))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void rateLimitKeepsRetryAfter() {
        stability.stubFor(post(urlEqualTo(StabilityFast3dAdapter.PATH))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")));

        assertThatThrownBy(() -> adapter().generate(JPEG, GenerationOptions.DEFAULTS))
                .isInstanceOfSatisfying(ProviderUnavailableException.class, e -> assertThat(e.retryAfter())
                        .contains(Duration.ofSeconds(30)));
    }

    @Test
    void aServerErrorIsAProviderFailureAndIsNeverRetried() {
        respond(500, "{\"name\":\"internal_error\"}");

        assertThatThrownBy(() -> adapter().generate(JPEG, GenerationOptions.DEFAULTS))
                .isInstanceOf(ProviderFailureException.class);
        stability.verify(1, postRequestedFor(urlEqualTo(StabilityFast3dAdapter.PATH)));
    }

    @Test
    void aSlowProviderTimesOut() {
        stability.stubFor(post(urlEqualTo(StabilityFast3dAdapter.PATH))
                .willReturn(aResponse().withStatus(200).withBody(GLB).withFixedDelay(2000)));

        assertThatThrownBy(() -> adapter(Duration.ofMillis(300), 1024, -1).generate(JPEG, GenerationOptions.DEFAULTS))
                .isInstanceOf(ProviderTimeoutException.class);
    }

    @Test
    void anUnreachableProviderIsAProviderFailure() {
        StabilityFast3dAdapter unreachable = StabilityFast3dAdapter.create(
                URI.create("http://localhost:1"), API_KEY, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024, -1);

        assertThatThrownBy(() -> unreachable.generate(JPEG, GenerationOptions.DEFAULTS))
                .isInstanceOf(ProviderFailureException.class);
    }

    @Test
    void theApiKeyNeverAppearsInErrors() {
        respond(401, "{\"name\":\"unauthorized\",\"errors\":[\"bad key\"]}");

        assertThatThrownBy(() -> adapter().generate(JPEG, GenerationOptions.DEFAULTS))
                .satisfies(
                        e -> assertThat(Arrays.toString(e.getStackTrace()) + e).doesNotContain(API_KEY));
    }

    @Test
    void requiresAKey() {
        assertThatThrownBy(() -> StabilityFast3dAdapter.create(
                        URI.create("http://localhost"), " ", Duration.ofSeconds(1), Duration.ofSeconds(1), 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
