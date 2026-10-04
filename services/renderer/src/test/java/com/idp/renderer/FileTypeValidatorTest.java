package com.idp.renderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.renderer.core.RenderException;
import com.idp.renderer.security.DocType;
import com.idp.renderer.security.FileTypeValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTypeValidatorTest {

    @TempDir
    Path dir;

    private final FileTypeValidator validator = new FileTypeValidator();

    private Path file(byte[] content) throws IOException {
        return Files.write(dir.resolve("f.pdf"), content);
    }

    @Test
    void ac07_detectaFormatosPermitidosPorMagicBytes() throws Exception {
        assertThat(validator.detect(file(TestDocs.pdf(1, "a")))).isEqualTo(DocType.PDF);
        assertThat(validator.detect(file(TestDocs.image("png")))).isEqualTo(DocType.PNG);
        assertThat(validator.detect(file(TestDocs.image("jpeg")))).isEqualTo(DocType.JPEG);
        assertThat(validator.detect(file(TestDocs.image("tiff")))).isEqualTo(DocType.TIFF);
        assertThat(validator.detect(file(TestDocs.docx()))).isEqualTo(DocType.DOCX);
    }

    @Test
    void ac07_rechazaContenidoNoPermitidoIndependienteDeLaExtension() {
        for (byte[] content : new byte[][] {
                {0x7F, 'E', 'L', 'F', 2, 1, 1, 0}, {'M', 'Z', 0, 0}, "hola mundo".getBytes(), {}}) {
            assertThatThrownBy(() -> validator.detect(file(content)))
                    .isInstanceOfSatisfying(RenderException.class, e -> {
                        assertThat(e.status()).isEqualTo(400);
                        assertThat(e.code()).isEqualTo("ERR_UNSUPPORTED_FORMAT");
                    });
        }
    }
}
