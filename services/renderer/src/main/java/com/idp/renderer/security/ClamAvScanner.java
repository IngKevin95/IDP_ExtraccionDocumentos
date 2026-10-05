package com.idp.renderer.security;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.core.RenderException;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Cliente clamd por TCP con el comando zINSTREAM (chunks, sin cargar el archivo completo en memoria). */
@Component
public class ClamAvScanner implements AntivirusScanner {

    private static final Logger LOG = LoggerFactory.getLogger(ClamAvScanner.class);
    private static final int CHUNK = 64 * 1024;

    private final RendererProperties.Clamav cfg;

    public ClamAvScanner(RendererProperties props) {
        this.cfg = props.clamav();
    }

    @Override
    public ScanResult scan(Path file) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(cfg.host(), cfg.port()), (int) cfg.connectTimeout().toMillis());
            socket.setSoTimeout((int) cfg.readTimeout().toMillis());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buf = new byte[CHUNK];
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(ByteBuffer.allocate(4).putInt(n).array());
                    out.write(buf, 0, n);
                }
            }
            out.write(new byte[4]);
            out.flush();
            return parse(readReply(socket.getInputStream()));
        } catch (IOException e) {
            LOG.error("clamd no disponible: {}", e.getClass().getSimpleName());
            throw RenderException.scannerUnavailable();
        }
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) > 0 && bos.size() < 4096) {
            bos.write(b);
        }
        return bos.toString(StandardCharsets.US_ASCII).trim();
    }

    static ScanResult parse(String reply) {
        if (reply.endsWith("OK")) {
            return ScanResult.ok();
        }
        if (reply.endsWith("FOUND")) {
            String sig = reply.replaceFirst("^stream:\\s*", "").replaceFirst("\\s*FOUND$", "");
            return ScanResult.infected(sig);
        }
        LOG.error("Respuesta clamd inesperada");
        throw RenderException.scannerUnavailable();
    }
}
