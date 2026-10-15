package com.tunai.api.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.application.port.in.GenerateModelUseCase.GenerateCommand;
import com.tunai.api.application.port.in.GenerateModelUseCase.GenerationResult;
import com.tunai.api.application.port.out.ConcurrencyLimiterPort;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.application.port.out.QuotaStorePort;
import com.tunai.api.domain.exception.ProviderFailureException;
import com.tunai.api.domain.exception.ProviderRejectedException;
import com.tunai.api.domain.exception.ProviderTimeoutException;
import com.tunai.api.domain.exception.QuotaExceededException;
import com.tunai.api.domain.exception.ServiceBusyException;
import com.tunai.api.domain.model.ClientIp;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.ImageFormat;
import com.tunai.api.domain.model.InstallationId;
import com.tunai.api.domain.model.QuotaReservation;
import com.tunai.api.domain.model.QuotaStatus;
import com.tunai.api.domain.model.VehicleImage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** §13.5: permiso → reserva → proveedor → confirmar; ante cualquier fallo se libera la reserva (RN-03). */
class GenerateModelServiceTest {

    private static final byte[] GLB = "glTF\u0002\u0000\u0000\u0000".getBytes(StandardCharsets.ISO_8859_1);
    private static final QuotaStatus AFTER_COMMIT =
            new QuotaStatus(3, 2, Instant.parse("2026-10-10T05:00:00Z"), 30, 15);
    private static final GenerateCommand COMMAND = new GenerateCommand(
            new InstallationId(UUID.randomUUID()),
            ClientIp.parse("1.2.3.4").orElseThrow(),
            new VehicleImage(new byte[] {1}, ImageFormat.JPEG, 512, 512));

    private final List<String> events = new ArrayList<>();
    private final RecordingQuota quota = new RecordingQuota();
    private final RecordingLimiter limiter = new RecordingLimiter();

    private GenerateModelService service(ModelGeneratorPort generator, long maxModelBytes) {
        return new GenerateModelService(limiter, quota, generator, maxModelBytes);
    }

    private ModelGeneratorPort succeeding() {
        return image -> {
            events.add("generate");
            return GeneratedModel.fromGlb(GLB);
        };
    }

    @Test
    void successCommitsTheReservationAndReturnsTheQuota() {
        GenerationResult result = service(succeeding(), 1024).generate(COMMAND);

        assertThat(result.model().toByteArray()).isEqualTo(GLB);
        assertThat(result.quota()).isEqualTo(AFTER_COMMIT);
        assertThat(events).containsExactly("acquire", "reserve", "generate", "commit", "close");
    }

    @Test
    void passesThePhotoToTheProvider() {
        ModelGeneratorPort generator = image -> {
            assertThat(image).isSameAs(COMMAND.image());
            return GeneratedModel.fromGlb(GLB);
        };

        new GenerateModelService(limiter, quota, generator, 1024).generate(COMMAND);
    }

    static Stream<Supplier<RuntimeException>> providerFailures() {
        return Stream.of(
                () -> new ProviderRejectedException("moderación"),
                () -> new ProviderFailureException("5xx"),
                () -> new ProviderTimeoutException("timeout", null),
                () -> new IllegalStateException("error inesperado"));
    }

    @ParameterizedTest
    @MethodSource("providerFailures")
    void anyFailureReleasesTheReservationAndThePermit(Supplier<RuntimeException> failure) {
        ModelGeneratorPort failing = image -> {
            events.add("generate");
            throw failure.get();
        };

        assertThatThrownBy(() -> service(failing, 1024).generate(COMMAND)).isInstanceOf(RuntimeException.class);
        assertThat(events).containsExactly("acquire", "reserve", "generate", "release", "close");
    }

    @Test
    void anOversizedModelIsAProviderFailureAndReleasesTheReservation() {
        assertThatThrownBy(() -> service(succeeding(), GLB.length - 1).generate(COMMAND))
                .isInstanceOf(ProviderFailureException.class);
        assertThat(events).containsExactly("acquire", "reserve", "generate", "release", "close");
    }

    @Test
    void aFailedCommitStillReleasesTheReservation() {
        quota.failOnCommit = true;

        assertThatThrownBy(() -> service(succeeding(), 1024).generate(COMMAND))
                .isInstanceOf(IllegalStateException.class);
        assertThat(events).containsExactly("acquire", "reserve", "generate", "commit", "release", "close");
    }

    @Test
    void exhaustedQuotaNeverCallsTheProvider() {
        quota.rejectReserve = true;

        assertThatThrownBy(() -> service(succeeding(), 1024).generate(COMMAND))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(events).containsExactly("acquire", "reserve", "close");
    }

    @Test
    void noConcurrencySlotMeansNoReservationAndNoProviderCall() {
        limiter.busy = true;

        assertThatThrownBy(() -> service(succeeding(), 1024).generate(COMMAND))
                .isInstanceOf(ServiceBusyException.class);
        assertThat(events).containsExactly("acquire");
    }

    @Test
    void neverRetriesTheProvider() {
        ModelGeneratorPort failing = image -> {
            events.add("generate");
            throw new ProviderFailureException("5xx");
        };

        assertThatThrownBy(() -> service(failing, 1024).generate(COMMAND)).isInstanceOf(ProviderFailureException.class);
        assertThat(events).filteredOn("generate"::equals).hasSize(1);
    }

    @Test
    void rejectsANonPositiveMaximum() {
        assertThatThrownBy(() -> service(succeeding(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    private final class RecordingQuota implements QuotaStorePort {
        boolean rejectReserve;
        boolean failOnCommit;

        @Override
        public QuotaReservation tryReserve(InstallationId installationId, ClientIp clientIp) {
            events.add("reserve");
            if (rejectReserve) {
                throw new QuotaExceededException(
                        "agotada", new QuotaStatus(2, 0, Instant.EPOCH, 45, 15), Duration.ofHours(1));
            }
            return new QuotaReservation(UUID.randomUUID(), installationId, clientIp, LocalDate.of(2026, 10, 9));
        }

        @Override
        public QuotaStatus commit(QuotaReservation reservation) {
            events.add("commit");
            if (failOnCommit) {
                throw new IllegalStateException("fallo al confirmar");
            }
            return AFTER_COMMIT;
        }

        @Override
        public void release(QuotaReservation reservation) {
            events.add("release");
        }

        @Override
        public QuotaStatus status(InstallationId installationId, ClientIp clientIp) {
            throw new UnsupportedOperationException();
        }
    }

    private final class RecordingLimiter implements ConcurrencyLimiterPort {
        boolean busy;

        @Override
        public Permit acquire() {
            events.add("acquire");
            if (busy) {
                throw new ServiceBusyException("ocupado", Duration.ofSeconds(5));
            }
            return () -> events.add("close");
        }
    }
}
