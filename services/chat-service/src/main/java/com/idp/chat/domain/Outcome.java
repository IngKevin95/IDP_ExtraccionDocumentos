package com.idp.chat.domain;

/** Resultado de una respuesta del asistente. Solo ANSWERED es elegible para la cache semantica. */
public enum Outcome {
    /** Respuesta con citas verificadas. */
    ANSWERED,
    /** Informacion insuficiente: sin fragmentos relevantes o el modelo se abstuvo. */
    ABSTAINED,
    /** Respuesta del modelo descartada por no superar la verificacion de grounding. */
    BLOCKED,
    /** Servida desde la cache semantica. */
    CACHED
}
