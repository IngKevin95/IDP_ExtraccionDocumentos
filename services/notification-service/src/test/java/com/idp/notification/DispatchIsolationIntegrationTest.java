package com.idp.notification;

import com.idp.testsupport.Topics;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.notification.support.TestReceiver;
import com.idp.notification.support.TestReceiver.Reply;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Hallazgo 3: un tenant lento no retrasa a los demas (despacho en paralelo y sin esperar). */
@TestPropertySource(properties = "idp.tenants=" + DispatchIsolationIntegrationTest.SLOW + ","
    + DispatchIsolationIntegrationTest.FAST)
class DispatchIsolationIntegrationTest extends AbstractIntegrationTest {

    static final String SLOW = "aaaaaaaa-0000-0000-0000-00000000000a";
    static final String FAST = "bbbbbbbb-0000-0000-0000-00000000000b";

    private static boolean waitFor(BooleanSupplier condition, Duration max) throws InterruptedException {
        long deadline = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private boolean allStatus(String tenant, String status) {
        List<java.util.Map<String, Object>> rows = deliveries(tenant);
        return !rows.isEmpty() && rows.stream().allMatch(r -> status.equals(r.get("status")));
    }

    @Test
    void unTenantLentoNoRetrasaAOtroYRunAllNoBloquea() throws Exception {
        dns.map("lento.banco.test", "127.0.0.1");
        dns.map("rapido.banco.test", "127.0.0.1");
        migrator.migrate(SLOW);
        migrator.migrate(FAST);
        allowHosts(SLOW, "lento.banco.test");
        allowHosts(FAST, "rapido.banco.test");
        try (TestReceiver slow = new TestReceiver()) {
            slow.otherwise(r -> Reply.delayed(200, 1200));
            createWebhook(SLOW, "http://lento.banco.test:" + slow.port() + "/h", "extraccion.aprobada");
            createWebhook(FAST, "http://rapido.banco.test:" + receiver.port() + "/h", "extraccion.aprobada");
            for (int i = 0; i < 3; i++) {
                Topics.deliver(listener::onMessage, aprobada(SLOW, UUID.randomUUID().toString()));
            }
            Topics.deliver(listener::onMessage, aprobada(FAST, UUID.randomUUID().toString()));

            long t0 = System.nanoTime();
            int queued = worker.runAll();
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertThat(queued).isEqualTo(2);
            // runAll solo encola: no espera a los 3 envios lentos (>= 3,6 s en serie).
            assertThat(elapsedMs).isLessThan(1000);
            // El tenant rapido termina mientras el lento sigue en curso.
            assertThat(waitFor(() -> allStatus(FAST, "ENTREGADO"), Duration.ofMillis(1100))).isTrue();
            assertThat(allStatus(SLOW, "ENTREGADO")).isFalse();
            // El lento termina todo (tope por tenant = 1: en serie), sin perder entregas.
            assertThat(waitFor(() -> allStatus(SLOW, "ENTREGADO"), Duration.ofSeconds(15))).isTrue();
            assertThat(slow.count()).isEqualTo(3);
            assertThat(receiver.count()).isEqualTo(1);
        }
    }
}
