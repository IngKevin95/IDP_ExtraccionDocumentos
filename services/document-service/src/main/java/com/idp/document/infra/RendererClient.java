package com.idp.document.infra;

import java.util.List;

/** Puerto hacia el renderer (llamada sincrona). Permite sustituirlo en tests. */
public interface RendererClient {

    RenderResult render(String filename, String contentType, byte[] content);

    record Page(int number, byte[] png) {
    }

    record RenderResult(List<Page> pages, byte[] textLayer) {
    }

    /** El renderer rechazo el documento (4xx): no se reintenta. {@code code} sigue la taxonomia ERR_*. */
    class RejectedException extends RuntimeException {
        private final String code;
        private final int status;

        public RejectedException(String code, int status) {
            super("Renderer rechazo el documento: " + code + " (" + status + ")");
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }

    /** Fallo transitorio del renderer (5xx, timeout, red, respuesta ilegible): se reintenta. */
    class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
