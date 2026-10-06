package com.idp.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.chat.service.TextChunker.Page;
import com.idp.chat.service.TextChunker.PageChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker(100, 20);

    @Test
    void textoCortoEsUnSoloFragmentoConSuPagina() {
        List<PageChunk> out = chunker.chunk(List.of(new Page(1, "Hola mundo"), new Page(2, "Otra pagina")));

        assertThat(out).containsExactly(new PageChunk(1, "Hola mundo"), new PageChunk(2, "Otra pagina"));
    }

    @Test
    void ningunFragmentoExcedeElMaximoCadaUnoEsSubcadenaYHaySolape() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("palabra").append(i).append(i % 7 == 6 ? ". " : " ");
        }
        String text = sb.toString();

        List<PageChunk> out = chunker.chunk(List.of(new Page(4, text)));

        assertThat(out.size()).isGreaterThan(3);
        assertThat(out).allSatisfy(c -> {
            assertThat(c.content().length()).isLessThanOrEqualTo(100);
            assertThat(text).contains(c.content());
            assertThat(c.pageNumber()).isEqualTo(4);
        });
        // Solape: el inicio de un fragmento ya aparece al final del anterior.
        for (int i = 1; i < out.size(); i++) {
            String head = out.get(i).content().split(" ")[0];
            assertThat(out.get(i - 1).content()).contains(head);
        }
        // Cobertura: todas las palabras aparecen en algun fragmento.
        for (int i = 0; i < 60; i++) {
            String w = "palabra" + i;
            assertThat(out).anySatisfy(c -> assertThat(c.content()).contains(w));
        }
    }

    @Test
    void sinSeparadoresCortaEnElLimiteYAvanza() {
        List<PageChunk> out = chunker.chunk(List.of(new Page(1, "x".repeat(450))));

        assertThat(out).allSatisfy(c -> assertThat(c.content().length()).isLessThanOrEqualTo(100));
        assertThat(out.size()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void descartaPaginasVaciasYNormalizaSaltosDeLinea() {
        assertThat(chunker.chunk(List.of(new Page(1, "  \n "), new Page(2, null)))).isEmpty();
        assertThat(chunker.chunk(List.of(new Page(1, "a\r\nb\0c")))).containsExactly(new PageChunk(1, "a\nbc"));
    }

    @Test
    void parametrosInvalidosSeRechazan() {
        assertThatThrownBy(() -> new TextChunker(100, 60)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(5, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
