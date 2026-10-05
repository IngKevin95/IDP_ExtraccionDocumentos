package com.idp.review.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.review.config.ReviewProperties;
import com.idp.review.domain.ReviewField;
import com.idp.review.domain.ReviewTask;
import com.idp.review.infra.PageImageSource;
import com.idp.review.infra.ReviewRepository;
import com.idp.review.service.Exceptions.ConflictException;
import com.idp.review.service.Exceptions.TaskNotFoundException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Recorte de pagina del campo dudoso. Las paginas estan cifradas con sobre en el bucket, asi que el enlace no es una URL
 * presignada del bucket: apunta al endpoint de recorte de este servicio, con vencimiento corto y firma HMAC ligada a
 * tenant, tarea, campo y revisor. El servicio devuelve solo el recorte, nunca la pagina completa.
 */
@Service
public final class CropService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_PIXELS = 40_000_000L;
    /** Margen alrededor de la caja, como fraccion de la pagina, para dar contexto al revisor. */
    private static final double PADDING = 0.01;

    public record CropLink(String url, Instant expiresAt) {
    }

    private final ReviewRepository repo;
    private final PageImageSource pages;
    private final ReviewProperties props;
    private final Clock clock;

    public CropService(ReviewRepository repo, PageImageSource pages, ReviewProperties props, Clock clock) {
        if (props.cropSecret() == null || props.cropSecret().getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("idp.review.crop-secret debe tener al menos 32 bytes");
        }
        this.repo = repo;
        this.pages = pages;
        this.props = props;
        this.clock = clock;
    }

    public CropLink link(Caller caller, UUID taskId, UUID fieldId) {
        ReviewField field = authorizedField(caller, taskId, fieldId);
        requireEvidence(field);
        Instant exp = clock.instant().plus(props.cropTtl());
        String url = props.publicBaseUrl() + "/v1/review/tasks/" + taskId + "/fields/" + fieldId + "/crop?exp="
                + exp.getEpochSecond() + "&sig=" + sign(caller.tenantId(), taskId, fieldId, exp.getEpochSecond(),
                caller.userId());
        return new CropLink(url, exp);
    }

    /** PNG del recorte. Exige firma vigente emitida a este mismo revisor y sobre este campo. */
    public byte[] crop(Caller caller, UUID taskId, UUID fieldId, long exp, String sig) {
        ReviewField field = authorizedField(caller, taskId, fieldId);
        if (clock.instant().getEpochSecond() > exp || sig == null
                || !MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8),
                sign(caller.tenantId(), taskId, fieldId, exp, caller.userId()).getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException("Enlace de recorte invalido o vencido");
        }
        requireEvidence(field);
        ReviewTask task = repo.findTask(caller.tenantId(), taskId).orElseThrow(TaskNotFoundException::new);
        byte[] png = pages.page(caller.tenantId(), task.documentId(), field.page())
                .orElseThrow(() -> new ConflictException("REVIEW_PAGE_UNAVAILABLE", "Pagina no disponible"));
        return cropPng(png, parseBox(field.boundingBox()));
    }

    /** Solo el REVISOR ve el contenido, y solo de tareas abiertas que no tiene asignadas otro revisor. */
    private ReviewField authorizedField(Caller caller, UUID taskId, UUID fieldId) {
        ReviewTask task = repo.findTask(caller.tenantId(), taskId).orElseThrow(TaskNotFoundException::new);
        if (!task.status().open()) {
            throw new Exceptions.InvalidStateException("La tarea ya esta cerrada");
        }
        if (task.assigneeId() != null && !task.assigneeId().equals(caller.userId())) {
            throw new ConflictException("REVIEW_TASK_ASSIGNED", "La tarea esta asignada a otro revisor");
        }
        return repo.field(taskId, fieldId).orElseThrow(TaskNotFoundException::new);
    }

    private static void requireEvidence(ReviewField field) {
        if (field.page() == null || field.page() < 1 || field.boundingBox() == null) {
            throw new ConflictException("REVIEW_NO_EVIDENCE", "El campo no tiene evidencia de pagina");
        }
    }

    /** Caja [x, y, ancho, alto] normalizada; rechaza valores fuera de [0, 1] o de tamano nulo. */
    public static double[] parseBox(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            if (!n.isArray() || n.size() != 4) {
                throw new ConflictException("REVIEW_NO_EVIDENCE", "Caja de evidencia invalida");
            }
            double[] b = new double[4];
            for (int i = 0; i < 4; i++) {
                if (!n.get(i).isNumber()) {
                    throw new ConflictException("REVIEW_NO_EVIDENCE", "Caja de evidencia invalida");
                }
                b[i] = n.get(i).asDouble();
            }
            if (b[0] < 0 || b[1] < 0 || b[2] <= 0 || b[3] <= 0 || b[0] + b[2] > 1.0001 || b[1] + b[3] > 1.0001) {
                throw new ConflictException("REVIEW_NO_EVIDENCE", "Caja de evidencia fuera de la pagina");
            }
            return b;
        } catch (IOException e) {
            throw new ConflictException("REVIEW_NO_EVIDENCE", "Caja de evidencia invalida");
        }
    }

    public static byte[] cropPng(byte[] pagePng, double[] box) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(pagePng))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new ConflictException("REVIEW_PAGE_UNAVAILABLE", "Pagina no legible");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if ((long) w * h > MAX_PIXELS) {
                    throw new ConflictException("REVIEW_PAGE_UNAVAILABLE", "Pagina demasiado grande");
                }
                BufferedImage page = reader.read(0);
                int x0 = clamp((int) Math.round((box[0] - PADDING) * w), 0, w - 1);
                int y0 = clamp((int) Math.round((box[1] - PADDING) * h), 0, h - 1);
                int x1 = clamp((int) Math.round((box[0] + box[2] + PADDING) * w), x0 + 1, w);
                int y1 = clamp((int) Math.round((box[1] + box[3] + PADDING) * h), y0 + 1, h);
                BufferedImage out = page.getSubimage(x0, y0, x1 - x0, y1 - y0);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!ImageIO.write(out, "png", bytes)) {
                    throw new IllegalStateException("No hay escritor PNG");
                }
                return bytes.toByteArray();
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private String sign(String tenantId, UUID taskId, UUID fieldId, long exp, String userId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.cropSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((tenantId + "|" + taskId + "|" + fieldId + "|" + exp + "|"
                    + userId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
