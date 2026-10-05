package com.idp.notification.http;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

/** Salida HTTP hacia el integrador. La unica implementacion de produccion aplica anti-SSRF y no sigue redirects. */
public interface WebhookTransport {

    /** POST del cuerpo JSON; devuelve el codigo HTTP sin leer el cuerpo de la respuesta. */
    int post(URI uri, byte[] body, Map<String, String> headers) throws IOException;
}
