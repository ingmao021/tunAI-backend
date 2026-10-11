package com.tunai.api.adapter.out.tripo;

import static org.assertj.core.api.Assertions.assertThat;

import com.tunai.api.domain.exception.DomainException;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderRejectedException;
import com.tunai.api.domain.exception.ProviderUnavailableException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TripoErrorTranslatorTest {

    private final TripoErrorTranslator translator = new TripoErrorTranslator();

    @ParameterizedTest(name = "HTTP {0}, code {1}")
    @CsvSource({
        "400, 2003, rejected",
        "400, 2004, rejected",
        "400, 2008, rejected",
        "401, 2, unavailable",
        "401, 1000, unavailable",
        "401, 1001, unavailable",
        "403, 2010, unavailable",
        "400, 2015, unavailable",
        "429, 1007, unavailable",
        "429, 2000, unavailable",
        // 2002 es un parámetro que enviamos mal: error nuestro, no de la foto
        "400, 2002, failure",
        "404, 9999, unavailable",
        "422, 9999, rejected",
        "500, 9999, failure",
        "400, 9999, failure"
    })
    void translatesByCodeAndThenByStatus(int status, int code, String expected) {
        DomainException e = translator.translate("crear la tarea", status, code, "{}", null);

        assertThat(e).isInstanceOf(type(expected));
    }

    @Test
    void withoutACodeDecidesByStatus() {
        assertThat(translator.translate("subir la foto", 403, null, "", null))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThat(translator.translate("subir la foto", 503, null, "", null))
                .isInstanceOf(ProviderFailureException.class);
    }

    @Test
    void rateLimitWithoutCodeKeepsRetryAfter() {
        assertThat(translator.translate("crear la tarea", 429, null, "", "12").retryAfter())
                .contains(Duration.ofSeconds(12));
    }

    @Test
    void longBodiesAreAbbreviated() {
        String body = "x".repeat(TripoErrorTranslator.MAX_BODY_CHARS * 2);

        assertThat(translator.translate("crear la tarea", 500, null, body, null).getMessage())
                .hasSizeLessThan(TripoErrorTranslator.MAX_BODY_CHARS + 100);
    }

    @Test
    void onlyBannedTasksBlameThePhoto() {
        assertThat(translator.translateTaskStatus("t", "banned")).isInstanceOf(ProviderRejectedException.class);
        assertThat(translator.translateTaskStatus("t", "failed")).isInstanceOf(ProviderFailureException.class);
        assertThat(translator.translateTaskStatus("t", "expired")).isInstanceOf(ProviderFailureException.class);
        assertThat(translator.translateTaskStatus("t", "cancelled")).isInstanceOf(ProviderFailureException.class);
    }

    private static Class<? extends DomainException> type(String expected) {
        return switch (expected) {
            case "rejected" -> ProviderRejectedException.class;
            case "unavailable" -> ProviderUnavailableException.class;
            default -> ProviderFailureException.class;
        };
    }
}
