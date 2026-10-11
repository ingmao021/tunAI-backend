package com.tunai.api.adapter.out.tripo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TripoOptionsTest {

    @ParameterizedTest
    @ValueSource(ints = {TripoOptions.MIN_FACES, 50_000, TripoOptions.MAX_FACES})
    void acceptsFaceLimitsInRange(int faces) {
        assertThatCode(() -> new TripoOptions("v3.1-20260211", faces, TripoOptions.TextureQuality.STANDARD, true))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, TripoOptions.MIN_FACES - 1, TripoOptions.MAX_FACES + 1})
    void rejectsFaceLimitsOutOfRange(int faces) {
        assertThatThrownBy(() -> new TripoOptions("v3.1-20260211", faces, TripoOptions.TextureQuality.STANDARD, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankModel() {
        assertThatThrownBy(() -> new TripoOptions(" ", 50_000, TripoOptions.TextureQuality.STANDARD, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
