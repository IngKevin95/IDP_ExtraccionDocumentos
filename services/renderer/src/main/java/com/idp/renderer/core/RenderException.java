package com.idp.renderer.core;

/** Error de negocio con codigo de la taxonomia del contrato y estado HTTP. */
public class RenderException extends RuntimeException {

    private final int status;
    private final String code;

    private RenderException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static RenderException unsupportedFormat(String msg) {
        return new RenderException(400, "ERR_UNSUPPORTED_FORMAT", msg);
    }

    public static RenderException invalid(String msg) {
        return new RenderException(400, "ERR_VALIDATION", msg);
    }

    public static RenderException malware() {
        return new RenderException(400, "ERR_MALWARE_DETECTED",
                "El motor antivirus detecto una firma maliciosa en el archivo.");
    }

    public static RenderException limitExceeded(String msg) {
        return new RenderException(413, "ERR_LIMIT_EXCEEDED", msg);
    }

    public static RenderException activeContent(String msg) {
        return new RenderException(422, "ERR_ACTIVE_CONTENT", msg);
    }

    public static RenderException encrypted() {
        return new RenderException(422, "ERR_ENCRYPTED", "Documento protegido con contrasena.");
    }

    public static RenderException conversionRejected(String msg) {
        return new RenderException(422, "ERR_CONVERSION_REJECTED", msg);
    }

    public static RenderException timeout(String msg) {
        return new RenderException(500, "ERR_TIMEOUT", msg);
    }

    public static RenderException busy() {
        return new RenderException(503, "ERR_RENDERER_BUSY", "Servicio de renderizacion saturado; reintente.");
    }

    public static RenderException scannerUnavailable() {
        return new RenderException(500, "ERR_SCANNER_UNAVAILABLE", "Servicio antivirus no disponible.");
    }

    public static RenderException converterUnavailable() {
        return new RenderException(500, "ERR_CONVERTER_UNAVAILABLE", "Conversor de documentos no disponible.");
    }

    public static RenderException internal(String msg) {
        return new RenderException(500, "ERR_RENDER_FAILED", msg);
    }
}
