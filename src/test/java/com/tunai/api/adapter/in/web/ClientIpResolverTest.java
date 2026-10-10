package com.tunai.api.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tunai.api.domain.model.ClientIp;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** RF-BE-022: la IP la decide el proxy de confianza, nunca el primer valor de X-Forwarded-For. */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver("CF-Connecting-IP", 1);

    private static MockHttpServletRequest request(String remote) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remote);
        return request;
    }

    private static ClientIp ip(String value) {
        return ClientIp.parse(value).orElseThrow();
    }

    @Test
    void prefersTheEdgeProxyHeader() {
        MockHttpServletRequest request = request("10.0.0.9");
        request.addHeader("CF-Connecting-IP", "190.24.5.7");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 190.24.5.7, 172.68.1.1");

        assertThat(resolver.resolve(request)).isEqualTo(ip("190.24.5.7"));
    }

    @Test
    void withoutTheHeaderUsesTheValueAddedByTheTrustedProxy() {
        MockHttpServletRequest request = request("10.0.0.9");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 190.24.5.7");

        assertThat(resolver.resolve(request)).isEqualTo(ip("190.24.5.7"));
    }

    @Test
    void neverTrustsTheLeftmostValueControlledByTheClient() {
        MockHttpServletRequest request = request("10.0.0.9");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 190.24.5.7");

        assertThat(resolver.resolve(request)).isNotEqualTo(ip("6.6.6.6"));
    }

    @Test
    void countsSeveralTrustedProxiesFromTheRight() {
        ClientIpResolver twoProxies = new ClientIpResolver("", 2);
        MockHttpServletRequest request = request("10.0.0.9");
        request.addHeader("X-Forwarded-For", "6.6.6.6");
        request.addHeader("X-Forwarded-For", "190.24.5.7, 172.68.1.1");

        assertThat(twoProxies.resolve(request)).isEqualTo(ip("190.24.5.7"));
    }

    @Test
    void ignoresAnInvalidHeaderValueAndFallsBack() {
        MockHttpServletRequest request = request("10.0.0.9");
        request.addHeader("CF-Connecting-IP", "unknown");

        assertThat(resolver.resolve(request)).isEqualTo(ip("10.0.0.9"));
    }

    @Test
    void fallsBackToTheConnectionAddress() {
        assertThat(resolver.resolve(request("127.0.0.1"))).isEqualTo(ip("127.0.0.1"));
    }

    @Test
    void tooFewHopsForTheTrustedProxiesFallsBackToTheConnection() {
        ClientIpResolver threeProxies = new ClientIpResolver(null, 3);
        MockHttpServletRequest request = request("10.0.0.9");
        request.addHeader("X-Forwarded-For", "190.24.5.7, 172.68.1.1");

        assertThat(threeProxies.resolve(request)).isEqualTo(ip("10.0.0.9"));
    }

    @Test
    void rejectsANonPositiveProxyCount() {
        assertThatThrownBy(() -> new ClientIpResolver("CF-Connecting-IP", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
