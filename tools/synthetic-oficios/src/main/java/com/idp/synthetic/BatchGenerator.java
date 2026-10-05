package com.idp.synthetic;

import com.idp.synthetic.OficioGenerator.Plan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Genera N oficios por tipologia a un directorio: un subdirectorio por oficio y un manifest.json. */
public final class BatchGenerator {

    public enum Modo { NATIVO, ESCANEADO, AMBOS }

    public record Opciones(Path salida, long semilla, int cantidad, int desde, List<Tipo> tipos, Modo modo,
                           Plan plan, boolean fewShot) {
    }

    private final OficioGenerator generador = new OficioGenerator();

    /** Genera los oficios y devuelve los ids creados, en orden. */
    public List<String> generar(Opciones op) throws IOException {
        Files.createDirectories(op.salida());
        List<String> ids = new ArrayList<>();
        List<Object> entradas = new ArrayList<>();
        for (Tipo tipo : op.tipos()) {
            for (int i = op.desde(); i < op.desde() + op.cantidad(); i++) {
                OficioData data = generador.generar(tipo, op.semilla(), i, op.plan());
                entradas.add(escribir(op, data));
                ids.add(data.id());
            }
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("generador", "synthetic-oficios/1");
        manifest.put("semilla", op.semilla());
        manifest.put("modo", op.modo().name());
        manifest.put("plan", op.plan().name());
        manifest.put("oficios", entradas);
        Files.write(op.salida().resolve("manifest.json"), GroundTruth.json(manifest));
        return ids;
    }

    /** Escribe todos los artefactos de un oficio y devuelve su entrada de manifest. */
    private Map<String, Object> escribir(Opciones op, OficioData data) throws IOException {
        TipologiaDef def = generador.tipologia(data.tipo());
        Path dir = op.salida().resolve(data.id());
        Files.createDirectories(dir);
        Map<String, Object> sha = new LinkedHashMap<>();
        OficioPdfWriter writer = new OficioPdfWriter();

        byte[] verdad = GroundTruth.json(GroundTruth.verdad(data, def));
        Files.write(dir.resolve("verdad.json"), verdad);
        sha.put("verdad.json", sha256(verdad));

        String texto;
        try (PDDocument nativo = writer.escribir(data)) {
            texto = OficioPdfWriter.texto(nativo);
            if (op.modo() != Modo.ESCANEADO) {
                byte[] pdf = OficioPdfWriter.bytes(nativo);
                Files.write(dir.resolve("oficio.pdf"), pdf);
                Files.write(dir.resolve("texto.txt"), texto.getBytes(StandardCharsets.UTF_8));
                sha.put("oficio.pdf", sha256(pdf));
                sha.put("texto.txt", sha256(texto.getBytes(StandardCharsets.UTF_8)));
            }
        }
        if (op.modo() != Modo.NATIVO) {
            long base = OficioGenerator.mezclar(data.semilla(), data.tipo().ordinal(), data.indice());
            try (PDDocument sellado = new OficioPdfWriter().escribir(data)) {
                writer.decorarEscaneo(sellado, new Random(base + 7919), fechaRecepcion(data));
                try (PDDocument escaneado = ScanSimulator.escanear(sellado, base - 104729)) {
                    Files.write(dir.resolve("oficio-escaneado.pdf"), OficioPdfWriter.bytes(escaneado));
                }
            }
        }
        if (op.fewShot()) {
            Files.write(op.salida().resolve(data.id() + ".txt"), fewShot(data, texto));
        }

        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", data.id());
        e.put("tipologia", def.id());
        e.put("defectos", data.defectos().stream().map(d -> d.tipo().name()).toList());
        e.put("escaneado", op.modo() != Modo.NATIVO);
        e.put("sha256", sha);
        return e;
    }

    private static String fechaRecepcion(OficioData data) {
        String f = data.campo("fecha_oficio");
        return (f == null ? Datos.FECHA_REFERENCIA.minusDays(10) : LocalDate.parse(f).plusDays(3)).toString();
    }

    /** Ejemplo few-shot listo para el prompt: texto del oficio y salida esperada. */
    static byte[] fewShot(OficioData data, String texto) {
        String json = new String(GroundTruth.json(GroundTruth.salidaEsperada(data)), StandardCharsets.UTF_8);
        String s = "### Ejemplo " + data.id() + " (oficio sintético, sin datos personales reales)\n"
            + "--- TEXTO DEL OFICIO ---\n" + texto
            + "--- SALIDA ESPERADA (JSON) ---\n" + json;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
