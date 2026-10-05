package com.idp.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.document.service.Exceptions.FileTooLargeException;
import com.idp.document.service.Exceptions.InvalidFileFormatException;
import com.idp.document.service.FileValidator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FileValidatorTest {

    private static final long MAX = 50L * 1024 * 1024;
    private final FileValidator validator = new FileValidator();

    private static byte[] withPrefix(byte... prefix) {
        byte[] b = new byte[prefix.length + 16];
        System.arraycopy(prefix, 0, b, 0, prefix.length);
        return b;
    }

    @Test
    void ac02_aceptaCabecerasDePdfPngJpegTiffYDocx() {
        assertThat(validator.validate(withPrefix("%PDF-1.7".getBytes(StandardCharsets.US_ASCII)), MAX))
                .isEqualTo("application/pdf");
        assertThat(validator.validate(withPrefix((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47, (byte) 0x0D,
                (byte) 0x0A, (byte) 0x1A, (byte) 0x0A), MAX)).isEqualTo("image/png");
        assertThat(validator.validate(withPrefix((byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0), MAX))
                .isEqualTo("image/jpeg");
        assertThat(validator.validate(withPrefix((byte) 0x49, (byte) 0x49, (byte) 0x2A, (byte) 0x00), MAX))
                .isEqualTo("image/tiff");
        assertThat(validator.validate(withPrefix((byte) 0x4D, (byte) 0x4D, (byte) 0x00, (byte) 0x2A), MAX))
                .isEqualTo("image/tiff");
        assertThat(validator.validate(docx("[Content_Types].xml"), MAX)).contains("wordprocessingml");
    }

    @Test
    void ac02_rechazaTextoZipGenericoVacioYMagicBytesFalsos() {
        assertThatThrownBy(() -> validator.validate("hola".getBytes(StandardCharsets.UTF_8), MAX))
                .isInstanceOf(InvalidFileFormatException.class);
        assertThatThrownBy(() -> validator.validate(docx("script.exe"), MAX))
                .isInstanceOf(InvalidFileFormatException.class);
        assertThatThrownBy(() -> validator.validate(new byte[0], MAX)).isInstanceOf(InvalidFileFormatException.class);
        assertThatThrownBy(() -> validator.validate(null, MAX)).isInstanceOf(InvalidFileFormatException.class);
        assertThatThrownBy(() -> validator.validate(new byte[] {0x50, 0x4B, 3, 4, 0}, MAX))
                .isInstanceOf(InvalidFileFormatException.class);
    }

    @Test
    void ac02_rechazaArchivosQueExcedenElLimite() {
        byte[] big = withPrefix("%PDF-".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> validator.validate(big, 10)).isInstanceOf(FileTooLargeException.class);
    }

    private static byte[] docx(String firstEntry) {
        byte[] name = firstEntry.getBytes(StandardCharsets.US_ASCII);
        byte[] b = new byte[30 + name.length + 8];
        b[0] = 0x50;
        b[1] = 0x4B;
        b[2] = 3;
        b[3] = 4;
        b[26] = (byte) name.length;
        System.arraycopy(name, 0, b, 30, name.length);
        return b;
    }
}
