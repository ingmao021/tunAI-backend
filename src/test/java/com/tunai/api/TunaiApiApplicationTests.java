package com.tunai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.tunai.api.adapter.out.tripo.TripoAdapter;
import com.tunai.api.application.port.out.ModelGeneratorPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** Arranca el contexto completo con una clave de prueba; ninguna prueba genera, así que no se llama a Tripo. */
@SpringBootTest(properties = "tunai.tripo.api-key=tsk_test-0123456789")
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
    void theGeneratorIsTripo() {
        assertThat(modelGenerator).isInstanceOf(TripoAdapter.class);
    }
}
