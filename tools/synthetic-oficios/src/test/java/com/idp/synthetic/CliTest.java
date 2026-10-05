package com.idp.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static int correr(PrintStream err, String... args) {
        return SyntheticCli.ejecutar(args, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            err);
    }

    @Test
    void generaNOficiosPorTipologiaConSusArtefactos(@TempDir Path tmp) throws IOException {
        int rc = correr(System.err, "--salida", tmp.toString(), "--semilla", "5", "--cantidad", "2",
            "--tipologias", "EC,DJ", "--modo", "ambos", "--few-shot");
        assertThat(rc).isZero();
        for (String id : new String[] {"ec-01", "ec-02", "dj-01", "dj-02"}) {
            assertThat(tmp.resolve(id).resolve("oficio.pdf")).isNotEmptyFile();
            assertThat(tmp.resolve(id).resolve("oficio-escaneado.pdf")).isNotEmptyFile();
            assertThat(tmp.resolve(id).resolve("verdad.json")).isNotEmptyFile();
            assertThat(tmp.resolve(id).resolve("texto.txt")).isNotEmptyFile();
            assertThat(tmp.resolve(id + ".txt")).content().contains("SALIDA ESPERADA");
        }
        assertThat(tmp.resolve("ej-01")).doesNotExist();
        assertThat(tmp.resolve("manifest.json")).content().contains("\"semilla\": 5");
    }

    @Test
    void modoNativoNoGeneraEscaneadoYEscaneadoNoGeneraNativo(@TempDir Path tmp) {
        assertThat(correr(System.err, "--salida", tmp.resolve("n").toString(), "--cantidad", "1", "--tipologias",
            "EJ", "--modo", "nativo")).isZero();
        assertThat(tmp.resolve("n/ej-01/oficio.pdf")).exists();
        assertThat(tmp.resolve("n/ej-01/oficio-escaneado.pdf")).doesNotExist();
        assertThat(correr(System.err, "--salida", tmp.resolve("e").toString(), "--cantidad", "1", "--tipologias",
            "EJ", "--modo", "escaneado")).isZero();
        assertThat(tmp.resolve("e/ej-01/oficio.pdf")).doesNotExist();
        assertThat(tmp.resolve("e/ej-01/oficio-escaneado.pdf")).exists();
    }

    @Test
    void reproduciblePorSemillaEnLosArtefactosDeterministas(@TempDir Path tmp) throws IOException {
        String[] base = {"--semilla", "99", "--cantidad", "2", "--tipologias", "EC,EJ", "--modo", "ambos"};
        for (String sub : new String[] {"a", "b"}) {
            String[] args = new String[base.length + 2];
            System.arraycopy(base, 0, args, 2, base.length);
            args[0] = "--salida";
            args[1] = tmp.resolve(sub).toString();
            assertThat(correr(System.err, args)).isZero();
        }
        assertThat(Files.readAllBytes(tmp.resolve("a/manifest.json"))).isEqualTo(Files.readAllBytes(tmp.resolve("b/manifest.json")));
        for (String f : new String[] {"ec-01/oficio.pdf", "ec-02/verdad.json", "ej-02/oficio-escaneado.pdf"}) {
            assertThat(Files.readAllBytes(tmp.resolve("a").resolve(f))).isEqualTo(Files.readAllBytes(tmp.resolve("b").resolve(f)));
        }
    }

    @Test
    void argumentosInvalidosDevuelven2(@TempDir Path tmp) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(err, true, StandardCharsets.UTF_8);
        assertThat(correr(ps)).isEqualTo(2);
        assertThat(correr(ps, "--salida", tmp.toString(), "--tipologias", "XX")).isEqualTo(2);
        assertThat(correr(ps, "--salida", tmp.toString(), "--cantidad", "0")).isEqualTo(2);
        assertThat(correr(ps, "--bogus", "1")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Uso:");
    }
}
