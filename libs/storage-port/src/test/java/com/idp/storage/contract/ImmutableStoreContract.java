package com.idp.storage.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectStore.StorageException;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Contrato WORM portable. Los proveedores difieren en como rechazan un borrado logico bajo retencion (S3 agrega
 * un delete marker; GCS y Blob responden con error), por eso la prueba no se apoya en {@code delete} del puerto
 * sino en {@link #hardDelete}: el intento de destruir de forma irreversible el contenido retenido, que DEBE
 * fallar mientras haya retencion o legal hold y DEBE funcionar cuando no los hay (control positivo: sin el, un
 * adaptador sin lock pasaria las mismas pruebas).
 */
public abstract class ImmutableStoreContract extends ObjectStoreContract {

    @Override
    protected abstract ImmutableStore getStore();

    /** Deja pasar el tiempo suficiente para que venza una retencion de 1 segundo. */
    protected abstract void advancePastRetention() throws InterruptedException;

    /**
     * Intenta destruir de forma irreversible el contenido retenido en el proveedor (S3: borrar la version por
     * versionId; GCS y Blob: borrar el objeto). Lanza {@link StorageException} si el proveedor lo rechaza.
     */
    protected abstract void hardDelete(TenantId tenantId, String path) throws Exception;

    /** Contenido original que el proveedor conserva, aunque el puerto ya no lo sirva (delete marker, version nueva). */
    protected abstract byte[] retainedContent(TenantId tenantId, String path) throws Exception;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    protected void sinRetencionNiHoldElBorradoDefinitivoFunciona() throws Exception {
        byte[] data = bytes("control");
        getStore().put(getTenantA(), "lock/control.txt", new ByteArrayInputStream(data), meta(data));
        assertDoesNotThrow(() -> hardDelete(getTenantA(), "lock/control.txt"));
    }

    @Test
    protected void retencionImpideElBorradoDefinitivoYConservaElContenido() throws Exception {
        byte[] original = bytes("retencion");
        byte[] otro = bytes("sobrescritura");
        getStore().putWithRetention(getTenantA(), "lock/retencion.txt", new ByteArrayInputStream(original),
            meta(original), Duration.ofMinutes(5));

        assertThrows(Exception.class, () -> hardDelete(getTenantA(), "lock/retencion.txt"));
        try {
            getStore().put(getTenantA(), "lock/retencion.txt", new ByteArrayInputStream(otro), meta(otro));
        } catch (StorageException rechazoDelProveedor) {
            // aceptable: GCS y Blob rechazan la sobrescritura; S3 crea una version nueva
        }
        assertArrayEquals(original, retainedContent(getTenantA(), "lock/retencion.txt"));
    }

    @Test
    protected void legalHoldImpideElBorradoDefinitivoTrasVencerLaRetencion() throws Exception {
        byte[] original = bytes("legalhold");
        getStore().putWithRetention(getTenantA(), "lock/hold.txt", new ByteArrayInputStream(original),
            meta(original), Duration.ofSeconds(1));
        getStore().applyLegalHold(getTenantA(), "lock/hold.txt");

        advancePastRetention();
        assertThrows(Exception.class, () -> hardDelete(getTenantA(), "lock/hold.txt"));
        assertArrayEquals(original, retainedContent(getTenantA(), "lock/hold.txt"));

        getStore().removeLegalHold(getTenantA(), "lock/hold.txt");
        assertDoesNotThrow(() -> hardDelete(getTenantA(), "lock/hold.txt"));
    }
}
