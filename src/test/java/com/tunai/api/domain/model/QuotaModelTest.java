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
        QuotaLimits limits = new QuotaLimits(45, 15, 2, 2, BOGOTA);
        Instant lateNightInBogota = Instant.parse("2026-10-10T03:00:00Z");

        assertThat(limits.dayOf(lateNightInBogota)).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(limits.nextReset(lateNightInBogota)).isEqualTo(Instant.parse("2026-10-10T05:00:00Z"));
    }

    @Test
    void limitsMustBePositive() {
        assertThatThrownBy(() -> new QuotaLimits(-1, 15, 1, 1, BOGOTA)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaLimits(45, 0, 1, 1, BOGOTA)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaLimits(45, 15, 0, 1, BOGOTA)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaLimits(45, 15, 1, 0, BOGOTA)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theInitialCreditsCoverWholeModelsOnly() {
        assertThat(new QuotaLimits(45, 15, 2, 2, BOGOTA).modelsPerInstallation())
                .isEqualTo(3);
        assertThat(new QuotaLimits(40, 15, 2, 2, BOGOTA).modelsPerInstallation())
                .isEqualTo(2);
    }

    @Test
    void statusRejectsInconsistentValues() {
        assertThatThrownBy(() -> new QuotaStatus(2, 3, Instant.EPOCH, 45, 15))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaStatus(2, -1, Instant.EPOCH, 45, 15))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaStatus(2, 1, Instant.EPOCH, -1, 15))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QuotaStatus(2, 1, Instant.EPOCH, 45, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void accountIdIsNormalizedAndHashedForLogs() {
        AccountId id = new AccountId("  Demo ");

        assertThat(id.value()).isEqualTo("demo");
        assertThat(id.hashForLogs()).hasSize(12).matches("[0-9a-f]+").doesNotContain("demo");
        assertThat(id.hashForLogs()).isEqualTo(new AccountId("DEMO").hashForLogs());
        assertThatThrownBy(() -> new AccountId("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
