package com.idp.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.llm.LlmProvider;
import com.idp.llm.LlmRequest;
import com.idp.llm.LlmResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LlmProvider falso y determinista: oficio EJ... de embargo (EC) con 2 demandados, sin red ni modelos reales. */
final class DeterministicLlm implements LlmProvider {

    enum Mode { CLEAN, INCONSISTENT_AMOUNT }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern PAGE = Pattern.compile("SOLO de la pagina ([0-9]+)");

    volatile Mode mode = Mode.CLEAN;

    @Override
    public LlmResponse generate(LlmRequest request) {
        String p = request.prompt();
        String json;
        if (p.contains("TAREA: clasifica")) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("tipologia", "EC");
            n.put("confidence", 0.98);
            json = n.toString();
        } else if (p.contains("TAREA: revision focalizada")) {
            ObjectNode root = MAPPER.createObjectNode();
            root.putObject("fields");
            root.putArray("cells");
            json = root.toString();
        } else if (p.contains("TAREA: extrae los campos")) {
            ObjectNode root = MAPPER.createObjectNode();
            ObjectNode f = root.putObject("fields");
            fields().forEach((k, v) -> f.set(k, node(v, 0.99)));
            json = root.toString();
        } else if (p.contains("TAREA: extrae la tabla")) {
            Matcher m = PAGE.matcher(p);
            int page = m.find() ? Integer.parseInt(m.group(1)) : 1;
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode rows = root.putArray("rows");
            if (page == 1) {
                for (Map<String, String> r : demandados()) {
                    ObjectNode row = rows.addObject();
                    r.forEach((k, v) -> row.set(k, node(v, 0.99)));
                }
            }
            json = root.toString();
        } else {
            json = "{}";
        }
        return new LlmResponse(json, "fake-model-1", "STOP", new LlmResponse.Usage(100, 50));
    }

    private Map<String, String> fields() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("radicado", "11001310300520240012300");
        f.put("autoridad_emisora", "Direccion de Impuestos");
        f.put("ciudad", "Bogota D.C.");
        f.put("fecha_oficio", "2026-09-30");
        f.put("tipo_medida", "Embargo coactivo");
        f.put("banco_destinatario", "Banco Ejemplo");
        f.put("monto_numeros", "15000000");
        f.put("monto_letras", mode == Mode.CLEAN ? "quince millones de pesos" : "veinte millones de pesos");
        f.put("productos_o_cuentas", "Cuenta de ahorros 123456789");
        f.put("entidad_coactiva", "Entidad Coactiva Ejemplo");
        f.put("numero_resolucion", "R-2026-001");
        return f;
    }

    private static List<Map<String, String>> demandados() {
        return List.of(
            row("Maria Perez", "CC", "1234567", "10000000", "Cuenta de ahorros 123456789"),
            row("Comercial SAS", "NIT", "900123456-8", "5000000", "cuenta corriente 987654321"));
    }

    private static Map<String, String> row(String nombre, String tipo, String numero, String monto, String productos) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("nombre", nombre);
        r.put("tipo_identificacion", tipo);
        r.put("numero_identificacion", numero);
        r.put("monto", monto);
        r.put("productos", productos);
        return r;
    }

    private static ObjectNode node(String value, double confidence) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("value", value);
        n.put("confidence", confidence);
        n.put("page", 1);
        n.put("quote", value);
        n.putArray("bbox").add(0.1).add(0.2).add(0.3).add(0.05);
        return n;
    }
}
