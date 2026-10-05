package com.idp.extraction.reliability;

/**
 * Calibracion de la senal conjunta de un campo (ADR-0012): las probabilidades crudas del LLM nunca
 * se usan sin calibrar. La calibracion es especifica del modelo y del tipo de campo.
 */
public interface Calibrator {

    /** Identificador de la calibracion, registrado junto con la extraccion (SEC-049). */
    String id();

    /**
     * @param fieldType tipo del campo (string, decimal, date)
     * @param raw       senal combinada en [0, 1]
     * @return probabilidad calibrada en [0, 1]
     */
    double calibrate(String fieldType, double raw);
}
