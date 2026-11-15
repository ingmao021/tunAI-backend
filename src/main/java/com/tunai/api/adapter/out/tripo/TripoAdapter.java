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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * <p>El cliente espera como máximo {@code maxWait} (debe caber en el proxy de Render), pero la generación <b>no se
 * cancela</b> al vencer: Tripo la termina y la cobra igual, así que abandonarla solo desperdicia el modelo. Corre en un
 * hilo virtual propio, con plazo {@code taskDeadline}, y queda registrada por el hash de la foto. Un reintento con la
 * misma foto se une a ella (o recoge el modelo ya terminado) en vez de crear otra tarea, y por tanto no vuelve a gastar
 * créditos. Un modelo sin recoger se descarta tras {@link #RESULT_TTL}.
 *
 * <p>{@link #generate} empieza una generación o se une a la que haya; {@link #resume} solo se une y, si no hay ninguna
 * (el servicio se reinició o la generación falló), falla sin crear nada. El registro vive en memoria: no sobrevive a
 * un reinicio.
 *
 * <p>Nunca se reintenta la creación de la tarea (RN-13). Si algo falla después de enviar la creación (creación sin
 * respuesta clara, tiempo agotado, consulta o descarga fallida, GLB inválido), Tripo puede cobrarla igual: la
 * excepción lo indica con {@link DomainException#providerMayHaveCharged()} y el {@code task_id}, si se conoce, queda
 * en el log.
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

    /** Plazo por defecto de la generación en segundo plano; Tripo tarda minutos, no horas. */
    static final Duration DEFAULT_TASK_DEADLINE = Duration.ofMinutes(10);

    /** Cuánto se conserva un modelo terminado que el cliente no llegó a recoger. */
    static final Duration RESULT_TTL = Duration.ofMinutes(15);

    /**
     * Modelos terminados y sin recoger que se conservan a la vez. Cada uno ocupa hasta {@code maxModelBytes} (50 MB) y
     * la instancia de Render tiene 512 MB.
     */
    static final int MAX_KEPT_RESULTS = 2;

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
    private final Duration taskDeadline;
    private final long maxModelBytes;
    private final ConcurrentMap<String, Generation> generations = new ConcurrentHashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private final TripoErrorTranslator translator = new TripoErrorTranslator();

    /**
     * @param api cliente con la URL base de la v3, el header {@code Authorization} y timeouts
     * @param download cliente para la URL del GLB; no debe llevar la clave (es otro host)
     * @param minBalance créditos disponibles mínimos para empezar una generación (lo que cuesta un modelo)
     * @param maxWait lo que espera cada petición antes de responder que la generación sigue; debe caber en los ~100 s
     *     del proxy (§13.9)
     * @param taskDeadline plazo de toda la generación, del saldo a la descarga; corre en segundo plano y sigue
     *     aunque el cliente deje de esperar
     * @param maxModelBytes tamaño máximo del GLB; se corta la lectura al superarlo (RF-BE-013)
     */
    public TripoAdapter(
            RestClient api,
            RestClient download,
            TripoOptions options,
            int minBalance,
            Duration pollInterval,
            Duration maxWait,
            Duration taskDeadline,
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
        this.taskDeadline = requirePositive(taskDeadline, "taskDeadline");
        if (maxModelBytes <= 0 || maxModelBytes > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("maxModelBytes fuera de rango: " + maxModelBytes);
        }
        this.maxModelBytes = maxModelBytes;
    }

    /** Como {@link #create} con el plazo de segundo plano por defecto. */
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
        return create(
                baseUrl,
                apiKey,
                connectTimeout,
                readTimeout,
                options,
                minBalance,
                pollInterval,
                maxWait,
                DEFAULT_TASK_DEADLINE,
                maxModelBytes);
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
            Duration taskDeadline,
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
        return new TripoAdapter(api, download, options, minBalance, pollInterval, maxWait, taskDeadline, maxModelBytes);
    }

    @Override
    public GeneratedModel generate(VehicleImage image) {
        Objects.requireNonNull(image, "image");
        String key = fingerprint(image);
        evictStale();
        Generation fresh = new Generation();
        Generation generation = generations.putIfAbsent(key, fresh);
        if (generation == null) {
            generation = fresh;
            start(key, image, generation);
        } else {
            logResumed(generation);
        }
        return await(key, generation);
    }

    @Override
    public GeneratedModel resume(VehicleImage image) {
        Objects.requireNonNull(image, "image");
        String key = fingerprint(image);
        evictStale();
        Generation generation = generations.get(key);
        if (generation == null) {
            // Se perdió con un reinicio o falló: la anterior pudo cobrarse y aquí no se crea otra (RN-13)
            throw new ProviderFailureException(
                    "No hay ninguna generación de Tripo en curso para esta foto: no se crea otra al retomar",
                    null,
                    true);
        }
        logResumed(generation);
        return await(key, generation);
    }

    @Override
    public boolean hasPendingGeneration(VehicleImage image) {
        Objects.requireNonNull(image, "image");
        evictStale();
        return generations.containsKey(fingerprint(image));
    }

    /** Espera el modelo como máximo {@code maxWait}; si no llega, la generación sigue y se puede retomar. */
    private GeneratedModel await(String key, Generation generation) {
        generation.waiters.incrementAndGet();
        try {
            GeneratedModel model = generation.result.get(maxWait.toNanos(), TimeUnit.NANOSECONDS);
            generations.remove(key, generation);
            return model;
        } catch (TimeoutException e) {
            throw stillRunning(generation);
        } catch (ExecutionException e) {
            throw unwrap(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderFailureException(
                    "Espera de la generación con Tripo interrumpida", e, generation.creationSent.get());
        } finally {
            generation.waiters.decrementAndGet();
        }
    }

    private static void logResumed(Generation generation) {
        String taskId = generation.taskId.get();
        log.info(
                "Se retoma la generación de Tripo de esta foto (tarea {}) en vez de crear otra",
                taskId != null ? taskId : "aún sin crear");
    }

    /** Lanza la generación en su propio hilo virtual: no depende de que el cliente siga esperando. */
    private void start(String key, VehicleImage image, Generation generation) {
        long deadline = System.nanoTime() + taskDeadline.toNanos();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        generation.worker = Thread.ofVirtual().name("tripo-generation").start(() -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                GeneratedModel model = run(image, deadline, generation.creationSent, generation.taskId);
                generation.finishedAt = System.nanoTime();
                generation.result.complete(model);
            } catch (Throwable t) {
                // Una generación fallida no se retoma: el siguiente intento debe crear una tarea nueva
                generations.remove(key, generation);
                generation.finishedAt = System.nanoTime();
                if (generation.waiters.get() == 0) {
                    // Nadie espera la respuesta: sin esta línea el fallo (y el task_id) no quedaría en ningún lado
                    String taskId = generation.taskId.get();
                    log.warn(
                            "La generación de Tripo en segundo plano falló (tarea {}): {}",
                            taskId != null ? taskId : "sin crear",
                            t.toString());
                }
                generation.result.completeExceptionally(t);
            } finally {
                MDC.clear();
            }
        });
    }

    /** El cliente dejó de esperar, pero la generación sigue: Tripo puede terminarla y cobrarla. */
    private ProviderTimeoutException stillRunning(Generation generation) {
        String taskId = generation.taskId.get();
        log.warn(
                "Tripo no terminó en {} (tarea {}): la generación sigue en segundo plano y un reintento con la misma"
                        + " foto la retoma",
                maxWait,
                taskId != null ? taskId : "aún sin crear");
        return new ProviderTimeoutException(
                "Tripo no terminó en " + maxWait + (taskId != null ? " (tarea " + taskId + ")" : ""), null, true, true);
    }

    /** Descarta modelos sin recoger caducados y, si hay demasiados, los más antiguos. */
    private void evictStale() {
        long now = System.nanoTime();
        generations.entrySet().removeIf(e -> {
            Generation g = e.getValue();
            return g.result.isDone() && now - g.finishedAt > RESULT_TTL.toNanos();
        });
        long finished =
                generations.values().stream().filter(g -> g.result.isDone()).count();
        while (finished-- > MAX_KEPT_RESULTS) {
            generations.entrySet().stream()
                    .filter(e -> e.getValue().result.isDone())
                    .min((a, b) -> Long.compare(a.getValue().finishedAt, b.getValue().finishedAt))
                    .ifPresent(e -> generations.remove(e.getKey(), e.getValue()));
        }
    }

    /** Interrumpe las generaciones en curso y olvida las guardadas; para apagar el servicio y para los tests. */
    void cancelAll() {
        generations.values().forEach(g -> {
            Thread worker = g.worker;
            if (worker != null) {
                worker.interrupt();
            }
        });
        generations.clear();
    }

    private static String fingerprint(VehicleImage image) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image.data()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    /** Generación en curso o terminada y sin recoger, compartida por el intento original y sus reintentos. */
    private static final class Generation {
        final CompletableFuture<GeneratedModel> result = new CompletableFuture<>();
        final AtomicBoolean creationSent = new AtomicBoolean();
        final AtomicReference<String> taskId = new AtomicReference<>();
        final AtomicInteger waiters = new AtomicInteger();
        volatile long finishedAt;
        volatile Thread worker;
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
        String filename = switch (image.format()) {
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
                    "Tarea de Tripo {} sin terminar tras {} ({}, {}%): se abandona; puede completarse y cobrarse",
                    taskId, taskDeadline, status, progress);
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
