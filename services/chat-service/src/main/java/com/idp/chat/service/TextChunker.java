package com.idp.chat.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Divide la capa de texto en fragmentos de a lo mas {@code maxChars} con solape. Cada fragmento es una subcadena
 * literal del texto de su pagina (las citas se validan contra ese contenido). Prefiere cortar en salto de parrafo,
 * de linea, fin de oracion o espacio, en ese orden, dentro de la segunda mitad de la ventana.
 */
public final class TextChunker {

    public record Page(int number, String text) {
    }

    public record PageChunk(int pageNumber, String content) {
    }

    private final int maxChars;
    private final int overlap;

    public TextChunker(int maxChars, int overlap) {
        if (maxChars < 10 || overlap < 0 || overlap >= maxChars / 2) {
            throw new IllegalArgumentException("Parametros de chunking invalidos");
        }
        this.maxChars = maxChars;
        this.overlap = overlap;
    }

    public List<PageChunk> chunk(List<Page> pages) {
        List<PageChunk> out = new ArrayList<>();
        for (Page p : pages) {
            String text = p.text() == null ? "" : p.text().replace("\r\n", "\n").replace('\r', '\n').replace("\0", "");
            split(p.number(), text, out);
        }
        return out;
    }

    private void split(int page, String text, List<PageChunk> out) {
        int len = text.length();
        int start = 0;
        while (start < len) {
            int end = Math.min(start + maxChars, len);
            if (end < len) {
                end = cutPoint(text, start, end);
            }
            String piece = text.substring(start, end).strip();
            if (!piece.isEmpty()) {
                out.add(new PageChunk(page, piece));
            }
            if (end >= len) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
    }

    private int cutPoint(String text, int start, int end) {
        int floor = start + maxChars / 2;
        for (String sep : new String[] {"\n\n", "\n", ". ", " "}) {
            int i = text.lastIndexOf(sep, end - sep.length());
            if (i >= floor) {
                return i + sep.length();
            }
        }
        return end;
    }
}
