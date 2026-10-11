package com.tunai.api.adapter.out.tripo;

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
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adaptador de Tripo API v3 (§13.9). La generación es asíncrona del lado de Tripo y aquí se vuelve síncrona:
 *
 * <ol>
 *   <li>{@code POST /files}: sube la foto y recibe un {@code file_token}.
 *   <li>{@code POST /generation/image-to-model}: crea la tarea y recibe un {@code task_id}. Aquí Tripo congela los
 *       créditos; solo los cobra si la tarea termina bien.
 *   <li>{@code GET /tasks/{task_id}}: consulta cada {@code pollInterval} hasta un estado final o hasta {@code maxWait}.
 *   <li>Descarga el GLB de {@code output.model_url} (CDN de Tripo, <b>sin</b> la clave).
 * </ol>
 *
 * <p>Nunca se reintenta la creación de la tarea (RN-13). Si se agota {@code maxWait}, la tarea puede terminar igual
 * en Tripo y cobrar sus créditos; por eso su {@code task_id} queda en el log. Los {@link GenerationOptions} son de
 * Stable Fast 3D y se ignoran: los parámetros de Tripo vienen en {@link TripoOptions}.
 */
public final class TripoAdapter implements ModelGeneratorPort {

    static final String FILES_PATH = "/files";
    static final String IMAGE_TO_MODEL_PATH = "/generation/image-to-model";
    static final String TASK_PATH = "/tasks/{taskId}";

    private static final Logger log = LoggerFactory.getLogger(TripoAdapter.class);

    /** Bytes de las respuestas JSON que se leen; las de Tripo ocupan unos cientos. */
    private static final int MAX_JSON_BYTES = 64 * 1024;

    private static final Set<String> RUNNING = Set.of("queued", "running");

    private final RestClient api;
    private final RestClient download;
    private final TripoOptions options;
    private final Duration pollInterval;
    private final Duration maxWait;
    private final long maxModelBytes;
    private final JsonMapper json = JsonMapper.builder().build();
    private final TripoErrorTranslator translator = new TripoErrorTranslator();

    /**
     * @param api cliente con la URL base de la v3, el header {@code Authorization} y timeouts
     * @param download cliente para la URL del GLB; no debe llevar la clave (es otro host)
     * @param maxWait tiempo máximo esperando la tarea; debe caber en el timeout del cliente y del proxy (§13.9)
     * @param maxModelBytes tamaño máximo del GLB; se corta la lectura al superarlo (RF-BE-013)
     */
    public TripoAdapter(
            RestClient api,
            RestClient download,
            TripoOptions options,
            Duration pollInterval,
            Duration maxWait,
            long maxModelBytes) {
        this.api = Objects.requireNonNull(api, "api");
        this.download = Objects.requireNonNull(download, "download");
        this.options = Objects.requireNonNull(options, "options");
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        this.maxWait = requirePositive(maxWait, "maxWait");
        if (maxModelBytes <= 0) {
            throw new IllegalArgumentException("maxModelBytes debe ser positivo: " + maxModelBytes);
        }
        this.maxModelBytes = maxModelBytes;
    }

    /** Crea el adaptador con un cliente HTTP del JDK (RNF-BE-002) y la clave solo en las llamadas a la API. */
    public static TripoAdapter create(
            URI baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration readTimeout,
            TripoOptions options,
            Duration pollInterval,
            Duration maxWait,
            long maxModelBytes) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Falta la clave de Tripo");
        }
        // HTTP/1.1 por la misma razón que en Stability: el upgrade h2c falla con algunos servidores
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Objects.requireNonNull(readTimeout, "readTimeout"));
        String base = baseUrl.toString().replaceAll("/+$", "");
        RestClient api = RestClient.builder()
                .baseUrl(base)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        RestClient download =
                RestClient.builder().requestFactory(requestFactory).build();
        return new TripoAdapter(api, download, options, pollInterval, maxWait, maxModelBytes);
    }

    @Override
    public GeneratedModel generate(VehicleImage image, GenerationOptions ignored) {
        Objects.requireNonNull(image, "image");
        long deadline = System.nanoTime() + maxWait.toNanos();
        try {
            String fileToken = upload(image);
            String taskId = createTask(fileToken);
            log.info("Tarea de Tripo creada: {} (modelo {})", taskId, options.model());
            JsonNode task = awaitTask(taskId, deadline);
            URI modelUrl = modelUrl(taskId, task);
            byte[] glb = downloadModel(modelUrl);
            log.info(
                    "Tarea de Tripo {} lista: {} bytes, {} créditos",
                    taskId,
                    glb.length,
                    task.path("credits_consumed").asText("?"));
            return GeneratedModel.fromGlb(glb);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new ProviderTimeoutException("Tiempo agotado con Tripo", e);
            }
            throw new ProviderFailureException("No se pudo conectar con Tripo: " + e.getMessage(), e);
        }
    }

    private String upload(VehicleImage image) {
        String filename =
                switch (image.format()) {
                    case JPEG -> "image.jpg";
                    case PNG -> "image.png";
                };
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(
                switch (image.format()) {
                    case JPEG -> MediaType.IMAGE_JPEG;
                    case PNG -> MediaType.IMAGE_PNG;
                });
        fileHeaders.setContentDispositionFormData("file", filename);
        ByteArrayResource fileBody = new ByteArrayResource(image.data()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new HttpEntity<>(fileBody, fileHeaders));

        JsonNode data = api.post()
                .uri(FILES_PATH)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .exchange((request, response) -> data("subir la foto", response));
        return requireText(data, "file_token", "subir la foto");
    }

    private String createTask(String fileToken) {
        ObjectNode body = json.createObjectNode()
                .put("input", fileToken)
                .put("model", options.model())
                .put("texture", true)
                .put("pbr", options.pbr())
                .put("texture_quality", options.textureQuality().apiValue())
                .put("face_limit", options.faceLimit());
        JsonNode data = api.post()
                .uri(IMAGE_TO_MODEL_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .exchange((request, response) -> data("crear la tarea", response));
        return requireText(data, "task_id", "crear la tarea");
    }

    private JsonNode awaitTask(String taskId, long deadline) {
        while (true) {
            JsonNode task = api.get()
                    .uri(TASK_PATH, taskId)
                    .exchange((request, response) -> data("consultar la tarea", response));
            String status = task.path("status").asText("");
            if ("success".equals(status)) {
                return task;
            }
            if (!RUNNING.contains(status)) {
                throw translator.translateTaskStatus(taskId, status.isEmpty() ? "(sin estado)" : status);
            }
            if (System.nanoTime() + pollInterval.toNanos() > deadline) {
                log.warn(
                        "Tarea de Tripo {} sin terminar tras {} ({}, {}%): puede completarse y cobrar créditos",
                        taskId, maxWait, status, task.path("progress").asInt());
                throw new ProviderTimeoutException("La tarea " + taskId + " de Tripo no terminó a tiempo", null);
            }
            sleep(pollInterval);
        }
    }

    private URI modelUrl(String taskId, JsonNode task) {
        String url = task.path("output").path("model_url").asText("");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ProviderFailureException("La tarea " + taskId + " de Tripo trae una URL de modelo inválida", e);
        }
        String scheme = uri.getScheme();
        if (url.isEmpty() || !("https".equals(scheme) || "http".equals(scheme)) || uri.getHost() == null) {
            throw new ProviderFailureException("La tarea " + taskId + " de Tripo terminó sin una URL de modelo válida");
        }
        return uri;
    }

    private byte[] downloadModel(URI modelUrl) {
        return download.get().uri(modelUrl).exchange((request, response) -> {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new ProviderFailureException("La descarga del modelo de Tripo respondió "
                        + response.getStatusCode().value());
            }
            byte[] data = response.getBody().readNBytes((int) Math.min(Integer.MAX_VALUE - 8, maxModelBytes + 1));
            if (data.length > maxModelBytes) {
                throw new ProviderFailureException(
                        "El modelo de Tripo supera el máximo de " + maxModelBytes + " bytes (RF-BE-013)");
            }
            return data;
        });
    }

    /** Devuelve {@code data} de una respuesta {@code {"code":0,"data":{...}}}; cualquier otra cosa es un error. */
    private JsonNode data(String step, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String body = readJson(response.getBody());
        JsonNode root = parse(body);
        Integer code =
                root != null && root.path("code").isNumber() ? root.path("code").asInt() : null;
        if (response.getStatusCode().is2xxSuccessful()
                && code != null
                && code == 0
                && root.path("data").isObject()) {
            return root.path("data");
        }
        throw translator.translate(
                step, status, code, body, response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    private JsonNode parse(String body) {
        if (body.isBlank()) {
            return null;
        }
        try {
            return json.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String requireText(JsonNode data, String field, String step) {
        String value = data.path(field).asText("");
        if (value.isBlank()) {
            throw new ProviderFailureException("Tripo (" + step + ") respondió sin " + field);
        }
        return value;
    }

    private static String readJson(InputStream body) throws IOException {
        return new String(body.readNBytes(MAX_JSON_BYTES), StandardCharsets.UTF_8);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderFailureException("Espera de la tarea de Tripo interrumpida", e);
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " debe ser positivo: " + value);
        }
        return value;
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
