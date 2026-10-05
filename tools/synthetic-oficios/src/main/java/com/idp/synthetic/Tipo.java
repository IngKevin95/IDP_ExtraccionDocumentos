package com.idp.synthetic;

import java.util.Locale;

/** Tipologias de oficio soportadas (contracts/tipologias/embargos.v1.yaml). */
public enum Tipo {
    EC(true, true, "Embargo coactivo"),
    EJ(false, true, "Embargo judicial"),
    DC(true, false, "Desembargo coactivo"),
    DJ(false, false, "Desembargo judicial");

    private final boolean coactivo;
    private final boolean embargo;
    private final String medida;

    Tipo(boolean coactivo, boolean embargo, String medida) {
        this.coactivo = coactivo;
        this.embargo = embargo;
        this.medida = medida;
    }

    public boolean coactivo() {
        return coactivo;
    }

    public boolean embargo() {
        return embargo;
    }

    /** Valor del campo tipo_medida. */
    public String medida() {
        return medida;
    }

    /** Id de la tipologia en el YAML, p. ej. embargos.v1.ec. */
    public String tipologiaId() {
        return "embargos.v1." + prefijo();
    }

    public String prefijo() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Tipo parse(String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
