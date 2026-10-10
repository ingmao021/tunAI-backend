package com.tunai.api.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.domain.exception.InvalidInstallationIdException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class InstallationIdTest {

    @Test
    void parsesACanonicalUuid() {
        InstallationId id = InstallationId.parse("3f1c2a9e-6d0b-4e8a-9f7c-2b5d1e4a6c80");

        assertThat(id.value()).isEqualTo(UUID.fromString("3f1c2a9e-6d0b-4e8a-9f7c-2b5d1e4a6c80"));
        assertThat(id).hasToString("3f1c2a9e-6d0b-4e8a-9f7c-2b5d1e4a6c80");
    }

    @Test
    void toleratesSurroundingWhitespaceAndUppercase() {
        assertThat(InstallationId.parse("  3F1C2A9E-6D0B-4E8A-9F7C-2B5D1E4A6C80 "))
                .isEqualTo(InstallationId.parse("3f1c2a9e-6d0b-4e8a-9f7c-2b5d1e4a6c80"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "   ",
                "not-a-uuid",
                "1-1-1-1-1",
                "3f1c2a9e6d0b4e8a9f7c2b5d1e4a6c80",
                "3f1c2a9e-6d0b-4e8a-9f7c-2b5d1e4a6c8z"
            })
    void rejectsMissingOrMalformedValues(String raw) {
        assertThatThrownBy(() -> InstallationId.parse(raw)).isInstanceOf(InvalidInstallationIdException.class);
    }
}
