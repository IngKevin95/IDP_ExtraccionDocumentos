package com.idp.chat.infra;

/** Literal de texto de pgvector ({@code [0.1,0.2]}); se envia como parametro y se castea con {@code cast(? as vector)}. */
final class Vectors {

    private Vectors() {
    }

    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (!Float.isFinite(v[i])) {
                throw new IllegalArgumentException("Embedding con valores no finitos");
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(v[i]));
        }
        return sb.append(']').toString();
    }
}
