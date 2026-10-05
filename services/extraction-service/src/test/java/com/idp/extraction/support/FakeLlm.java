package com.idp.extraction.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.llm.LlmProvider;
import com.idp.llm.LlmRequest;
import com.idp.llm.LlmResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LlmProvider falso y determinista: responde segun la tarea del prompt, sin red ni modelos reales. */
public final class FakeLlm implements LlmProvider {

    /** Valor devuelto para un campo. */
    public record Spec(String value, Double confidence, boolean evidence) {
        public static Spec of(String value) {
            return new Spec(value, 0.99, true);
        }

        public static Spec of(String value, double confidence) {
            return new Spec(value, confidence, true);
        }

        public static Spec noEvidence(String value, double confidence) {
            return new Spec(value, confidence, false);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PAGE = Pattern.compile("SOLO de la pagina (\\d+)");

    public final Map<String, Spec> fields = new LinkedHashMap<>();
    public final Map<Integer, List<Map<String, Spec>>> rowsByPage = new HashMap<>();
    public final Map<String, Spec> secondPassFields = new LinkedHashMap<>();
    public String classification = "EC";
    public double classificationConfidence = 0.98;
    public String model = "fake-model-1";
    public int inputTokens = 100;
    public int outputTokens = 50;
    public boolean failWith5xx;
    public String rawContentOverride;

    public final AtomicInteger calls = new AtomicInteger();
    public final AtomicInteger classifyCalls = new AtomicInteger();
    public final AtomicInteger secondPassCalls = new AtomicInteger();
    public final List<LlmRequest> requests = new CopyOnWriteArrayList<>();

    /** Respuesta por defecto: oficio de embargo coactivo limpio. */
    public static FakeLlm cleanEc() {
        FakeLlm f = new FakeLlm();
        f.fields.put("radicado", Spec.of("11001310300520240012300"));
        f.fields.put("autoridad_emisora", Spec.of("Direccion de Impuestos"));
        f.fields.put("ciudad", Spec.of("Bogota D.C."));
        f.fields.put("fecha_oficio", Spec.of("2026-09-30"));
        f.fields.put("tipo_medida", Spec.of("Embargo coactivo"));
        f.fields.put("banco_destinatario", Spec.of("Banco Ejemplo"));
        f.fields.put("monto_numeros", Spec.of("15000000"));
        f.fields.put("monto_letras", Spec.of("quince millones de pesos"));
        f.fields.put("productos_o_cuentas", Spec.of("Cuenta de ahorros 123456789"));
        f.fields.put("entidad_coactiva", Spec.of("Entidad Coactiva Ejemplo"));
        f.fields.put("numero_resolucion", Spec.of("R-2026-001"));
        f.rowsByPage.put(1, new ArrayList<>(List.of(
            row("Maria Perez", "CC", "1234567", "10000000", "Cuenta de ahorros 123456789"),
            row("Comercial SAS", "NIT", "900123456-8", "5000000", "cuenta corriente 987654321"))));
        return f;
    }

    public static Map<String, Spec> row(String nombre, String tipo, String numero, String monto, String productos) {
        Map<String, Spec> r = new LinkedHashMap<>();
        r.put("nombre", Spec.of(nombre));
        r.put("tipo_identificacion", Spec.of(tipo));
        r.put("numero_identificacion", Spec.of(numero));
        r.put("monto", Spec.of(monto));
        r.put("productos", Spec.of(productos));
        return r;
    }

    @Override
    public LlmResponse generate(LlmRequest request) {
        calls.incrementAndGet();
        requests.add(request);
        if (failWith5xx) {
            throw new IllegalStateException("HTTP 500 simulado");
        }
        String p = request.prompt();
        String json;
        if (rawContentOverride != null) {
            json = rawContentOverride;
        } else if (p.contains("TAREA: clasifica")) {
            classifyCalls.incrementAndGet();
            ObjectNode n = MAPPER.createObjectNode();
            n.put("tipologia", classification);
            n.put("confidence", classificationConfidence);
            json = n.toString();
        } else if (p.contains("TAREA: revision focalizada")) {
            secondPassCalls.incrementAndGet();
            ObjectNode root = MAPPER.createObjectNode();
            ObjectNode f = root.putObject("fields");
            secondPassFields.forEach((k, v) -> f.set(k, node(v)));
            root.putArray("cells");
            json = root.toString();
        } else if (p.contains("TAREA: extrae los campos")) {
            ObjectNode root = MAPPER.createObjectNode();
            ObjectNode f = root.putObject("fields");
            fields.forEach((k, v) -> f.set(k, node(v)));
            json = root.toString();
        } else if (p.contains("TAREA: extrae la tabla")) {
            Matcher m = PAGE.matcher(p);
            int page = m.find() ? Integer.parseInt(m.group(1)) : 1;
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode rows = root.putArray("rows");
            for (Map<String, Spec> r : rowsByPage.getOrDefault(page, List.of())) {
                ObjectNode row = rows.addObject();
                r.forEach((k, v) -> row.set(k, node(v)));
            }
            json = root.toString();
        } else {
            json = "{}";
        }
        return new LlmResponse(json, model, "STOP", new LlmResponse.Usage(inputTokens, outputTokens));
    }

    private static ObjectNode node(Spec s) {
        ObjectNode n = MAPPER.createObjectNode();
        if (s.value() == null) {
            n.putNull("value");
        } else {
            n.put("value", s.value());
        }
        if (s.confidence() != null) {
            n.put("confidence", s.confidence());
        }
        if (s.evidence()) {
            n.put("page", 1);
            n.put("quote", s.value() == null ? "" : s.value());
            n.putArray("bbox").add(0.1).add(0.2).add(0.3).add(0.05);
        }
        return n;
    }
}
