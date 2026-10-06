package com.idp.synthetic;

import java.util.Locale;

/** Cantidades en letras en espanol (hasta 999.999.999.999) para el monto en letras del oficio. */
public final class NumberWords {

    private static final String[] UNIDADES = {"cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete",
        "ocho", "nueve", "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete",
        "dieciocho", "diecinueve", "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro",
        "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve"};
    private static final String[] DECENAS = {"", "", "", "treinta", "cuarenta", "cincuenta", "sesenta",
        "setenta", "ochenta", "noventa"};
    private static final String[] CENTENAS = {"", "ciento", "doscientos", "trescientos", "cuatrocientos",
        "quinientos", "seiscientos", "setecientos", "ochocientos", "novecientos"};
    private static final long MAX = 999_999_999_999L;

    private NumberWords() {
    }

    /** Texto en mayusculas listo para el oficio, p. ej. "VEINTIDOS MILLONES CIENTO CINCUENTA MIL PESOS M/CTE". */
    public static String pesos(long n) {
        String sufijo = n >= 1_000_000 && n % 1_000_000 == 0 ? " DE PESOS M/CTE" : " PESOS M/CTE";
        return palabras(n).toUpperCase(Locale.ROOT) + sufijo;
    }

    /** Palabras en minuscula, sin moneda. */
    public static String palabras(long n) {
        if (n < 0 || n > MAX) {
            throw new IllegalArgumentException("Fuera de rango: " + n);
        }
        return n == 0 ? "cero" : sobre(n, false);
    }

    private static String sobre(long n, boolean apocope) {
        if (n >= 1_000_000) {
            long millones = n / 1_000_000;
            long resto = n % 1_000_000;
            String cabeza = millones == 1 ? "un millón" : sobre(millones, true) + " millones";
            return resto == 0 ? cabeza : cabeza + " " + sobre(resto, apocope);
        }
        if (n >= 1000) {
            long miles = n / 1000;
            long resto = n % 1000;
            String cabeza = miles == 1 ? "mil" : sobre(miles, true) + " mil";
            return resto == 0 ? cabeza : cabeza + " " + sobre(resto, apocope);
        }
        return menorMil((int) n, apocope);
    }

    private static String menorMil(int valor, boolean apocope) {
        if (valor == 100) {
            return "cien";
        }
        int n = valor;
        StringBuilder sb = new StringBuilder();
        if (n > 100) {
            sb.append(CENTENAS[n / 100]);
            n %= 100;
            if (n > 0) {
                sb.append(' ');
            }
        }
        if (n == 0) {
            return sb.toString();
        }
        if (n < 30) {
            String u = UNIDADES[n];
            if (apocope && n == 1) {
                u = "un";
            } else if (apocope && n == 21) {
                u = "veintiún";
            }
            return sb.append(u).toString();
        }
        sb.append(DECENAS[n / 10]);
        int u = n % 10;
        if (u > 0) {
            sb.append(" y ").append(apocope && u == 1 ? "un" : UNIDADES[u]);
        }
        return sb.toString();
    }
}
