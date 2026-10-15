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
    void ipv4CountsByAddress() {
        assertThat(new ClientIp("190.24.5.7").quotaKey()).isEqualTo("190.24.5.7");
    }

    /** RN-10: un cliente suele tener todo un /64; rotar direcciones dentro de él no esquiva el límite. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "2800:e2:3f00:12::1",
                "2800:e2:3f00:12:a:b:c:d",
                "2800:00e2:3f00:0012:0000:0000:0000:0001",
                "2800:e2:3f00:12:ffff:ffff:ffff:ffff"
            })
    void ipv6CountsByItsSlash64(String address) {
        assertThat(ClientIp.parse(address).orElseThrow().quotaKey()).isEqualTo("2800:e2:3f00:12::/64");
    }

    @Test
    void ipv6PrefixesAreCanonical() {
        assertThat(new ClientIp("::1").quotaKey()).isEqualTo("0:0:0:0::/64");
        assertThat(new ClientIp("0:0::1").quotaKey()).isEqualTo("0:0:0:0::/64");
        assertThat(new ClientIp("2800:e2::").quotaKey()).isEqualTo("2800:e2:0:0::/64");
        assertThat(new ClientIp("2800:e2:3f00:12::1").quotaKey())
                .isNotEqualTo(new ClientIp("2800:e2:3f00:13::1").quotaKey());
    }

    @Test
    void anIpv4MappedAddressCountsAsTheIpv4() {
        assertThat(new ClientIp("::ffff:190.24.5.7").quotaKey()).isEqualTo("190.24.5.7");
        assertThat(new ClientIp("::ffff:be18:507").quotaKey()).isEqualTo("190.24.5.7");
    }

    @Test
    void anIpv6WithAnEmbeddedIpv4ThatIsNotMappedCountsByItsSlash64() {
        assertThat(new ClientIp("64:ff9b::190.24.5.7").quotaKey()).isEqualTo("64:ff9b:0:0::/64");
    }

    /** Un texto que pasa el filtro básico pero no es una IPv6 bien formada sigue contando por sí mismo. */
    @ParameterizedTest
    @ValueSource(strings = {"1::2::3", "1:2:3:4:5:6:7:8:9", "12345::1", "::1.2.3.256", "1.2.3.4::"})
    void aMalformedIpv6FallsBackToItsText(String raw) {
        ClientIp.parse(raw).ifPresent(ip -> assertThat(ip.quotaKey()).isEqualTo(ip.value()));
    }

    @Test
    void theConstructorRejectsNonLiterals() {
        assertThatThrownBy(() -> new ClientIp("localhost")).isInstanceOf(IllegalArgumentException.class);
    }
}
