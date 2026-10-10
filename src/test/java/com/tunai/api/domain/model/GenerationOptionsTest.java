package com.tunai.api.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.domain.model.GenerationOptions.Remesh;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GenerationOptionsTest {

    @Test
    void defaultsMatchThePrd() {
        assertThat(GenerationOptions.DEFAULTS).isEqualTo(new GenerationOptions(1024, 0.85, Remesh.NONE));
    }

    @ParameterizedTest
    @ValueSource(ints = {256, 1000, 4096})
    void rejectsUnsupportedTextureResolution(int resolution) {
        assertThatThrownBy(() -> new GenerationOptions(resolution, 0.85, Remesh.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.05, 1.01})
    void rejectsForegroundRatioOutOfRange(double ratio) {
        assertThatThrownBy(() -> new GenerationOptions(1024, ratio, Remesh.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
