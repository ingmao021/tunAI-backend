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
        assertThatCode(() -> options("v3.1-20260211", faces)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, TripoOptions.MIN_FACES - 1, TripoOptions.MAX_FACES + 1})
    void rejectsFaceLimitsOutOfRange(int faces) {
        assertThatThrownBy(() -> options("v3.1-20260211", faces)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsABlankModel() {
        assertThatThrownBy(() -> options(" ", 50_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAMissingGeometryQuality() {
        assertThatThrownBy(() ->
                        new TripoOptions("v3.1-20260211", 50_000, TripoOptions.TextureQuality.STANDARD, null, true))
                .isInstanceOf(NullPointerException.class);
    }

    private static TripoOptions options(String model, int faces) {
        return new TripoOptions(
                model, faces, TripoOptions.TextureQuality.STANDARD, TripoOptions.GeometryQuality.STANDARD, true);
    }
}
