package com.idp.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.quality.golden.GoldenSetLoader;
import com.idp.quality.golden.GoldenSetLoader.Loaded;
import com.idp.synthetic.BatchGenerator.Modo;
import com.idp.synthetic.BatchGenerator.Opciones;
import com.idp.synthetic.OficioGenerator.Plan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** --formato-golden produce archivos que importa el GoldenSetLoader de quality-service, sin tocar verdad.json. */
class GoldenFormatoQualityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void elLoteExportadoSeImportaConElLoaderDeQualityService(@TempDir Path salida) throws IOException {
        new BatchGenerator().generar(new Opciones(salida, 99L, 2, 1, List.of(Tipo.values()), Modo.NATIVO,
            Plan.CICLICO, false, true));

        List<Loaded> cargados = new GoldenSetLoader(JSON).loadDirectory(salida.resolve("golden"));

        assertThat(cargados).hasSize(8);
        assertThat(cargados).extracting(Loaded::tipologia).containsOnly("EC", "EJ", "DC", "DJ");
        for (Loaded l : cargados) {
            assertThat(l.verdad()).containsKey("radicado").doesNotContainValue(null);
            assertThat(l.verdad().keySet()).anyMatch(k -> k.startsWith("demandados_1_"));
            // La verdad exportada coincide con la de verdad.json.
            JsonNode campos = JSON.readTree(salida.resolve(l.externalId()).resolve("verdad.json").toFile())
                .get("campos");
            assertThat(l.verdad().get("radicado")).isEqualTo(campos.get("radicado").asText());
        }
    }

    @Test
    void sinLaOpcionNoSeEscribeGoldenYVerdadJsonConservaSuFormato(@TempDir Path salida) throws IOException {
        new BatchGenerator().generar(new Opciones(salida, 99L, 1, 1, List.of(Tipo.EC), Modo.NATIVO, Plan.CICLICO,
            false));

        assertThat(salida.resolve("golden")).doesNotExist();
        JsonNode verdad = JSON.readTree(Files.readAllBytes(salida.resolve("ec-01").resolve("verdad.json")));
        assertThat(verdad.get("esquema").asText()).isEqualTo("oficio-sintetico/1");
        assertThat(verdad.has("sintetico")).isFalse();
    }

    @Test
    void laOpcionDelCliSeReconoce() {
        Opciones op = SyntheticCli.parsear(new String[] {"--salida", "x", "--formato-golden"});
        assertThat(op.formatoGolden()).isTrue();
        assertThat(SyntheticCli.parsear(new String[] {"--salida", "x"}).formatoGolden()).isFalse();
    }
}
