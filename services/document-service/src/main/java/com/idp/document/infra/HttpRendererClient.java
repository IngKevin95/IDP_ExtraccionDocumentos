package com.idp.document.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.net.ssl.SSLContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Cliente HTTP del renderer (POST /v1/render multipart -> ZIP con page_N.png y text_layer.json). */
public class HttpRendererClient implements RendererClient {

    static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    static final int MAX_ENTRIES = 1000;
    private static final Pattern PAGE = Pattern.compile("page_(\\d{1,6})\\.png");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient client;

    public HttpRendererClient(String baseUrl, Duration connectTimeout, Duration readTimeout, SSLContext sslContext) {
        HttpClient.Builder http = HttpClient.newBuilder().connectTimeout(connectTimeout);
        if (sslContext != null) {
            http.sslContext(sslContext);
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(readTimeout);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public RenderResult render(String filename, String contentType, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        byte[] zip;
        try {
            zip = client.post().uri("/v1/render").contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.parseMediaType("application/zip"), MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(byte[].class);
        } catch (RestClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.is4xxClientError()) {
                throw new RejectedException(errorCode(e.getResponseBodyAsString()), status.value());
            }
            throw new UnavailableException("Renderer respondio " + status.value(), e);
        } catch (RestClientException e) {
            throw new UnavailableException("Renderer inaccesible", e);
        }
        if (zip == null || zip.length == 0) {
            throw new UnavailableException("Renderer devolvio respuesta vacia", null);
        }
        return parse(zip);
    }

    private static String errorCode(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            return n.path("code").asText("ERR_UNKNOWN");
        } catch (IOException | RuntimeException e) {
            return "ERR_UNKNOWN";
        }
    }

    static RenderResult parse(byte[] zip) {
        List<Page> pages = new ArrayList<>();
        byte[] text = null;
        long total = 0;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new UnavailableException("ZIP del renderer excede el numero de entradas", null);
                }
                byte[] data = readBounded(in);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new UnavailableException("ZIP del renderer excede el tamano total", null);
                }
                String name = entry.getName();
                Matcher m = PAGE.matcher(name);
                if (m.matches()) {
                    pages.add(new Page(Integer.parseInt(m.group(1)), data));
                } else if ("text_layer.json".equals(name)) {
                    text = data;
                }
            }
        } catch (IOException e) {
            throw new UnavailableException("ZIP del renderer ilegible", e);
        }
        if (pages.isEmpty()) {
            throw new UnavailableException("ZIP del renderer sin paginas", null);
        }
        pages.sort(Comparator.comparingInt(Page::number));
        return new RenderResult(List.copyOf(pages), text == null ? new byte[0] : text);
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long read = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            read += n;
            if (read > MAX_ENTRY_BYTES) {
                throw new UnavailableException("Entrada del ZIP del renderer excede el limite", null);
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
