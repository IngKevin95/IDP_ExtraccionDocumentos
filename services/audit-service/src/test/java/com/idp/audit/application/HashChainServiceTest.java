package com.idp.audit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** AC-01 (calculo del hash) y soporte de AC-02/AC-07: determinismo y sensibilidad del hash. */
class HashChainServiceTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID EVENT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final Instant T0 = Instant.parse("2026-10-04T10:15:00Z");

    private static String canonical(String payloadSha) {
        return HashChainService.canonicalEvent(TENANT, EVENT, "documento.recibido", null, null, "system", T0,
                payloadSha);
    }

    @Test
    void ac01_hashEsSha256DePrevSeqYEventoCanonico() {
        String canonical = canonical("p");
        String expected = HashChainService.sha256Hex(("prev" + 7 + canonical).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(expected, HashChainService.hash("prev", 7, canonical));
        assertEquals(64, expected.length());
    }

    @Test
    void ac01_genesisSon64CerosYElHashEsDeterministico() {
        assertEquals("0".repeat(64), HashChainService.GENESIS);
        assertEquals(HashChainService.hash(HashChainService.GENESIS, 1, canonical("p")),
                HashChainService.hash(HashChainService.GENESIS, 1, canonical("p")));
    }

    @Test
    void ac02_cualquierCambioDePrevSecuenciaOEventoCambiaElHash() {
        String base = HashChainService.hash("prev", 1, canonical("p"));
        assertNotEquals(base, HashChainService.hash("prev2", 1, canonical("p")));
        assertNotEquals(base, HashChainService.hash("prev", 2, canonical("p")));
        assertNotEquals(base, HashChainService.hash("prev", 1, canonical("q")));
    }

    @Test
    void ac07_elHashDependeDeLaHuellaDelPayloadNoDelPayload() throws Exception {
        JsonNode a = M.readTree("{\"b\":1,\"a\":{\"y\":2,\"x\":[3,1]}}");
        JsonNode b = M.readTree("{ \"a\" : {\"x\":[3,1], \"y\":2}, \"b\":1 }");
        assertEquals(HashChainService.payloadSha256(a), HashChainService.payloadSha256(b));
        assertNotEquals(HashChainService.payloadSha256(a),
                HashChainService.payloadSha256(M.readTree("{\"b\":1,\"a\":{\"y\":2,\"x\":[1,3]}}")));
    }

    @Test
    void canonicoOrdenaClavesYEscapaTexto() throws Exception {
        assertEquals("{\"a\":[1,\"x\\\"y\",null],\"b\":true}",
                CanonicalJson.write(M.readTree("{\"b\":true,\"a\":[1,\"x\\\"y\",null]}")));
        assertTrue(canonical("p").contains("\"correlationId\":null"));
    }
}
