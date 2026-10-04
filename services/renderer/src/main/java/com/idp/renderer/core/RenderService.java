package com.idp.renderer.core;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.convert.DocumentConverter;
import com.idp.renderer.security.AntivirusScanner;
import com.idp.renderer.security.DocType;
import com.idp.renderer.security.DocxInspector;
import com.idp.renderer.security.FileTypeValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orquesta el pipeline: tamano, antivirus, magic bytes, inspeccion DOCX, conversion, rasterizado. Falla
 * rapido y sin resultados parciales.
 */
@Service
public class RenderService {

    private static final Logger LOG = LoggerFactory.getLogger(RenderService.class);

    private final RendererProperties.Limits limits;
    private final AntivirusScanner scanner;
    private final FileTypeValidator validator;
    private final DocxInspector docxInspector;
    private final DocumentConverter converter;
    private final PdfProcessor pdfProcessor;
    private final ImageProcessor imageProcessor;
    private final Counter malwareCounter;
    private final Map<DocType, Counter> pageCounters = new EnumMap<>(DocType.class);

    public RenderService(RendererProperties props, AntivirusScanner scanner, FileTypeValidator validator,
            DocxInspector docxInspector, DocumentConverter converter, PdfProcessor pdfProcessor,
            ImageProcessor imageProcessor, MeterRegistry meters) {
        this.limits = props.limits();
        this.scanner = scanner;
        this.validator = validator;
        this.docxInspector = docxInspector;
        this.converter = converter;
        this.pdfProcessor = pdfProcessor;
        this.imageProcessor = imageProcessor;
        this.malwareCounter = meters.counter("renderer.scan.malware_detected");
        for (DocType t : DocType.values()) {
            pageCounters.put(t, meters.counter("renderer.pages.processed", "format", t.format()));
        }
    }

    /** El llamador debe cerrar el ResultPackage devuelto. */
    public ResultPackage render(Path input) {
        long started = System.nanoTime();
        try {
            long size = Files.size(input);
            if (size == 0) {
                throw RenderException.invalid("Archivo vacio.");
            }
            if (size > limits.maxFileBytes()) {
                throw RenderException.limitExceeded("El archivo excede el tamano maximo.");
            }
            AntivirusScanner.ScanResult scan = scanner.scan(input);
            if (!scan.clean()) {
                malwareCounter.increment();
                LOG.warn("Malware detectado signature={}", scan.signature());
                throw RenderException.malware();
            }
            DocType type = validator.detect(input);
            Deadline deadline = new Deadline(limits.renderTimeout());
            ResultPackage pkg = new ResultPackage();
            try {
                int pages = process(input, type, pkg, deadline);
                pkg.finish();
                pageCounters.get(type).increment(pages);
                LOG.info("Render ok format={} pages={} ms={}", type.format(), pages,
                        (System.nanoTime() - started) / 1_000_000);
                return pkg;
            } catch (IOException | RuntimeException e) {
                pkg.close();
                throw e;
            }
        } catch (IOException e) {
            throw RenderException.internal("Error de E/S durante el procesamiento.");
        }
    }

    private int process(Path input, DocType type, ResultPackage pkg, Deadline deadline) throws IOException {
        if (type.isImage()) {
            return imageProcessor.process(input, type, pkg, deadline);
        }
        if (type == DocType.PDF) {
            return pdfProcessor.process(input, pkg, deadline);
        }
        docxInspector.inspect(input);
        Path work = Files.createTempDirectory("render-work-");
        try {
            Path pdf = converter.convertToPdf(input, work);
            return pdfProcessor.process(pdf, pkg, deadline);
        } finally {
            deleteTree(work);
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // limpieza best effort; el emptyDir del pod es efimero
        }
    }
}
