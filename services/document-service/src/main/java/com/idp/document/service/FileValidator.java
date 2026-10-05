package com.idp.document.service;

import com.idp.document.service.Exceptions.FileTooLargeException;
import com.idp.document.service.Exceptions.InvalidFileFormatException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/** Validacion fail-fast (SEC-023): tamano maximo y magic bytes de PDF, DOCX, PNG, JPG y TIFF. */
@Component
public class FileValidator {

    /** Devuelve el mime type detectado por la cabecera; lanza si el tamano o la cabecera no son validos. */
    public String validate(byte[] content, long maxBytes) {
        if (content == null || content.length == 0) {
            throw new InvalidFileFormatException("Archivo vacio");
        }
        if (content.length > maxBytes) {
            throw new FileTooLargeException("El archivo excede el limite de " + maxBytes + " bytes");
        }
        if (startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if (startsWith(content, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "image/png";
        }
        if (startsWith(content, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(content, new byte[] {'I', 'I', 0x2A, 0x00}) || startsWith(content, new byte[] {'M', 'M', 0x00, 0x2A})) {
            return "image/tiff";
        }
        if (isDocx(content)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        throw new InvalidFileFormatException("El formato o la firma de cabecera del archivo no es permitido");
    }

    /** ZIP con la primera entrada propia de un paquete OOXML; sin descomprimir nada (anti zip-bomb). */
    private static boolean isDocx(byte[] c) {
        if (c.length < 31 || !startsWith(c, new byte[] {'P', 'K', 0x03, 0x04})) {
            return false;
        }
        int nameLen = (c[26] & 0xFF) | ((c[27] & 0xFF) << 8);
        if (nameLen <= 0 || 30 + nameLen > c.length) {
            return false;
        }
        String name = new String(c, 30, nameLen, StandardCharsets.US_ASCII);
        return name.equals("[Content_Types].xml") || name.startsWith("word/") || name.startsWith("_rels/")
                || name.startsWith("docProps/");
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
