package com.tunai.api.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class QuotaModelTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Test
    void theDayFollowsTheConfiguredZoneNotUtc() {
        QuotaLimits limits = new QuotaLimits(2, 2, 2, BOGOTA);
        Instant lateNightInBogota = Instant.parse("2026-10-10T03:00:00Z");

        assertThat(limits.dayOf(lateNightInBogota)).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(limits.nextReset(lateNightInBogota)).isEqualTo(Instant.parse("2026-10-10T05:00:00Z"));
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new QuotaLimits(0, 1, 1, BOGOTA)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaLimits(1, 0, 1, BOGOTA)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaLimits(1, 1, 0, BOGOTA)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void statusRejectsInconsistentValues() {
        assertThatThrownBy(() -> new QuotaStatus(2, 3, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaStatus(2, -1, Instant.EPOCH)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void installationIdIsHashedForLogs() {
        InstallationId id = InstallationId.parse("3f1c2a9e-6d0b-4e8a-9f7c-2b5d1e4a6c80");

        assertThat(id.hashForLogs()).hasSize(12).matches("[0-9a-f]+").doesNotContain("3f1c2a9e");
        assertThat(id.hashForLogs())
                .isEqualTo(InstallationId.parse("3F1C2A9E-6D0B-4E8A-9F7C-2B5D1E4A6C80")
                        .hashForLogs());
    }
}
