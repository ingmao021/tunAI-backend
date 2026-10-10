package com.tunai.api.adapter.out.stability;

import static org.assertj.core.api.Assertions.assertThat;

import com.tunai.api.domain.exception.DomainException;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderRejectedException;
import com.tunai.api.domain.exception.ProviderUnavailableException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StabilityErrorTranslatorTest {

    private final StabilityErrorTranslator translator = new StabilityErrorTranslator();

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        "400, ProviderRejectedException",
        "413, ProviderRejectedException",
        "422, ProviderRejectedException",
        "401, ProviderUnavailableException",
        "402, ProviderUnavailableException",
        "404, ProviderUnavailableException",
        "429, ProviderUnavailableException",
        "500, ProviderFailureException",
        "502, ProviderFailureException",
        "503, ProviderFailureException",
        "418, ProviderFailureException"
    })
    void mapsStatusCodes(int status, String expected) {
        DomainException e = translator.translate(status, "{\"name\":\"x\"}", null);

        assertThat(e.getClass().getSimpleName()).isEqualTo(expected);
    }

    @Test
    void a403MentioningModerationIsARejectionOfTheImage() {
        DomainException e = translator.translate(
                403, "{\"id\":\"a1\",\"name\":\"content_moderation\",\"errors\":[\"flagged\"]}", null);

        assertThat(e).isInstanceOf(ProviderRejectedException.class);
    }

    @Test
    void anyOther403IsAnOperatorProblem() {
        assertThat(translator.translate(403, "{\"name\":\"forbidden\"}", null))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void rateLimitKeepsTheProviderRetryAfter() {
        assertThat(translator.translate(429, "", "17").retryAfter()).contains(Duration.ofSeconds(17));
        assertThat(translator
                        .translate(429, "", "Wed, 21 Oct 2026 07:28:00 GMT")
                        .retryAfter())
                .isEmpty();
        assertThat(translator.translate(429, "", "0").retryAfter()).isEmpty();
    }

    @Test
    void theInternalMessageKeepsAShortBodyForDiagnosis() {
        String longBody = "x".repeat(2000);

        DomainException e = translator.translate(500, longBody, null);

        assertThat(e).isInstanceOf(ProviderFailureException.class);
        assertThat(e.getMessage()).contains("500").hasSizeLessThan(StabilityErrorTranslator.MAX_BODY_CHARS + 50);
        assertThat(translator.translate(500, "  ", null).getMessage()).contains("(sin cuerpo)");
    }
}
