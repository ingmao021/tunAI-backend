package com.tunai.api.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * El límite de multipart lo aplica Tomcat antes del controlador; MockMvc no lo reproduce, por eso esta prueba usa un
 * servidor real (RNF-BE-007, catálogo 413).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UploadLimitTest {

    private static final String BOUNDARY = "tunai-test-boundary";

    @LocalServerPort
    int port;

    @Test
    void anUploadOverTheLimitAnswers413ProblemJson() throws Exception {
        byte[] image = new byte[11 * 1024 * 1024];
        image[0] = (byte) 0xFF;
        image[1] = (byte) 0xD8;
        image[2] = (byte) 0xFF;

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/generations"))
                                .header("X-Installation-Id", "3f1c2a9e-6d0b-4e8a-9f7c-2b5d1e4a6c80")
                                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(image)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("application/problem+json"));
        assertThat(response.body()).contains("\"code\":\"IMAGE_TOO_LARGE\"");
    }

    private static byte[] multipart(byte[] image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"carro.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(image);
        out.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }
}
