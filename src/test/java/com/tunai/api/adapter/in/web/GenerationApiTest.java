package com.tunai.api.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tunai.api.TestImages;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** F-BE-01 de extremo a extremo con el proveedor {@code fake} (flujos A1 y A2). */
@SpringBootTest(properties = "tunai.generator.fake.delay=0s")
@AutoConfigureMockMvc
class GenerationApiTest {

    private static final String URI = "/api/v1/generations";
    private static final String INSTALLATION_ID = "3f1c2a9e-6d0b-4e8a-9f7c-2b5d1e4a6c80";

    @Autowired
    MockMvcTester mvc;

    @Test
    void returnsTheGlbForAValidPhoto() {
        MvcTestResult result = generate(INSTALLATION_ID, TestImages.jpeg(800, 600));

        assertThat(result).hasStatusOk().hasContentType("model/gltf-binary");
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(Arrays.copyOf(body, 4)).isEqualTo("glTF".getBytes(StandardCharsets.US_ASCII));
        assertThat(result.getResponse().getContentLengthLong()).isEqualTo(body.length);
    }

    @Test
    void acceptsPng() {
        assertThat(generate(INSTALLATION_ID, TestImages.png(512, 512))).hasStatusOk();
    }

    @Test
    void missingInstallationIdIsRejected() {
        assertError(generate(null, TestImages.jpeg(800, 600)), 400, "INVALID_INSTALLATION_ID");
    }

    @Test
    void malformedInstallationIdIsRejected() {
        assertError(generate("1-1-1-1-1", TestImages.jpeg(800, 600)), 400, "INVALID_INSTALLATION_ID");
    }

    @Test
    void theHeaderIsCheckedBeforeTheImage() {
        assertError(generate("nope", "not an image".getBytes(StandardCharsets.UTF_8)), 400, "INVALID_INSTALLATION_ID");
    }

    @Test
    void bytesThatAreNotAnImageAreRejected() {
        assertError(generate(INSTALLATION_ID, "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)), 400, "INVALID_IMAGE");
    }

    @Test
    void anImageTooSmallIsRejected() {
        assertError(generate(INSTALLATION_ID, TestImages.png(800, 300)), 400, "INVALID_IMAGE");
    }

    @Test
    void anotherImageFormatIsUnsupported() {
        assertError(generate(INSTALLATION_ID, TestImages.webpHeader()), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void theContentTypeOfThePartIsNotTrusted() {
        MvcTestResult result = mvc.post()
                .uri(URI)
                .header("X-Installation-Id", INSTALLATION_ID)
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
                .header("X-Installation-Id", INSTALLATION_ID)
                .multipart()
                .param("other", "x")
                .exchange();

        assertError(result, 400, "INVALID_IMAGE");
    }

    @Test
    void aNonMultipartRequestIsRejected() {
        MvcTestResult result = mvc.post()
                .uri(URI)
                .header("X-Installation-Id", INSTALLATION_ID)
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

    private MvcTestResult generate(String installationId, byte[] image) {
        var request = mvc.post().uri(URI);
        if (installationId != null) {
            request.header("X-Installation-Id", installationId);
        }
        return request.multipart().file("image", image).exchange();
    }

    private static void assertError(MvcTestResult result, int status, String code) {
        assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo(code));
    }
}
