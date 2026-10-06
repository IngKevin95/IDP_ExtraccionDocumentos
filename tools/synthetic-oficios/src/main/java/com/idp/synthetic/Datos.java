package com.idp.synthetic;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

/** Catalogos ficticios para componer oficios (sin datos personales reales). */
final class Datos {

    record Ciudad(String nombre, String dane) {
    }

    static final LocalDate FECHA_REFERENCIA = LocalDate.of(2026, 6, 30);

    static final List<Ciudad> CIUDADES = List.of(
        new Ciudad("Bogotá D.C.", "11001"), new Ciudad("Medellín", "05001"),
        new Ciudad("Cali", "76001"), new Ciudad("Barranquilla", "08001"),
        new Ciudad("Bucaramanga", "68001"), new Ciudad("Cartagena", "13001"),
        new Ciudad("Pereira", "66001"), new Ciudad("Manizales", "17001"));

    static final List<String> BANCOS = List.of(
        "Banco Andino Sintético S.A.", "Banco Cafetero Demo S.A.", "Banco del Pacífico Ficticio S.A.",
        "Banca Horizonte de Prueba S.A.", "Banco Altiplano Sintético S.A.");

    /** Nombres inventados (no corresponden a nombres de pila reales), para que nadie los confunda con personas. */
    static final List<String> NOMBRES = List.of(
        "Alderan", "Brisenia", "Cantelo", "Dorvina", "Eldemar", "Fabrizela", "Gundelo", "Heliodora", "Ixtaro",
        "Jorlenia", "Kandelo", "Lunaria", "Mirtelo", "Norvina", "Ovaldo", "Pelagrina", "Quenelo", "Rivalda",
        "Sorvelo", "Tamarisca");

    /** Apellidos inventados; ninguno es un apellido de uso comun. */
    static final List<String> APELLIDOS = List.of(
        "Zandoval", "Mirabeque", "Torcuato", "Valdenavia", "Orquillo", "Bellaterra", "Quintanaro", "Salmedora",
        "Ventureño", "Arcanelo", "Pedregaza", "Lumbrales", "Cordellera", "Vistamar", "Narvalle", "Ibarreta",
        "Fontemar", "Gualdrapa", "Rosalinda", "Tarquino");

    static final List<String> EMPRESA_RAIZ = List.of(
        "Comercializadora", "Inversiones", "Distribuciones", "Constructora", "Agroindustrias", "Transportes",
        "Servicios", "Inmobiliaria");

    /** Todas las razones sociales llevan la palabra Ficticia: no pueden coincidir con una empresa real. */
    static final List<String> EMPRESA_APELLIDO = List.of(
        "Ficticia Andina", "Ficticia del Caribe", "Ficticia Cafetera", "Ficticia Los Álamos",
        "Ficticia Altiplano", "Ficticia Pacífico", "Ficticia Solar", "Ficticia Horizonte");

    static final List<String> EMPRESA_SUFIJO = List.of("S.A.S.", "S.A.", "Ltda.");

    static final List<String> PRODUCTOS = List.of(
        "cuentas corrientes", "cuentas de ahorro", "CDT y depósitos a término",
        "fondos de inversión colectiva", "cuentas de ahorro programado", "productos de depósito en general");

    static final List<String> PRODUCTO_DEMANDADO = List.of(
        "Cuenta de ahorros", "Cuenta corriente", "CDT", "Ahorro programado", "Fondo de inversión");

    static final List<String> CLASES_PROCESO = List.of(
        "Ejecutivo singular", "Ejecutivo con garantía real", "Cobro de pagaré", "Ejecutivo hipotecario",
        "Ejecución de sentencia");

    static final List<String> TIPOS_JUZGADO = List.of(
        "Civil Municipal", "Civil del Circuito", "Promiscuo Municipal", "de Familia", "Laboral del Circuito",
        "Civil Municipal de Ejecución de Sentencias");

    static final List<String> ORDINALES = List.of(
        "Primero", "Segundo", "Tercero", "Cuarto", "Quinto", "Sexto", "Séptimo", "Octavo", "Noveno",
        "Décimo");

    static final List<String> ENTIDADES_COACTIVAS = List.of(
        "Secretaría de Hacienda", "Dirección de Impuestos Municipales", "Tesorería General",
        "Oficina de Rentas");

    static final List<String> MESES = List.of(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre",
        "noviembre", "diciembre");

    private Datos() {
    }

    static <T> T elegir(Random rng, List<T> lista) {
        return lista.get(rng.nextInt(lista.size()));
    }

    static String persona(Random rng) {
        return elegir(rng, NOMBRES) + " " + elegir(rng, APELLIDOS) + " " + elegir(rng, APELLIDOS);
    }

    static String empresa(Random rng) {
        return elegir(rng, EMPRESA_RAIZ) + " " + elegir(rng, EMPRESA_APELLIDO) + " " + elegir(rng, EMPRESA_SUFIJO);
    }

    static String fechaLarga(LocalDate d) {
        return d.getDayOfMonth() + " de " + MESES.get(d.getMonthValue() - 1) + " de " + d.getYear();
    }

    /** Miles con punto, estilo colombiano: 12.345.678. */
    static String miles(long n) {
        String s = Long.toString(n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                sb.append('.');
            }
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }
}
