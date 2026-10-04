package com.idp.renderer.convert;

import java.nio.file.Path;

public interface DocumentConverter {

    /** Convierte un DOCX a PDF dentro de workDir y devuelve la ruta del PDF. */
    Path convertToPdf(Path docx, Path workDir);
}
