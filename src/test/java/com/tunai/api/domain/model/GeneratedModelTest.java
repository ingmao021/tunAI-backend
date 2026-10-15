package com.tunai.api.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.domain.exception.ProviderFailureException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GeneratedModelTest {

    static final byte[] MINIMAL_GLB = "glTF\u0002\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);

    @Test
    void acceptsBytesStartingWithGlbMagic() throws IOException {
        GeneratedModel model = GeneratedModel.fromGlb(MINIMAL_GLB);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        model.writeTo(out);

        assertThat(model.sizeInBytes()).isEqualTo(MINIMAL_GLB.length);
        assertThat(out.toByteArray()).isEqualTo(MINIMAL_GLB);
    }

    @Test
    void rejectsNonGlbAsProviderFailure() {
        byte[] json = "{\"error\":\"x\"}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> GeneratedModel.fromGlb(json)).isInstanceOf(ProviderFailureException.class);
    }

    @Test
    void rejectsBytesShorterThanMagic() {
        assertThatThrownBy(() -> GeneratedModel.fromGlb(new byte[] {'g', 'l'}))
                .isInstanceOf(ProviderFailureException.class);
    }

    /** {@code fromGlb} no copia (el GLB puede pesar decenas de MB), pero lo que se expone sí es una copia. */
    @Test
    void theExposedBytesAreACopy() {
        GeneratedModel model = GeneratedModel.fromGlb(MINIMAL_GLB.clone());

        model.toByteArray()[1] = 'X';

        assertThat(model.toByteArray()).isEqualTo(MINIMAL_GLB);
    }
}
