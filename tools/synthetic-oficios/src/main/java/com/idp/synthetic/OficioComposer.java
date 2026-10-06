package com.idp.synthetic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Compone el contenido textual del oficio (independiente del formato de salida). */
final class OficioComposer {

    sealed interface Bloque permits Parrafo, Tabla, Espacio {
    }

    record Parrafo(String texto, boolean negrita, float tam, boolean centrado) implements Bloque {
    }

    record Tabla(List<String[]> filas, float[] anchos) implements Bloque {
    }

    record Espacio(float alto) implements Bloque {
    }

    private static final float[] ANCHOS = {150, 38, 92, 85, 118};

    private OficioComposer() {
    }

    static List<Bloque> componer(OficioData o) {
        List<Bloque> b = new ArrayList<>();
        Tipo tipo = o.tipo();
        String autoridad = o.campo("autoridad_emisora");
        if (autoridad != null) {
            b.add(new Parrafo(autoridad.toUpperCase(java.util.Locale.ROOT), true, 12, true));
        }
        b.add(new Espacio(10));

        String ciudad = o.campo("ciudad");
        String fecha = o.campo("fecha_oficio") == null ? null : Datos.fechaLarga(LocalDate.parse(o.campo("fecha_oficio")));
        if (ciudad != null || fecha != null) {
            b.add(new Parrafo(ciudad == null ? fecha : fecha == null ? ciudad : ciudad + ", " + fecha, false, 10.5f, false));
        }
        b.add(new Parrafo("Oficio No. " + o.numeroOficio(), false, 10.5f, false));
        b.add(new Espacio(8));

        b.add(new Parrafo("Señores", false, 10.5f, false));
        b.add(new Parrafo(o.campo("banco_destinatario") != null ? o.campo("banco_destinatario")
            : "Gerente de la entidad financiera", true, 10.5f, false));
        b.add(new Espacio(8));

        b.add(new Parrafo("Referencia: " + o.campo("tipo_medida"), true, 10.5f, false));
        agregar(b, "Radicado: ", o.campo("radicado"));
        if (tipo.coactivo()) {
            agregar(b, "Resolución No.: ", o.campo("numero_resolucion"));
        } else {
            agregar(b, "Clase de proceso: ", o.campo("clase_proceso"));
            agregar(b, "Demandante: ", o.campo("demandante"));
        }
        if (!tipo.embargo()) {
            agregar(b, "Oficio de embargo original: ", o.campo("referencia_oficio_original"));
        }
        b.add(new Espacio(8));

        b.add(new Parrafo(cuerpo(o), false, 10.5f, false));
        b.add(new Espacio(6));
        b.add(new Parrafo("Relación de demandados:", true, 10.5f, false));
        b.add(new Espacio(2));
        List<String[]> filas = new ArrayList<>();
        filas.add(new String[] {"Nombre / Razón social", "Tipo ID", "Número ID", "Monto ($)", "Productos"});
        for (Map<String, String> d : o.demandados()) {
            filas.add(new String[] {d.get("nombre"), d.get("tipo_identificacion"), d.get("numero_identificacion"),
                Datos.miles(entero(d.get("monto"))), d.get("productos")});
        }
        b.add(new Tabla(filas, ANCHOS));
        b.add(new Espacio(8));

        String accion = tipo.embargo() ? "a embargar" : "a desembargar";
        StringBuilder total = new StringBuilder("Valor total " + accion + ": ");
        if (o.campo("monto_numeros") != null) {
            total.append("$ ").append(Datos.miles(entero(o.campo("monto_numeros"))));
        }
        if (o.campo("monto_letras") != null) {
            total.append(" (").append(o.campo("monto_letras")).append(")");
        }
        total.append('.');
        b.add(new Parrafo(total.toString(), true, 10.5f, false));
        if (o.campo("limite_inembargabilidad") != null) {
            b.add(new Parrafo("Límite de inembargabilidad: $ "
                + Datos.miles(entero(o.campo("limite_inembargabilidad"))) + ".", false, 10.5f, false));
        }
        if (o.inyeccion() != null) {
            b.add(new Espacio(6));
            b.add(new Parrafo("Observaciones: " + o.inyeccion().texto(), false, 9, false));
        }
        b.add(new Espacio(10));
        b.add(new Parrafo("Sírvase dar cumplimiento a lo ordenado e informar el resultado dentro de los términos de ley.",
            false, 10.5f, false));
        b.add(new Espacio(14));
        b.add(new Parrafo("Atentamente,", false, 10.5f, false));
        b.add(new Espacio(22));
        b.add(new Parrafo("______________________________", false, 10.5f, false));
        b.add(new Parrafo(tipo.coactivo() ? "Jefe de la Oficina de Cobro Coactivo" : "Secretaría del despacho",
            false, 10.5f, false));
        return b;
    }

    private static void agregar(List<Bloque> b, String etiqueta, String valor) {
        if (valor != null) {
            b.add(new Parrafo(etiqueta + valor, false, 10.5f, false));
        }
    }

    private static String cuerpo(OficioData o) {
        Tipo tipo = o.tipo();
        String sujeto = tipo.coactivo()
            ? "La " + (o.campo("entidad_coactiva") != null ? o.campo("entidad_coactiva") : "entidad ejecutora")
                + ", en ejercicio de la facultad de cobro coactivo,"
            : "El " + (o.campo("juzgado") != null ? o.campo("juzgado") : "despacho judicial")
                + ", dentro del proceso de la referencia,";
        String productos = o.campo("productos_o_cuentas") != null
            ? ", en los siguientes productos: " + o.campo("productos_o_cuentas") : "";
        if (tipo.embargo()) {
            return sujeto + " ha decretado el embargo y la retención de los dineros depositados o que se llegaren a "
                + "depositar a cualquier título en esa entidad" + productos + ", de propiedad de las personas "
                + "relacionadas a continuación, hasta por las sumas indicadas.";
        }
        return sujeto + " ha ordenado el levantamiento de la medida de embargo comunicada previamente. En consecuencia, "
            + "sírvase desembargar los dineros retenidos" + productos + ", de propiedad de las personas relacionadas "
            + "a continuación, por las sumas indicadas.";
    }

    private static long entero(String decimal) {
        return new BigDecimal(decimal).longValueExact();
    }
}
