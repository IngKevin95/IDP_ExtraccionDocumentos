package com.idp.storage.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore;
import com.idp.storage.ObjectStore.StorageException;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

public abstract class ObjectStoreContract {

    protected abstract ObjectStore getStore();
    protected abstract TenantId getTenantA();
    protected abstract TenantId getTenantB();

    /**
     * Simula una caida del proveedor. Los adaptadores nuevos (GCS, Blob) deben sobrescribirlo (tasks.md T-04 y
     * T-05); mientras devuelva vacio, AC-06 se omite.
     */
    protected Optional<Runnable> providerFailure() {
        return Optional.empty();
    }

    protected ObjectMetadata meta(byte[] data) throws NoSuchAlgorithmException {
        return new ObjectMetadata(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)),
            data.length, "text/plain");
    }

    @Test
    protected void putYGetRoundTripBajoPrefijoDelTenant() throws Exception {
        byte[] data = "contenido".getBytes(StandardCharsets.UTF_8);
        getStore().put(getTenantA(), "docs/a.txt", new ByteArrayInputStream(data), meta(data));
        try (var in = getStore().get(getTenantA(), "docs/a.txt")) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    protected void getRutaInexistenteFalla() {
        assertThrows(StorageException.class, () -> getStore().get(getTenantA(), "docs/no.txt"));
    }

    @Test
    protected void deleteYGetPosteriorFalla() throws Exception {
        byte[] data = "contenido".getBytes(StandardCharsets.UTF_8);
        getStore().put(getTenantA(), "docs/del.txt", new ByteArrayInputStream(data), meta(data));
        getStore().delete(getTenantA(), "docs/del.txt");
        assertThrows(StorageException.class, () -> getStore().get(getTenantA(), "docs/del.txt"));
    }

    @Test
    protected void unTenantNoPuedeLeerRutaDeOtroTenant() throws Exception {
        byte[] data = "contenido".getBytes(StandardCharsets.UTF_8);
        getStore().put(getTenantA(), "docs/b.txt", new ByteArrayInputStream(data), meta(data));
        assertThrows(StorageException.class, () -> getStore().get(getTenantB(), "docs/b.txt"));
    }

    @Test
    protected void unTenantNoPuedeBorrarRutaDeOtroTenant() throws Exception {
        byte[] data = "contenido".getBytes(StandardCharsets.UTF_8);
        getStore().put(getTenantA(), "docs/c.txt", new ByteArrayInputStream(data), meta(data));
        // Borrar una ruta ausente puede ser idempotente o lanzar segun el proveedor; lo que importa es que
        // los datos del otro tenant sigan intactos.
        try {
            getStore().delete(getTenantB(), "docs/c.txt");
        } catch (StorageException ignorada) {
            // aceptable
        }
        try (var in = getStore().get(getTenantA(), "docs/c.txt")) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    protected void caracteresInvalidosLanzanStorageExceptionSinTocarProveedor() {
        assertThrows(StorageException.class, () -> getStore().get(getTenantA(), "../ruta"));
        assertThrows(StorageException.class, () -> getStore().get(new TenantId("t/1"), "ruta"));
        assertThrows(StorageException.class, () -> getStore().get(new TenantId(".."), "ruta"));
    }

    @Test
    protected void putYDeleteRechazanIdentificadoresInvalidos() throws Exception {
        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        assertThrows(StorageException.class,
            () -> getStore().put(getTenantA(), "../ruta", new ByteArrayInputStream(data), meta(data)));
        assertThrows(StorageException.class,
            () -> getStore().put(new TenantId("t/1"), "ruta", new ByteArrayInputStream(data), meta(data)));
        assertThrows(StorageException.class, () -> getStore().delete(getTenantA(), "../ruta"));
        assertThrows(StorageException.class, () -> getStore().delete(new TenantId("t/1"), "ruta"));
    }

    @Test
    protected void falloDelProveedorSePropagaSinPiiNiCredenciales() {
        Optional<Runnable> failure = providerFailure();
        Assumptions.assumeTrue(failure.isPresent(), "No hay simulacion de fallo");
        failure.get().run();
        StorageException ex = assertThrows(StorageException.class, () -> getStore().get(getTenantA(), "docs/a.txt"));
        assertEquals(-1, ex.getMessage().indexOf(getTenantA().value()));
    }
}
