package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.idp.review.config.ReviewProperties;
import com.idp.review.domain.FieldStatus;
import com.idp.review.domain.ReviewField;
import com.idp.review.domain.ReviewTask;
import com.idp.review.domain.TaskStatus;
import com.idp.review.infra.PageImageSource;
import com.idp.review.infra.ReviewRepository;
import com.idp.review.service.Caller;
import com.idp.review.service.CropService;
import com.idp.review.service.Exceptions.ConflictException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** Enlace firmado de corta vida y recorte de la caja normalizada. */
class CropServiceTest {

    private static final String TENANT = UUID.randomUUID().toString();
    private final UUID taskId = UUID.randomUUID();
    private final UUID fieldId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final Caller ana = new Caller(TENANT, "ana", Set.of("REVISOR"));
    private final ReviewRepository repo = mock(ReviewRepository.class);
    private final PageImageSource pages = mock(PageImageSource.class);

    private static ReviewProperties props(Duration ttl, String secret) {
        return new ReviewProperties("documents", "https://api.idp.example", secret, ttl, Duration.ofHours(4),
                Duration.ofHours(1), 3, List.of("monto"), "none", false, 1024, 2, 3, 40_000_000L,
                new ReviewProperties.Relay(false, Duration.ofSeconds(1)),
                new ReviewProperties.Escalation(false, Duration.ofMinutes(1)));
    }

    private CropService service(Instant now, Duration ttl) {
        return new CropService(repo, pages, props(ttl, "k".repeat(40)), Clock.fixed(now, ZoneOffset.UTC),
                mock(com.idp.review.service.BlindReviewPolicy.class), false);
    }

    private void givenField(String bbox) {
        OffsetDateTime t = OffsetDateTime.now();
        when(repo.findTask(TENANT, taskId)).thenReturn(Optional.of(new ReviewTask(taskId, TENANT, documentId,
                UUID.randomUUID(), TaskStatus.PENDING, null, null, null, null, false, t.plusHours(1), 0, null, null,
                t, t)));
        when(repo.field(taskId, fieldId)).thenReturn(Optional.of(new ReviewField(fieldId, taskId, "monto", 1, bbox,
                null, null, true, FieldStatus.PENDING, t)));
    }

    private static byte[] png(int w, int h) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static long exp(String url) {
        String q = URI.create(url).getQuery();
        return Long.parseLong(q.substring(q.indexOf("exp=") + 4, q.indexOf('&')));
    }

    private static String sig(String url) {
        String q = URI.create(url).getQuery();
        return q.substring(q.indexOf("sig=") + 4);
    }

    @Test
    void elEnlaceVenceAlCumplirseElTtl() throws Exception {
        givenField("[0.1,0.1,0.5,0.1]");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        when(pages.page(TENANT, documentId, 1)).thenReturn(Optional.of(png(100, 100)));
        CropService.CropLink link = service(t0, Duration.ofSeconds(60)).link(ana, taskId, fieldId);

        assertThat(link.expiresAt()).isEqualTo(t0.plusSeconds(60));
        assertThat(link.url()).startsWith("https://api.idp.example/v1/review/tasks/" + taskId + "/fields/" + fieldId
                + "/crop?exp=");
        assertThat(service(t0.plusSeconds(60), Duration.ofSeconds(60)).crop(ana, taskId, fieldId, exp(link.url()),
                sig(link.url()))).isNotEmpty();
        assertThatThrownBy(() -> service(t0.plusSeconds(61), Duration.ofSeconds(60)).crop(ana, taskId, fieldId,
                exp(link.url()), sig(link.url()))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void extenderElVencimientoInvalidaLaFirma() {
        givenField("[0.1,0.1,0.5,0.1]");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        CropService.CropLink link = service(t0, Duration.ofSeconds(60)).link(ana, taskId, fieldId);

        assertThatThrownBy(() -> service(t0.plusSeconds(120), Duration.ofSeconds(60)).crop(ana, taskId, fieldId,
                exp(link.url()) + 3600, sig(link.url()))).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void elSecretoDebeTenerAlMenos32Bytes() {
        ReviewProperties weak = props(Duration.ofSeconds(60), "corto");

        assertThatThrownBy(() -> new CropService(repo, pages, weak, Clock.systemUTC(),
                mock(com.idp.review.service.BlindReviewPolicy.class), false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recortaLaCajaNormalizadaConMargenYNuncaDevuelveLaPaginaCompleta() throws Exception {
        byte[] out = CropService.cropPng(png(1000, 2000), CropService.parseBox("[0.2,0.3,0.4,0.1]"));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(out));

        // 400 x 200 px de caja, mas 1% de margen de pagina por lado (10 y 20 px)
        assertThat(img.getWidth()).isEqualTo(420);
        assertThat(img.getHeight()).isEqualTo(240);
    }

    @Test
    void elMargenNoSaleDeLosBordesDeLaPagina() throws Exception {
        byte[] out = CropService.cropPng(png(100, 100), CropService.parseBox("[0,0,1,1]"));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(out));

        assertThat(img.getWidth()).isEqualTo(100);
        assertThat(img.getHeight()).isEqualTo(100);
    }

    @Test
    void cajasInvalidasSeRechazan() {
        for (String bad : new String[] {"[0.1,0.1,0.5]", "[0.1,0.1,0.5,0]", "[-0.1,0,0.5,0.5]", "[0.8,0,0.5,0.5]",
                "[\"a\",0,0.5,0.5]", "{\"x\":1}", "no-json"}) {
            assertThatThrownBy(() -> CropService.parseBox(bad)).isInstanceOf(ConflictException.class);
        }
    }

    @Test
    void unaPaginaIlegibleNoSeSirve() {
        assertThatThrownBy(() -> CropService.cropPng(new byte[] {1, 2, 3}, new double[] {0, 0, 1, 1}))
                .isInstanceOf(ConflictException.class);
    }

    private CropService tuned(String baseUrl, boolean dev, int concurrent, int rate, long maxPixels, Instant now) {
        ReviewProperties p = new ReviewProperties("documents", baseUrl, "k".repeat(40), Duration.ofSeconds(60),
                Duration.ofHours(4), Duration.ofHours(1), 3, List.of("monto"), "none", false, 1024, concurrent, rate,
                maxPixels, new ReviewProperties.Relay(false, Duration.ofSeconds(1)),
                new ReviewProperties.Escalation(false, Duration.ofMinutes(1)));
        return new CropService(repo, pages, p, Clock.fixed(now, ZoneOffset.UTC),
                mock(com.idp.review.service.BlindReviewPolicy.class), dev);
    }

    @Test
    void sec_elRateLimitPorUsuarioResponde429AlExcederLaCuota() throws Exception {
        givenField("[0.1,0.1,0.5,0.1]");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        when(pages.page(TENANT, documentId, 1)).thenReturn(Optional.of(png(100, 100)));
        CropService svc = tuned("https://api.idp.example", false, 4, 3, 40_000_000L, t0);
        CropService.CropLink link = svc.link(ana, taskId, fieldId);

        for (int i = 0; i < 3; i++) {
            assertThat(svc.crop(ana, taskId, fieldId, exp(link.url()), sig(link.url()))).isNotEmpty();
        }
        assertThatThrownBy(() -> svc.crop(ana, taskId, fieldId, exp(link.url()), sig(link.url())))
                .isInstanceOf(com.idp.review.service.Exceptions.TooManyRequestsException.class);
        Caller beto = new Caller(TENANT, "beto", Set.of("REVISOR"));
        CropService.CropLink other = svc.link(beto, taskId, fieldId);
        assertThat(svc.crop(beto, taskId, fieldId, exp(other.url()), sig(other.url()))).isNotEmpty();
    }

    @Test
    void sec_elSemaforoDeDecodificacionResponde503CuandoEstaAgotado() throws Exception {
        givenField("[0.1,0.1,0.5,0.1]");
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        CropService svc = tuned("https://api.idp.example", false, 1, 100, 40_000_000L, t0);
        CropService.CropLink link = svc.link(ana, taskId, fieldId);
        java.util.concurrent.atomic.AtomicReference<Throwable> nested = new java.util.concurrent.atomic.AtomicReference<>();
        byte[] page = png(100, 100);
        when(pages.page(TENANT, documentId, 1)).thenAnswer(inv -> {
            // segunda decodificacion mientras la unica ficha esta tomada
            try {
                svc.crop(ana, taskId, fieldId, exp(link.url()), sig(link.url()));
            } catch (Throwable t) {
                nested.set(t);
            }
            return Optional.of(page);
        });

        assertThat(svc.crop(ana, taskId, fieldId, exp(link.url()), sig(link.url()))).isNotEmpty();

        assertThat(nested.get()).isInstanceOf(com.idp.review.service.Exceptions.ServiceBusyException.class);
        when(pages.page(TENANT, documentId, 1)).thenReturn(Optional.of(page));
        assertThat(svc.crop(ana, taskId, fieldId, exp(link.url()), sig(link.url()))).isNotEmpty();
    }

    @Test
    void sec_laPaginaMasGrandeQueElLimiteDePixelesNoSeDecodifica() throws Exception {
        assertThatThrownBy(() -> CropService.cropPng(png(200, 200), new double[] {0.1, 0.1, 0.2, 0.2}, 10_000L))
                .isInstanceOf(ConflictException.class);
        assertThat(CropService.cropPng(png(200, 200), new double[] {0.1, 0.1, 0.2, 0.2}, 40_000L)).isNotEmpty();
    }

    @Test
    void sec_publicBaseUrlEsObligatoriaYHttpsSalvoDevMode() {
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> tuned(null, true, 4, 30, 1L, t0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tuned(" ", true, 4, 30, 1L, t0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tuned("http://api.idp.example", false, 4, 30, 1L, t0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tuned("ftp://api.idp.example", true, 4, 30, 1L, t0))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tuned("https://api.idp.example", false, 4, 30, 1L, t0)).isNotNull();
        assertThat(tuned("http://localhost:8080", true, 4, 30, 1L, t0)).isNotNull();
    }
}
