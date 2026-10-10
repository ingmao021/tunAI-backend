package com.tunai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.tunai.api.adapter.out.fake.FakeModelGenerator;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import com.tunai.api.domain.model.GenerationOptions;
import com.tunai.api.domain.model.ImageFormat;
import com.tunai.api.domain.model.VehicleImage;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest(properties = "tunai.generator.fake.delay=0s")
@AutoConfigureMockMvc
class TunaiApiApplicationTests {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ModelGeneratorPort modelGenerator;

    @Test
    void healthIsPublicAndHidesDetails() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .isStrictlyEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void onlyHealthIsExposed() {
        assertThat(mvc.get().uri("/actuator/env")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/actuator/beans")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/actuator/heapdump")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void everyResponseCarriesATraceId() {
        assertThat(mvc.get().uri("/actuator/health"))
                .headers()
                .hasHeaderSatisfying(
                        "X-Trace-Id",
                        values -> assertThat(values).singleElement().asString().matches("[0-9a-f]{16}"));
    }

    @Test
    void defaultProviderIsFakeAndServesTheBundledGlb() throws IOException {
        assertThat(modelGenerator).isInstanceOf(FakeModelGenerator.class);

        VehicleImage image = new VehicleImage(new byte[] {1}, ImageFormat.JPEG, 512, 512);
        assertThat(modelGenerator.generate(image, GenerationOptions.DEFAULTS).sizeInBytes())
                .isEqualTo(new ClassPathResource("fake/vehicle-sample.glb").contentLength());
    }
}
