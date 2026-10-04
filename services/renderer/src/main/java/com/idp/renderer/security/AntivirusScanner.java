package com.idp.renderer.security;

import java.nio.file.Path;

public interface AntivirusScanner {

    record ScanResult(boolean clean, String signature) {
        public static ScanResult ok() {
            return new ScanResult(true, null);
        }

        public static ScanResult infected(String signature) {
            return new ScanResult(false, signature);
        }
    }

    /** Escanea el archivo. Si el motor no responde lanza RenderException (falla cerrada). */
    ScanResult scan(Path file);
}
