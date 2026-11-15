package com.tunai.api.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tunai.api.adapter.out.auth.PasswordHash;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Inicio de sesión de extremo a extremo. Arranca la aplicación completa, que no inicia sin clave de Tripo (RNF-BE-015):
 * la clave es de prueba y la URL de Tripo no existe, así la prueba no depende de que la clave real esté en el entorno
 * (en CI no está) y ninguna petición puede llegar al proveedor (RN-15).
 */
@SpringBootTest(
        properties = {
            "tunai.auth.token-secret=secreto-de-prueba-con-mas-de-32-caracteres",
            "tunai.tripo.api-key=tsk_test-auth",
            "tunai.tripo.base-url=http://127.0.0.1:9/v3"
        })
@AutoConfigureMockMvc
class AuthApiTest {

    private static final String PASSWORD = "clave-demo";

    @DynamicPropertySource
    static void users(DynamicPropertyRegistry registry) {
        registry.add("tunai.auth.users", () -> "Demo:" + PasswordHash.hash(PASSWORD));
    }

    @Autowired
    MockMvcTester mvc;

    @Test
    void validCredentialsReturnATokenThatOpensTheQuotaEndpoint() throws Exception {
        MvcTestResult login = login("{\"username\":\"demo\",\"password\":\"" + PASSWORD + "\"}");

        assertThat(login).hasStatusOk().headers().hasValue("Cache-Control", "no-store");
        JsonNode body = new ObjectMapper().readTree(login.getResponse().getContentAsString());
        assertThat(body.path("username").asString()).isEqualTo("demo");
        assertThat(body.path("expiresAt").asString()).isNotBlank();

        assertThat(mvc.get()
                        .uri("/api/v1/quota")
                        .header("Authorization", "Bearer " + body.path("token").asString())
                        .exchange())
                .hasStatusOk();
    }

    @Test
    void usernameIsCaseInsensitive() {
        assertThat(login("{\"username\":\"DEMO\",\"password\":\"" + PASSWORD + "\"}"))
                .hasStatusOk();
    }

    @Test
    void wrongPasswordGets401WithProblemJson() {
        assertThat(login("{\"username\":\"demo\",\"password\":\"otra\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void unknownUserGets401() {
        assertThat(login("{\"username\":\"nadie\",\"password\":\"x\"}")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void blankFieldsGet401() {
        assertThat(login("{\"username\":\"\",\"password\":\"\"}")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(login("{}")).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void malformedJsonGets400() {
        assertThat(login("no-es-json"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("BAD_REQUEST");
    }

    @Test
    void quotaWithoutAValidTokenGets401() {
        assertThat(mvc.get().uri("/api/v1/quota").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get()
                        .uri("/api/v1/quota")
                        .header("Authorization", "Bearer falso")
                        .exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult login(String body) {
        return mvc.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }
}
