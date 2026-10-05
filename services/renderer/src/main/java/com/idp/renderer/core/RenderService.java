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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "render-worker");
        t.setDaemon(true);
        return t;
    });
    private final Counter malwareCounter;
    private final Map<DocType, Counter> pageCounters = new EnumMap<>(DocType.class);

    public RenderService(RendererProperties props, AntivirusScanner scanner, FileTypeValidator validator,
            DocxInspector docxInspector, DocumentConverter converter, PdfProcessor pdfProcessor,
            ImageProcessor imageProcessor, MeterRegistry meters) {
        this.limits = props.limits();
        this.permits = new Semaphore(Math.max(1, limits.maxConcurrent()));
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
                int pages = processBounded(input, type, pkg, deadline);
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

    /** Concurrencia acotada y render en hilo propio con timeout que lo interrumpe (paginas patologicas). */
    private int processBounded(Path input, DocType type, ResultPackage pkg, Deadline deadline)
            throws IOException {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RenderException.internal("Procesamiento interrumpido.");
        }
        if (!acquired) {
            throw RenderException.busy();
        }
        Future<Integer> task;
        try {
            task = executor.submit(() -> {
                try {
                    return process(input, type, pkg, deadline);
                } finally {
                    permits.release();
                }
            });
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
        try {
            return task.get(limits.renderTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw RenderException.limitExceeded("Se excedio el tiempo maximo de procesamiento.");
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw RenderException.internal("Procesamiento interrumpido.");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            if (c instanceof IOException io) {
                throw io;
            }
            throw RenderException.internal("Error durante el procesamiento.");
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
