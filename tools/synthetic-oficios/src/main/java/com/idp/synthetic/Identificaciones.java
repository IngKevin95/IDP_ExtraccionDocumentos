package com.idp.synthetic;

import java.util.Random;

/** Identificaciones con formato valido pero en rangos reservados (no asignables a personas ni empresas reales): NIT con digito de verificacion modulo 11 y cedulas. */
public final class Identificaciones {

    private static final int[] PESOS = {3, 7, 13, 17, 19, 23, 29, 37, 41, 43, 47, 53, 59, 67, 71};

    private Identificaciones() {
    }

    /** Digito de verificacion DIAN (modulo 11) de la base numerica. */
    public static int digitoVerificacion(String base) {
        int suma = 0;
        for (int i = 0; i < base.length(); i++) {
            suma += (base.charAt(base.length() - 1 - i) - '0') * PESOS[i];
        }
        int residuo = suma % 11;
        return residuo > 1 ? 11 - residuo : residuo;
    }

    /**
     * NIT ficticio NNNNNNNNN-D con digito de verificacion valido. Base de 9 digitos en el rango reservado
     * 999000000 a 999999999: la DIAN asigna a la fecha bases que no llegan a ese prefijo, de modo que el valor
     * supera los validadores de formato sin corresponder a una empresa real.
     */
    public static String nit(Random rng) {
        String base = Long.toString(999_000_000L + rng.nextInt(1_000_000));
        return base + "-" + digitoVerificacion(base);
    }

    /**
     * Cedula ficticia de 10 digitos en el rango reservado 9900000000 a 9999999999: la Registraduria emite cedulas
     * de 10 digitos que empiezan por 1 (hasta alrededor de 1.2 mil millones), por lo que ningun numero del rango
     * pertenece a una persona real. Sin digito de verificacion.
     */
    public static String cedula(Random rng) {
        return Long.toString(9_900_000_000L + (long) rng.nextInt(100_000_000));
    }
}
