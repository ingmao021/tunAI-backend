package com.tunai.api.adapter.out.metrics;

import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.domain.exception.ProviderRejectedException;
import com.tunai.api.domain.exception.ProviderTimeoutException;
import com.tunai.api.domain.exception.ProviderUnavailableException;
import com.tunai.api.domain.model.GeneratedModel;
import com.tunai.api.domain.model.VehicleImage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Objects;

/**
 * Decorador que mide cada llamada al proveedor (RNF-BE-012), sin que la aplicación dependa de Micrometer:
 *
 * <ul>
 *   <li>{@code tunai.generations{provider, result}}: generaciones por resultado.
 *   <li>{@code tunai.provider.latency{provider, result}}: latencia de la llamada.
 * </ul>
 *
 * <p>{@code result} es {@code success}, {@code rejected}, {@code timeout}, {@code unavailable} o {@code error}.
 */
public final class MeteredModelGenerator implements ModelGeneratorPort {

    static final String GENERATIONS = "tunai.generations";
    static final String LATENCY = "tunai.provider.latency";

    private final ModelGeneratorPort delegate;
    private final MeterRegistry registry;
    private final String provider;

    public MeteredModelGenerator(ModelGeneratorPort delegate, MeterRegistry registry, String provider) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.provider = Objects.requireNonNull(provider, "provider").toLowerCase(Locale.ROOT);
    }

    @Override
    public GeneratedModel generate(VehicleImage image) {
        Timer.Sample sample = Timer.start(registry);
        String result = "error";
        try {
            GeneratedModel model = delegate.generate(image);
            result = "success";
            return model;
        } catch (RuntimeException e) {
            result = classify(e);
            throw e;
        } finally {
            sample.stop(Timer.builder(LATENCY)
                    .description("Latencia de la llamada al proveedor de generación 3D")
                    .tag("provider", provider)
                    .tag("result", result)
                    .register(registry));
            Counter.builder(GENERATIONS)
                    .description("Generaciones por resultado del proveedor")
                    .tag("provider", provider)
                    .tag("result", result)
                    .register(registry)
                    .increment();
        }
    }

    private static String classify(RuntimeException e) {
        if (e instanceof ProviderRejectedException) {
            return "rejected";
        }
        if (e instanceof ProviderTimeoutException) {
            return "timeout";
        }
        if (e instanceof ProviderUnavailableException) {
            return "unavailable";
        }
        return "error";
    }
}
