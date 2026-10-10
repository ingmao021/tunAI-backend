package com.tunai.api.adapter.out.stability;

import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderTimeoutException;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.GenerationOptions;
import com.tunai.api.domain.model.VehicleImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Adaptador de Stable Fast 3D (§13.7): {@code POST /v2beta/3d/stable-fast-3d} con la foto y los parámetros de
 * generación. Cada llamada exitosa cuesta créditos, por eso nunca se reintenta (RN-13).
 *
 * <p>El {@link RestClient} llega ya configurado con la URL base, la clave y los timeouts (RNF-BE-002). La clave nunca
 * aparece en mensajes ni logs (RNF-BE-006).
 */
public final class StabilityFast3dAdapter implements ModelGeneratorPort {

    static final String PATH = "/v2beta/3d/stable-fast-3d";

    /** Bytes del cuerpo de error que se leen para diagnóstico. */
    private static final int MAX_ERROR_BODY_BYTES = 4096;

    private final RestClient restClient;
    private final long maxModelBytes;
    private final int vertexCount;
    private final StabilityErrorTranslator translator = new StabilityErrorTranslator();

    /**
     * @param restClient cliente con URL base, header {@code Authorization} y timeouts
     * @param maxModelBytes tamaño máximo del GLB; se corta la lectura al superarlo (RF-BE-013)
     * @param vertexCount número de vértices objetivo de SF3D; {@code -1} para no enviarlo y usar el del proveedor
     */
    public StabilityFast3dAdapter(RestClient restClient, long maxModelBytes, int vertexCount) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        if (maxModelBytes <= 0) {
            throw new IllegalArgumentException("maxModelBytes debe ser positivo: " + maxModelBytes);
        }
        this.maxModelBytes = maxModelBytes;
        this.vertexCount = vertexCount;
    }

    /**
     * Crea el adaptador con un cliente HTTP del JDK: timeout de conexión y de respuesta (RNF-BE-002) y la clave en el
     * header {@code Authorization}.
     */
    public static StabilityFast3dAdapter create(
            URI baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration readTimeout,
            long maxModelBytes,
            int vertexCount) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Falta la clave de Stability");
        }
        // HTTP/1.1: una sola subida por petición no gana nada con HTTP/2, y el upgrade h2c falla con algunos servidores
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Objects.requireNonNull(readTimeout, "readTimeout"));
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl.toString())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        return new StabilityFast3dAdapter(restClient, maxModelBytes, vertexCount);
    }

    @Override
    public GeneratedModel generate(VehicleImage image, GenerationOptions options) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        try {
            return restClient
                    .post()
                    .uri(PATH)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(multipart(image, options))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.is2xxSuccessful()) {
                            return GeneratedModel.fromGlb(readModel(response.getBody()));
                        }
                        String errorBody = readErrorBody(response.getBody());
                        throw translator.translate(
                                status.value(), errorBody, response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
                    });
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new ProviderTimeoutException("Tiempo agotado con Stability", e);
            }
            throw new ProviderFailureException("No se pudo conectar con Stability: " + e.getMessage(), e);
        }
    }

    /**
     * Cuerpo multipart armado a mano: {@code MultipartBodyBuilder} de Spring 7 depende de Reactive Streams, que esta
     * app no incluye.
     */
    private MultiValueMap<String, Object> multipart(VehicleImage image, GenerationOptions options) {
        String filename =
                switch (image.format()) {
                    case JPEG -> "image.jpg";
                    case PNG -> "image.png";
                };
        HttpHeaders imageHeaders = new HttpHeaders();
        imageHeaders.setContentType(
                switch (image.format()) {
                    case JPEG -> MediaType.IMAGE_JPEG;
                    case PNG -> MediaType.IMAGE_PNG;
                });
        imageHeaders.setContentDispositionFormData("image", filename);
        ByteArrayResource imageBody = new ByteArrayResource(image.data()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("image", new HttpEntity<>(imageBody, imageHeaders));
        parts.add("texture_resolution", String.valueOf(options.textureResolution()));
        parts.add("foreground_ratio", String.valueOf(options.foregroundRatio()));
        parts.add("remesh", options.remesh().name().toLowerCase(Locale.ROOT));
        if (vertexCount != -1) {
            parts.add("vertex_count", String.valueOf(vertexCount));
        }
        return parts;
    }

    /** Lee como máximo {@code maxModelBytes + 1} bytes para detectar un modelo demasiado grande sin cargarlo entero. */
    private byte[] readModel(InputStream body) throws IOException {
        byte[] data = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxModelBytes + 1));
        if (data.length > maxModelBytes) {
            throw new ProviderFailureException(
                    "El modelo de Stability supera el máximo de " + maxModelBytes + " bytes (RF-BE-013)");
        }
        return data;
    }

    private static String readErrorBody(InputStream body) {
        try {
            return new String(body.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
