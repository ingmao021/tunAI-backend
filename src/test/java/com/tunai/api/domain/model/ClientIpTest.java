package com.tunai.api.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClientIpTest {

    @Test
    void normalizesIpv4AndIpv6() {
        assertThat(ClientIp.parse(" 190.24.5.7 ")).contains(new ClientIp("190.24.5.7"));
        assertThat(ClientIp.parse("[2800:E2:3F00::1]")).contains(new ClientIp("2800:e2:3f00::1"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "evil.example.com", "256.1.1.1", "1.2.3", "1.2.3.4; DROP", "::g"})
    void rejectsValuesThatAreNotIpLiterals(String raw) {
        assertThat(ClientIp.parse(raw)).isEmpty();
    }

    @Test
    void masksForLogs() {
        assertThat(new ClientIp("190.24.5.7").masked()).isEqualTo("190.24.5.x");
        assertThat(new ClientIp("2800:e2:3f00:12::1").masked()).isEqualTo("2800:e2:3f00:x");
        assertThat(new ClientIp("190.24.5.7")).hasToString("190.24.5.x");
    }

    @Test
    void theConstructorRejectsNonLiterals() {
        assertThatThrownBy(() -> new ClientIp("localhost")).isInstanceOf(IllegalArgumentException.class);
    }
}
