package com.idp.renderer.core;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import tools.jackson.databind.json.JsonMapper;

/**
 * ZIP de resultado escrito a un temporal pagina a pagina (memoria acotada). Solo se entrega si todo el
 * procesamiento termino bien: nunca hay resultados parciales. Se borra al cerrar.
 */
public final class ResultPackage implements Closeable {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record PageText(int page, String text) {}

    private final Path file;
    private final ZipOutputStream zip;
    private final List<PageText> texts = new ArrayList<>();
    private boolean finished;

    public ResultPackage() throws IOException {
        this.file = Files.createTempFile("render-out-", ".zip");
        this.zip = new ZipOutputStream(Files.newOutputStream(file));
    }

    public int pages() {
        return texts.size();
    }

    public void addPage(BufferedImage image, String nativeText) throws IOException {
        int n = texts.size() + 1;
        zip.putNextEntry(new ZipEntry("page_" + n + ".png"));
        if (!ImageIO.write(image, "png", zip)) {
            throw RenderException.internal("No se pudo codificar PNG.");
        }
        zip.closeEntry();
        texts.add(new PageText(n, nativeText == null ? "" : nativeText));
    }

    public void finish() throws IOException {
        zip.putNextEntry(new ZipEntry("text_layer.json"));
        zip.write(JSON.writeValueAsBytes(java.util.Map.of("pages", List.copyOf(texts))));
        zip.closeEntry();
        zip.close();
        finished = true;
    }

    public long size() throws IOException {
        return Files.size(file);
    }

    public void copyTo(OutputStream out) throws IOException {
        if (!finished) {
            throw new IllegalStateException("Paquete sin finalizar");
        }
        Files.copy(file, out);
    }

    @Override
    public void close() throws IOException {
        try {
            zip.close();
        } catch (IOException ignored) {
            // temporal descartado de todos modos
        }
        Files.deleteIfExists(file);
    }
}
