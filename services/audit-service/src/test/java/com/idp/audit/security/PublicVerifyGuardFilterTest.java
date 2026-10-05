package com.idp.audit.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PublicVerifyGuardFilterTest {

    private static MockHttpServletRequest post(byte[] body, String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/v1/audit/public/verify-signature");
        r.setRemoteAddr(ip);
        r.setContent(body);
        return r;
    }

    private static int run(PublicVerifyGuardFilter f, HttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void h3_cuerpoMayorAlLimiteSeRechazaCon413() throws Exception {
        PublicVerifyGuardFilter f = new PublicVerifyGuardFilter(1024, 100, 1000);
        assertEquals(200, run(f, post(new byte[1024], "1.1.1.1")));
        assertEquals(413, run(f, post(new byte[1025], "1.1.1.1")));
    }

    @Test
    void h3_limiteDeTasaPorIpResponde429SinAfectarOtrasIps() throws Exception {
        PublicVerifyGuardFilter f = new PublicVerifyGuardFilter(1024, 3, 1000);
        for (int i = 0; i < 3; i++) {
            assertEquals(200, run(f, post(new byte[10], "2.2.2.2")));
        }
        assertEquals(429, run(f, post(new byte[10], "2.2.2.2")));
        assertEquals(200, run(f, post(new byte[10], "3.3.3.3")));
    }

    @Test
    void h3_soloAplicaALasRutasPublicas() throws Exception {
        PublicVerifyGuardFilter f = new PublicVerifyGuardFilter(10, 1, 1000);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest r = new MockHttpServletRequest("GET", "/v1/audit/verify");
            assertEquals(200, run(f, r));
        }
    }

    @Test
    void h3_topeGlobalResponde429AunqueCadaIpEsteBajoSuLimite() throws Exception {
        PublicVerifyGuardFilter f = new PublicVerifyGuardFilter(1024, 100, 3);
        for (int i = 0; i < 3; i++) {
            assertEquals(200, run(f, post(new byte[10], "10.0.0." + i)));
        }
        assertEquals(429, run(f, post(new byte[10], "10.0.0.99")));
    }

    @Test
    void h3_usaLaIpResueltaPorElContenedor() throws Exception {
        PublicVerifyGuardFilter f = new PublicVerifyGuardFilter(1024, 1, 1000);
        MockHttpServletRequest a = post(new byte[10], "7.7.7.7");
        a.addHeader("X-Forwarded-For", "9.9.9.9");
        MockHttpServletRequest b = post(new byte[10], "7.7.7.7");
        b.addHeader("X-Forwarded-For", "8.8.8.8");
        assertEquals(200, run(f, a));
        // El filtro no relee X-Forwarded-For: el cliente no puede rotar la clave del limite con el header.
        assertEquals(429, run(f, b));
    }
}
