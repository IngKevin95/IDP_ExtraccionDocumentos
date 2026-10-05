package com.idp.synthetic;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.synthetic.OficioData.Defecto;
import com.idp.synthetic.OficioData.TipoDefecto;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verdad de campo (JSON) de un oficio sintetico: campos, tabla, criticidad, defectos y validaciones esperadas. */
final class GroundTruth {

    static final String ESQUEMA = "oficio-sintetico/1";
    static final String PASS = "PASS";
    static final String FAIL = "FAIL";
    static final String SUMA_TABLA = "suma_tabla";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GroundTruth() {
    }

    static Map<String, Object> verdad(OficioData o, TipologiaDef def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("esquema", ESQUEMA);
        m.put("id", o.id());
        m.put("tipologia", def.id());
        m.put("codigo", o.tipo().name());
        m.put("semilla", o.semilla());
        m.put("indice", o.indice());
        m.put("numero_oficio", o.numeroOficio());
        m.put("campos", o.campos());
        List<String> criticos = new ArrayList<>();
        def.campos().values().forEach(c -> {
            if (c.critico()) {
                criticos.add(c.nombre());
            }
        });
        m.put("campos_criticos", criticos);
        m.put("tablas", Map.of("demandados", o.demandados()));
        m.put("suma_filas_demandados", o.sumaFilas() + ".00");
        List<Map<String, String>> defectos = new ArrayList<>();
        for (Defecto d : o.defectos()) {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("tipo", d.tipo().name());
            e.put("campo", d.campo());
            e.put("detalle", d.detalle());
            defectos.add(e);
        }
        m.put("defectos", defectos);
        Map<String, Object> inyeccion = new LinkedHashMap<>();
        inyeccion.put("presente", o.inyeccion() != null);
        inyeccion.put("regla_esperada", o.inyeccion() == null ? null : o.inyeccion().regla());
        m.put("inyeccion_prompt", inyeccion);
        m.put("validadores_esperados", validadoresEsperados(o, def));
        return m;
    }

    /**
     * Formato importable por quality-service (GoldenSetLoader): {@code {id, tipologia, sintetico:true, tags, verdad}}.
     * La verdad es plana (campo a texto, nombres ^[a-z][a-z0-9_]{0,63}$): campos del documento sin los ausentes
     * (null) y las celdas de la tabla como {@code demandados_<fila 1-based>_<columna>}.
     */
    static Map<String, Object> golden(OficioData o, TipologiaDef def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("tipologia", o.tipo().name());
        m.put("sintetico", true);
        m.put("tags", o.defectos().stream().map(d -> d.tipo().name().toLowerCase(java.util.Locale.ROOT)).distinct()
            .toList());
        Map<String, String> verdad = new LinkedHashMap<>();
        o.campos().forEach((k, v) -> {
            if (v != null) {
                verdad.put(k, v);
            }
        });
        for (int i = 0; i < o.demandados().size(); i++) {
            final int fila = i + 1;
            o.demandados().get(i).forEach((k, v) -> {
                if (v != null) {
                    verdad.put("demandados_" + fila + "_" + k, v);
                }
            });
        }
        m.put("verdad", verdad);
        return m;
    }

    /** Resultado esperado de cada validador de dominio sobre los valores del documento (agregado por validador). */
    static Map<String, String> validadoresEsperados(OficioData o, TipologiaDef def) {
        Map<String, String> r = new LinkedHashMap<>();
        for (TipologiaDef.Campo c : def.campos().values()) {
            if (c.validador() == null) {
                continue;
            }
            boolean ok = "monto_numeros_letras".equals(c.validador())
                ? !o.tieneDefecto(TipoDefecto.MONTO_INCONSISTENTE)
                : o.campo(c.nombre()) != null;
            r.putIfAbsent(c.validador(), ok ? PASS : FAIL);
        }
        for (TipologiaDef.Campo c : def.columnasDemandados().values()) {
            if (c.validador() != null) {
                r.putIfAbsent(c.validador(), PASS);
            }
        }
        r.put(SUMA_TABLA, o.tieneDefecto(TipoDefecto.TABLA_NO_SUMA) ? FAIL : PASS);
        return r;
    }

    /** Solo lo que debe extraerse (campos y tablas): la salida esperada de un ejemplo few-shot. */
    static Map<String, Object> salidaEsperada(OficioData o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("campos", o.campos());
        m.put("tablas", Map.of("demandados", o.demandados()));
        return m;
    }

    /** JSON con indentacion de 2 espacios y LF (mismos bytes en cualquier SO), terminado en salto de linea. */
    static byte[] json(Object value) {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter()
            .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER));
        pp.indentObjectsWith(indenter);
        pp.indentArraysWith(indenter);
        try {
            return (MAPPER.writer(pp).writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
