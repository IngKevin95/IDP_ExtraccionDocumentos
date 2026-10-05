package com.idp.renderer.convert;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.core.RenderException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** LibreOffice headless como proceso aislado: perfil temporal, entorno minimo, timeout y sin salida logueada. */
@Component
public class LibreOfficeConverter implements DocumentConverter {

    private static final Logger LOG = LoggerFactory.getLogger(LibreOfficeConverter.class);
    private static final Set<String> KEEP_ENV = Set.of("PATH", "SystemRoot", "TEMP", "TMP", "LANG", "LC_ALL");

    private final RendererProperties.Libreoffice cfg;

    public LibreOfficeConverter(RendererProperties props) {
        this.cfg = props.libreoffice();
    }

    @Override
    public Path convertToPdf(Path docx, Path workDir) {
        try {
            Path input = workDir.resolve("input.docx");
            Files.copy(docx, input, StandardCopyOption.REPLACE_EXISTING);
            Path outDir = Files.createDirectories(workDir.resolve("out"));
            Path profile = workDir.resolve("profile");
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    cfg.binary(), "--headless", "--norestore", "--nologo", "--nolockcheck", "--nodefault",
                    "--nofirststartwizard", "-env:UserInstallation=" + profile.toUri(),
                    "--convert-to", "pdf:writer_pdf_Export", "--outdir", outDir.toString(), input.toString()));
            Map<String, String> env = pb.environment();
            env.keySet().removeIf(k -> !KEEP_ENV.contains(k));
            env.put("HOME", workDir.toString());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            try {
                if (!p.waitFor(cfg.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                    kill(p);
                    throw RenderException.timeout("Timeout en la conversion del documento.");
                }
            } catch (InterruptedException e) {
                kill(p);
                Thread.currentThread().interrupt();
                throw RenderException.internal("Conversion interrumpida.");
            }
            Path pdf = outDir.resolve("input.pdf");
            if (p.exitValue() != 0 || !Files.isRegularFile(pdf)) {
                LOG.warn("Conversion rechazada exit={}", p.exitValue());
                throw RenderException.conversionRejected("El documento no pudo ser convertido.");
            }
            return pdf;
        } catch (IOException e) {
            LOG.error("No se pudo ejecutar el conversor: {}", e.getClass().getSimpleName());
            throw RenderException.converterUnavailable();
        }
    }

    private static void kill(Process p) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
    }
}
