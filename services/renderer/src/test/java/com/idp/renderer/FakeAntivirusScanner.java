package com.idp.renderer;

import com.idp.renderer.security.AntivirusScanner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Antivirus falso para tests: marca como infectado todo archivo que contenga la cadena EICAR. */
class FakeAntivirusScanner implements AntivirusScanner {

    int calls;

    @Override
    public ScanResult scan(Path file) {
        calls++;
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            return content.contains("EICAR-STANDARD-ANTIVIRUS-TEST-FILE")
                    ? ScanResult.infected("Eicar-Test-Signature")
                    : ScanResult.ok();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
