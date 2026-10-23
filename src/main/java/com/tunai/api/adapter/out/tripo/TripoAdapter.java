package com.tunai.api.adapter.out.tripo;

import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.domain.exception.DomainException;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderTimeoutException;
import com.tunai.api.domain.exception.ProviderUnavailableException;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.VehicleImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
 *   <li>{@code GET /account/balance}: si el saldo no alcanza para un modelo, se responde 503 sin crear nada. El saldo
 *       es el tope real del gasto: la cuota vive en memoria y se reinicia cada vez que Render duerme el servicio.
 *   <li>{@code POST /files}: sube la foto y recibe un {@code file_token}.
 *   <li>{@code POST /generation/image-to-model}: crea la tarea y recibe un {@code task_id}. Aquí Tripo congela los
 *       créditos; solo los cobra si la tarea termina en {@code success}.
 *   <li>{@code GET /tasks/{task_id}}: consulta cada {@code pollInterval}; un fallo pasajero se reintenta.
 *   <li>Descarga el GLB de {@code output.model_url} (CDN de Tripo, <b>sin</b> la clave), con un reintento.
 * </ol>
 *
 * <p>Todo el flujo tiene un plazo duro de {@code maxWait}: corre en un hilo virtual que se cancela al vencer, así una
 * llamada lenta no deja al cliente esperando más que el proxy de Render. Nunca se reintenta la creación de la tarea
 * (RN-13). Si algo falla después de enviar la creación (creación sin respuesta clara, tiempo agotado, consulta o
 * descarga fallida, GLB inválido), Tripo puede cobrarla igual: la excepción lo indica con
 * {@link DomainException#providerMayHaveCharged()} y el {@code task_id}, si se conoce, queda en el log.
 */
public final class TripoAdapter implements ModelGeneratorPort {

    static final String BALANCE_PATH = "/account/balance";
    static final String FILES_PATH = "/files";
    static final String IMAGE_TO_MODEL_PATH = "/generation/image-to-model";
    static final String TASK_PATH = "/tasks/{taskId}";

    /** Consultas fallidas seguidas que se toleran antes de abandonar la tarea. */
    static final int MAX_POLL_FAILURES = 5;

    /** Intentos de descarga del GLB: la URL es de solo lectura, así que repetirla no cobra otra vez. */
    static final int DOWNLOAD_ATTEMPTS = 2;

    private static final Logger log = LoggerFactory.getLogger(TripoAdapter.class);

    /** Bytes de las respuestas JSON que se leen; las de Tripo ocupan unos cientos. */
    private static final int MAX_JSON_BYTES = 64 * 1024;

    private static final Set<String> RUNNING = Set.of("queued", "running");

    private final RestClient api;
    private final RestClient download;
    private final TripoOptions options;
    private final int minBalance;
    private final Duration pollInterval;
    private final Duration maxWait;
    private final long maxModelBytes;
    private final JsonMapper json = JsonMapper.builder().build();
    private final TripoErrorTranslator translator = new TripoErrorTranslator();

    /**
     * @param api cliente con la URL base de la v3, el header {@code Authorization} y timeouts
     * @param download cliente para la URL del GLB; no debe llevar la clave (es otro host)
     * @param minBalance créditos disponibles mínimos para empezar una generación (lo que cuesta un modelo)
     * @param maxWait plazo de toda la generación, del saldo a la descarga; debe caber en los ~100 s del proxy (§13.9)
     * @param maxModelBytes tamaño máximo del GLB; se corta la lectura al superarlo (RF-BE-013)
     */
    public TripoAdapter(
            RestClient api,
            RestClient download,
            TripoOptions options,
            int minBalance,
            Duration pollInterval,
            Duration maxWait,
            long maxModelBytes) {
        this.api = Objects.requireNonNull(api, "api");
        this.download = Objects.requireNonNull(download, "download");
        this.options = Objects.requireNonNull(options, "options");
        if (minBalance < 0) {
            throw new IllegalArgumentException("minBalance no puede ser negativo: " + minBalance);
        }
        this.minBalance = minBalance;
        this.pollInterval = requirePositive(pollInterval, "pollInterval");
        this.maxWait = requirePositive(maxWait, "maxWait");
        if (maxModelBytes <= 0 || maxModelBytes > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("maxModelBytes fuera de rango: " + maxModelBytes);
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
            int minBalance,
            Duration pollInterval,
            Duration maxWait,
            long maxModelBytes) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Falta la clave de Tripo");
        }
        // HTTP/1.1: el upgrade h2c del cliente del JDK falla con algunos servidores
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
        return new TripoAdapter(api, download, options, minBalance, pollInterval, maxWait, maxModelBytes);
    }

    @Override
    public GeneratedModel generate(VehicleImage image) {
        Objects.requireNonNull(image, "image");
        long deadline = System.nanoTime() + maxWait.toNanos();
        AtomicBoolean creationSent = new AtomicBoolean();
        AtomicReference<String> createdTask = new AtomicReference<>();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        FutureTask<GeneratedModel> work = new FutureTask<>(() -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                return run(image, deadline, creationSent, createdTask);
            } finally {
                MDC.clear();
            }
        });
        Thread.ofVirtual().name("tripo-generation").start(work);
        try {
            return work.get(maxWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            work.cancel(true);
            String taskId = createdTask.get();
            if (taskId != null) {
                log.warn("Tarea de Tripo {} sin terminar tras {}: puede completarse y cobrarse", taskId, maxWait);
            } else if (creationSent.get()) {
                log.warn("Plazo de {} vencido mientras se creaba la tarea de Tripo: puede existir y cobrarse", maxWait);
            }
            throw new ProviderTimeoutException(
                    "Tripo no terminó en " + maxWait + (taskId != null ? " (tarea " + taskId + ")" : ""),
                    null,
                    creationSent.get());
        } catch (ExecutionException e) {
            throw unwrap(e.getCause());
        } catch (InterruptedException e) {
            work.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderFailureException("Generación con Tripo interrumpida", e, creationSent.get());
        }
    }

    private GeneratedModel run(
            VehicleImage image, long deadline, AtomicBoolean creationSent, AtomicReference<String> createdTask) {
        String fileToken;
        try {
            checkBalance();
            fileToken = upload(image);
        } catch (ResourceAccessException e) {
            throw networkError(e);
        }
        // Desde aquí Tripo puede crear la tarea aunque la respuesta no llegue: un fallo ya no libera la cuota diaria
        creationSent.set(true);
        String taskId;
        try {
            taskId = createTask(fileToken);
        } catch (ResourceAccessException e) {
            throw mayHaveBeenCreated(networkError(e));
        }
        createdTask.set(taskId);
        log.info("Tarea de Tripo creada: {} (modelo {})", taskId, options.model());
        JsonNode task = awaitTask(taskId, deadline);
        try {
            byte[] glb = downloadModel(taskId, modelUrl(taskId, task));
            GeneratedModel model = GeneratedModel.fromGlb(glb);
            log.info(
                    "Tarea de Tripo {} lista: {} bytes, {} créditos",
                    taskId,
                    glb.length,
                    task.path("credits_consumed").asText("?"));
            return model;
        } catch (ResourceAccessException e) {
            throw charged(taskId, networkError(e));
        } catch (DomainException e) {
            throw charged(taskId, e);
        }
    }

    /** RN-17: el saldo es el único tope del gasto que sobrevive a un reinicio del servicio. */
    private void checkBalance() {
        JsonNode data =
                api.get().uri(BALANCE_PATH).exchange((request, response) -> data("consultar el saldo", response));
        JsonNode balance = data.path("balance");
        if (!balance.isNumber()) {
            throw new ProviderFailureException("Tripo (consultar el saldo) respondió sin balance");
        }
        if (balance.asDouble() < minBalance) {
            throw new ProviderUnavailableException("Saldo de Tripo insuficiente: " + balance.asText()
                    + " créditos disponibles; cada modelo usa " + minBalance + " (RN-17)");
        }
        log.info(
                "Saldo de Tripo: {} créditos ({} congelados)",
                balance.asText(),
                data.path("frozen").asText("?"));
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
                .put("geometry_quality", options.geometryQuality().apiValue())
                .put("face_limit", options.faceLimit());
        JsonNode data = api.post()
                .uri(IMAGE_TO_MODEL_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .exchange((request, response) -> {
                    try {
                        return data("crear la tarea", response);
                    } catch (ProviderFailureException e) {
                        // Un 4xx con código de Tripo es un rechazo definitivo; un 5xx o un cuerpo ilegible, no
                        throw response.getStatusCode().is4xxClientError() ? e : mayHaveBeenCreated(e);
                    }
                });
        return requireText(data, "task_id", "crear la tarea");
    }

    /**
     * La petición de creación salió pero no hubo una respuesta clara (red, tiempo agotado, 5xx): Tripo puede haber
     * creado la tarea y cobrarla. No se reintenta (RN-13), pero la cuota diaria la cuenta (§13.9).
     */
    private static DomainException mayHaveBeenCreated(DomainException e) {
        String message = e.getMessage() + " (la tarea pudo crearse: Tripo puede cobrarla)";
        return e instanceof ProviderTimeoutException
                ? new ProviderTimeoutException(message, e, true)
                : new ProviderFailureException(message, e, true);
    }

    /**
     * Consulta la tarea hasta un estado final. La consulta es un GET idempotente: un fallo de red o un 5xx se reintenta
     * en la siguiente vuelta, porque abandonar una tarea que Tripo sigue procesando no evita que la cobre. Los estados
     * finales sin modelo los traduce {@link TripoErrorTranslator#translateTaskStatus}, que sabe cuáles devuelve Tripo.
     */
    private JsonNode awaitTask(String taskId, long deadline) {
        int consecutiveFailures = 0;
        while (true) {
            JsonNode task = null;
            DomainException failure = null;
            try {
                task = api.get()
                        .uri(TASK_PATH, taskId)
                        .exchange((request, response) -> data("consultar la tarea", response));
            } catch (ResourceAccessException e) {
                failure = networkError(e);
            } catch (DomainException e) {
                failure = e;
            }
            if (failure != null) {
                // Clave inválida o límite de tasa no se arreglan reintentando; red, 5xx y respuestas raras, quizá sí
                boolean transientFailure =
                        failure instanceof ProviderFailureException || failure instanceof ProviderTimeoutException;
                if (!transientFailure || ++consecutiveFailures > MAX_POLL_FAILURES) {
                    throw charged(taskId, failure);
                }
                log.warn("Consulta de la tarea {} de Tripo fallida ({}): se reintenta", taskId, failure.getMessage());
                waitForNextPoll(taskId, deadline, "sin respuesta", 0);
                continue;
            }
            consecutiveFailures = 0;
            String status = task.path("status").asText("");
            if ("success".equals(status)) {
                return task;
            }
            if (!RUNNING.contains(status)) {
                throw translator.translateTaskStatus(taskId, status.isEmpty() ? "(sin estado)" : status);
            }
            waitForNextPoll(taskId, deadline, status, task.path("progress").asInt());
        }
    }

    private void waitForNextPoll(String taskId, long deadline, String status, int progress) {
        if (System.nanoTime() + pollInterval.toNanos() > deadline) {
            log.warn(
                    "Tarea de Tripo {} sin terminar tras {} ({}, {}%): puede completarse y cobrar créditos",
                    taskId, maxWait, status, progress);
            throw new ProviderTimeoutException("La tarea " + taskId + " de Tripo no terminó a tiempo", null, true);
        }
        sleep(pollInterval);
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

    /** Reintenta una vez los fallos de red y los 5xx; un 4xx o un GLB demasiado grande no se arreglan reintentando. */
    private byte[] downloadModel(String taskId, URI modelUrl) {
        for (int attempt = 1; ; attempt++) {
            try {
                byte[] glb = download.get().uri(modelUrl).exchange((request, response) -> readModel(response));
                if (glb != null) {
                    return glb;
                }
                if (attempt >= DOWNLOAD_ATTEMPTS) {
                    throw new ProviderFailureException("La descarga del modelo de Tripo respondió 5xx");
                }
            } catch (ResourceAccessException e) {
                if (attempt >= DOWNLOAD_ATTEMPTS) {
                    throw e;
                }
            }
            log.warn("Descarga del modelo de la tarea {} de Tripo fallida (intento {}): se reintenta", taskId, attempt);
        }
    }

    /**
     * Lee el GLB en un solo arreglo del tamaño exacto cuando hay {@code Content-Length} (RNF-BE-003).
     *
     * @return {@code null} si la respuesta es un 5xx que vale la pena reintentar
     */
    private byte[] readModel(ClientHttpResponse response) throws IOException {
        if (response.getStatusCode().is5xxServerError()) {
            return null;
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new ProviderFailureException("La descarga del modelo de Tripo respondió "
                    + response.getStatusCode().value());
        }
        long length = response.getHeaders().getContentLength();
        if (length > maxModelBytes) {
            throw tooLarge();
        }
        InputStream body = response.getBody();
        if (length >= 0) {
            byte[] data = new byte[(int) length];
            if (body.readNBytes(data, 0, data.length) < data.length) {
                throw new ProviderFailureException("La descarga del modelo de Tripo quedó incompleta");
            }
            return data;
        }
        byte[] data = body.readNBytes((int) maxModelBytes + 1);
        if (data.length > maxModelBytes) {
            throw tooLarge();
        }
        return data;
    }

    private ProviderFailureException tooLarge() {
        return new ProviderFailureException(
                "El modelo de Tripo supera el máximo de " + maxModelBytes + " bytes (RF-BE-013)");
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

    /** Marca un error ocurrido después de crear la tarea: Tripo puede terminarla y cobrarla aunque aquí se abandone. */
    private static DomainException charged(String taskId, DomainException e) {
        if (e.providerMayHaveCharged()) {
            return e;
        }
        String message = e.getMessage() + " (tarea " + taskId + ": Tripo puede cobrarla)";
        return e instanceof ProviderTimeoutException
                ? new ProviderTimeoutException(message, e, true)
                : new ProviderFailureException(message, e, true);
    }

    private static DomainException networkError(ResourceAccessException e) {
        if (isTimeout(e)) {
            return new ProviderTimeoutException("Tiempo agotado con Tripo", e);
        }
        return new ProviderFailureException("No se pudo conectar con Tripo: " + e.getMessage(), e);
    }

    private static RuntimeException unwrap(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new ProviderFailureException("Error inesperado con Tripo", cause);
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
