package com.idp.synthetic;

import java.util.Random;

/** Identificaciones con formato valido pero ficticias: NIT con digito de verificacion modulo 11 y cedulas. */
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

    /** NIT ficticio NNNNNNNNN-D (base de 9 digitos que inicia en 8 o 9). */
    public static String nit(Random rng) {
        String base = Long.toString(800_000_000L + rng.nextInt(199_999_999));
        return base + "-" + digitoVerificacion(base);
    }

    /** Cedula ficticia de 8 o 10 digitos, sin digito de verificacion. */
    public static String cedula(Random rng) {
        if (rng.nextInt(10) < 7) {
            return Long.toString(10_000_000L + rng.nextInt(90_000_000));
        }
        return Long.toString(1_000_000_000L + rng.nextInt(200_000_000));
    }
}
