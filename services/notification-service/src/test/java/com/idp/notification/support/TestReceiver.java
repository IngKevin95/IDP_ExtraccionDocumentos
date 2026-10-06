package com.idp.notification.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Receptor HTTP de pruebas (loopback) que registra las solicitudes y responde segun un guion. */
public final class TestReceiver implements AutoCloseable {

    /** Solicitud recibida. */
    public record Received(String method, String path, Map<String, String> headers, byte[] body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** Respuesta del guion. */
    public record Reply(int status, Map<String, String> headers, long delayMs) {
        public static Reply status(int code) {
            return new Reply(code, Map.of(), 0);
        }

        public static Reply redirect(String location) {
            return new Reply(302, Map.of("Location", location), 0);
        }

        public static Reply delayed(int code, long delayMs) {
            return new Reply(code, Map.of(), delayMs);
        }
    }

    private final HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentLinkedQueue<Reply> script = new ConcurrentLinkedQueue<>();
    private volatile Function<Received, Reply> fallback = r -> Reply.status(200);

    public TestReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Respuestas en orden; agotado el guion se usa la respuesta por defecto (200). */
    public TestReceiver reply(Reply... replies) {
        script.addAll(List.of(replies));
        return this;
    }

    public TestReceiver otherwise(Function<Received, Reply> f) {
        this.fallback = f;
        return this;
    }

    public List<Received> received() {
        synchronized (received) {
            return List.copyOf(received);
        }
    }

    public int count() {
        return received.size();
    }

    private void handle(HttpExchange ex) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
        byte[] body = ex.getRequestBody().readAllBytes();
        Received r = new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(), headers, body);
        received.add(r);
        Reply reply = script.poll();
        if (reply == null) {
            reply = fallback.apply(r);
        }
        try {
            if (reply.delayMs() > 0) {
                Thread.sleep(reply.delayMs());
            }
            reply.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(reply.status(), -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // el cliente cerro la conexion (timeout): esperado en pruebas de plazo
        } finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
