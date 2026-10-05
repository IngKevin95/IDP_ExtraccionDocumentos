package com.idp.extraction.store;

import java.util.Arrays;

/** Pagina renderizada: PNG y capa de texto nativa (nula si el PDF no tiene texto seleccionable). */
public record PageContent(int number, byte[] png, String nativeText) {

    public PageContent {
        png = png == null ? new byte[0] : png.clone();
    }

    @Override
    public byte[] png() {
        return png.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PageContent p && p.number == number && Arrays.equals(p.png, png)
            && java.util.Objects.equals(p.nativeText, nativeText);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * number + Arrays.hashCode(png)) + java.util.Objects.hashCode(nativeText);
    }

    @Override
    public String toString() {
        return "PageContent[number=" + number + ", pngBytes=" + png.length + "]";
    }
}
