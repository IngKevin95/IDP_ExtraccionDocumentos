package com.idp.synthetic;

import java.util.List;
import java.util.Map;

/**
 * Oficio sintetico generado: valores tal como los dice el documento (verdad de campo). Un campo ausente
 * del documento (o no aplicable) vale null.
 */
public record OficioData(
    String id,
    Tipo tipo,
    long semilla,
    int indice,
    String numeroOficio,
    Map<String, String> campos,
    List<Map<String, String>> demandados,
    List<Defecto> defectos,
    Inyeccion inyeccion,
    long sumaFilas) {

    /** Defecto deliberadamente inyectado en el oficio. */
    public record Defecto(TipoDefecto tipo, String campo, String detalle) {
    }

    public enum TipoDefecto { CAMPO_FALTANTE, MONTO_INCONSISTENTE, TABLA_NO_SUMA, INJECTION_PROMPT }

    /** Parrafo de prompt injection embebido en el texto; regla es el id esperado de PromptInjectionDetector. */
    public record Inyeccion(String texto, String regla) {
    }

    public boolean tieneDefecto(TipoDefecto t) {
        return defectos.stream().anyMatch(d -> d.tipo() == t);
    }

    public String campo(String nombre) {
        return campos.get(nombre);
    }
}
