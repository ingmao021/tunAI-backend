package com.tunai.api.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tunai.api.domain.exception.DomainException;
import com.tunai.api.domain.exception.GlobalLimitReachedException;
import com.tunai.api.domain.exception.ImageTooLargeException;
import com.tunai.api.domain.exception.InvalidImageException;
import com.tunai.api.domain.exception.InvalidInstallationIdException;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderRejectedException;
import com.tunai.api.domain.exception.ProviderTimeoutException;
import com.tunai.api.domain.exception.ProviderUnavailableException;
import com.tunai.api.domain.exception.QuotaExceededException;
import com.tunai.api.domain.exception.ServiceBusyException;
import com.tunai.api.domain.exception.UnsupportedImageFormatException;
import com.tunai.api.domain.model.QuotaStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Catálogo §15.4: cada excepción del dominio produce su estado, su {@code code} y problem+json. */
class ApiExceptionHandlerTest {

    private static final ThreadLocal<Supplier<? extends Exception>> TO_THROW = new ThreadLocal<>();
    private static final QuotaStatus EXHAUSTED = new QuotaStatus(2, 0, Instant.parse("2026-10-10T05:00:00Z"));

    private final MockMvcTester mvc = MockMvcTester.create(MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ApiExceptionHandler())
            .addFilters(new TraceIdFilter())
            .build());

    @RestController
    static class ThrowingController {
        @GetMapping("/throw")
        String doThrow() throws Exception {
            throw TO_THROW.get().get();
        }
    }

    static Stream<Arguments> catalog() {
        Duration none = null;
        return Stream.of(
                Arguments.of(
                        (Supplier<DomainException>) () -> new InvalidInstallationIdException("x"),
                        400,
                        "INVALID_INSTALLATION_ID",
                        none),
                Arguments.of(
                        (Supplier<DomainException>) () -> new InvalidImageException("x"), 400, "INVALID_IMAGE", none),
                Arguments.of(
                        (Supplier<DomainException>) () -> new ImageTooLargeException("x"),
                        413,
                        "IMAGE_TOO_LARGE",
                        none),
                Arguments.of(
                        (Supplier<DomainException>) () -> new UnsupportedImageFormatException("x"),
                        415,
                        "UNSUPPORTED_MEDIA_TYPE",
                        none),
                Arguments.of(
                        (Supplier<DomainException>) () -> new ProviderRejectedException("x"),
                        422,
                        "PROVIDER_REJECTED",
                        none),
                Arguments.of(
                        (Supplier<DomainException>)
                                () -> new QuotaExceededException("x", EXHAUSTED, Duration.ofMinutes(90)),
                        429,
                        "QUOTA_EXCEEDED",
                        Duration.ofMinutes(90)),
                Arguments.of(
                        (Supplier<DomainException>) () -> new ProviderFailureException("x"),
                        502,
                        "PROVIDER_ERROR",
                        none),
                Arguments.of(
                        (Supplier<DomainException>) () -> new ServiceBusyException("x", Duration.ofSeconds(5)),
                        503,
                        "SERVICE_BUSY",
                        Duration.ofSeconds(5)),
                Arguments.of(
                        (Supplier<DomainException>) () -> new GlobalLimitReachedException("x", Duration.ofHours(3)),
                        503,
                        "SERVICE_UNAVAILABLE",
                        Duration.ofHours(3)),
                Arguments.of(
                        (Supplier<DomainException>) () -> new ProviderUnavailableException("x"),
                        503,
                        "SERVICE_UNAVAILABLE",
                        none),
                Arguments.of(
                        (Supplier<DomainException>) () -> new ProviderTimeoutException("x", null),
                        504,
                        "PROVIDER_TIMEOUT",
                        none));
    }

    @ParameterizedTest(name = "{2} → {1}")
    @MethodSource("catalog")
    void mapsEveryDomainErrorToTheCatalog(
            Supplier<DomainException> exception, int status, String code, Duration retryAfter) {
        MvcTestResult result = call(exception);

        assertThat(result).hasStatus(status).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result)
                .bodyJson()
                .hasPathSatisfying("$.code", v -> assertThat(v).asString().isEqualTo(code))
                .hasPathSatisfying("$.status", v -> assertThat(v).asNumber().isEqualTo(status))
                .hasPathSatisfying("$.type", v -> assertThat(v)
                        .asString()
                        .isEqualTo("urn:tunai:problem:" + code.toLowerCase().replace('_', '-')))
                .hasPathSatisfying("$.traceId", v -> assertThat(v).asString().matches("[0-9a-f]{16}"));
        if (retryAfter == null) {
            assertThat(result).headers().doesNotContainHeader("Retry-After");
        } else {
            assertThat(result).headers().hasValue("Retry-After", retryAfter.toSeconds());
        }
    }

    @Test
    void quotaExceededCarriesTheQuotaHeaders() {
        MvcTestResult result = call(() -> new QuotaExceededException("agotada", EXHAUSTED, Duration.ofMinutes(90)));

        assertThat(result)
                .headers()
                .hasValue("X-Quota-Limit", 2)
                .hasValue("X-Quota-Remaining", 0)
                .hasValue("X-Quota-Reset", "2026-10-10T05:00:00Z")
                .hasValue("Retry-After", 5400);
    }

    @Test
    void hidesInternalDetailsOfProviderAndBudgetErrors() throws Exception {
        MvcTestResult provider = call(() -> new ProviderFailureException("Stability devolvió 500 con body secreto"));
        MvcTestResult global = call(() -> new GlobalLimitReachedException("6 de 6 créditos", Duration.ofHours(1)));

        assertThat(provider.getResponse().getContentAsString()).doesNotContain("secreto", "Stability");
        assertThat(global.getResponse().getContentAsString()).doesNotContain("créditos", "6 de 6");
    }

    @Test
    void showsTheMessageOfClientErrors() {
        MvcTestResult result = call(() -> new InvalidImageException("El lado menor de la imagen es 300 px"));

        assertThat(result)
                .bodyJson()
                .hasPathSatisfying("$.detail", v -> assertThat(v).asString().contains("300 px"));
    }

    @Test
    void unexpectedErrorsBecomeInternalErrorWithoutStackTrace() throws Exception {
        MvcTestResult result = call(() -> new IllegalStateException("NullPointer en com.tunai.api.Algo"));

        assertThat(result).hasStatus(500).bodyJson().hasPathSatisfying("$.code", v -> assertThat(v)
                .asString()
                .isEqualTo("INTERNAL_ERROR"));
        assertThat(result.getResponse().getContentAsString()).doesNotContain("com.tunai", "Exception", "at ");
    }

    @Test
    void retryAfterIsRoundedUpToWholeSeconds() {
        assertThat(ApiExceptionHandler.retryAfterSeconds(Duration.ofMillis(1500)))
                .isEqualTo(2);
        assertThat(ApiExceptionHandler.retryAfterSeconds(Duration.ofSeconds(7))).isEqualTo(7);
        assertThat(ApiExceptionHandler.retryAfterSeconds(Duration.ZERO)).isEqualTo(1);
    }

    private MvcTestResult call(Supplier<? extends Exception> exception) {
        TO_THROW.set(exception);
        try {
            return mvc.get().uri("/throw").exchange();
        } finally {
            TO_THROW.remove();
        }
    }
}
