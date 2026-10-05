package com.idp.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class NoAmbientCredentialsRequestMatcherTest {

    private final NoAmbientCredentialsRequestMatcher matcher = new NoAmbientCredentialsRequestMatcher();

    @Test
    void conBearer_seExcluyeDeCsrf() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/v1/x");
        r.addHeader("Authorization", "Bearer abc.def.ghi");
        assertThat(matcher.matches(r)).isTrue();
    }

    @Test
    void bearerEnMinusculas_tambienSeExcluye() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/v1/x");
        r.addHeader("Authorization", "bearer abc");
        assertThat(matcher.matches(r)).isTrue();
    }

    @Test
    void anonima_seExcluyePorqueNoHayCredencialAmbientalYRecibira401() {
        assertThat(matcher.matches(new MockHttpServletRequest("POST", "/v1/x"))).isTrue();
    }

    @Test
    void conCookie_mantieneCsrf() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/v1/x");
        r.addHeader("Cookie", "JSESSIONID=1");
        r.setCookies(new Cookie("JSESSIONID", "1"));
        assertThat(matcher.matches(r)).isFalse();
    }

    @Test
    void conBasic_mantieneCsrf() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/v1/x");
        r.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        assertThat(matcher.matches(r)).isFalse();
    }

    @Test
    void bearerConCookie_seExcluyePorqueElTokenEsExplicito() {
        assertThat(NoAmbientCredentialsRequestMatcher.isIgnorable("Bearer abc", "JSESSIONID=1")).isTrue();
    }
}
