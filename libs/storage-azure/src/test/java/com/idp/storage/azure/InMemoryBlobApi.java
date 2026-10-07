package com.idp.storage.azure;

import com.idp.storage.ObjectStore.StorageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fake en memoria de {@link BlobApi} con semantica de versiones, retencion y legal hold (el reloj es el del
 * adaptador, controlable). Modela solo lo que el contrato necesita: cada escritura crea una version, un borrado
 * deja las versiones y una version bajo retencion o hold no se puede destruir con {@link #hardDelete}. Los
 * contenedores deben crearse antes de usarse y la retencion exige WORM por version y una fecha futura.
 */
class InMemoryBlobApi implements BlobApi {

    /** Error del proveedor simulado (404 ContainerNotFound, 409 por WORM no habilitado, etc.). */
    static class SimulatedProviderException extends RuntimeException {
        SimulatedProviderException(String message) {
            super(message);
        }
    }

    private static final class Version {
        final byte[] data;
        final Instant retainUntil;
        boolean legalHold;

        Version(byte[] data, Instant retainUntil) {
            this.data = data;
            this.retainUntil = retainUntil;
        }
    }

    private static final class Blob {
        final List<Version> versions = new ArrayList<>();
        boolean deleted;
    }

    private final Clock clock;
    private final Map<String, Boolean> containerWorm = new HashMap<>();
    private final Map<String, Blob> blobs = new HashMap<>();
    boolean failing;

    InMemoryBlobApi(Clock clock) {
        this.clock = clock;
    }

    void createContainer(String container, boolean versionLevelWorm) {
        containerWorm.put(container, versionLevelWorm);
    }

    private void checkContainer(String container) {
        if (failing) {
            throw new IllegalStateException("proveedor no disponible");
        }
        if (!containerWorm.containsKey(container)) {
            throw new SimulatedProviderException("404 ContainerNotFound");
        }
    }

    private static String id(String container, String blob) {
        return container + "|" + blob;
    }

    @Override
    public void write(String container, String blob, InputStream data, long length, String contentType,
                      byte[] sha256, Instant retainUntil) {
        checkContainer(container);
        if (retainUntil != null) {
            if (!containerWorm.get(container)) {
                throw new SimulatedProviderException("409 contenedor sin immutable storage con versionado");
            }
            if (!retainUntil.isAfter(clock.instant())) {
                throw new SimulatedProviderException("400 fecha de expiracion en el pasado");
            }
        }
        byte[] bytes;
        try {
            bytes = data.readAllBytes();
            if (bytes.length != length) {
                throw new StorageException("longitud declarada distinta de la recibida");
            }
            if (sha256 != null && !MessageDigest.isEqual(sha256, MessageDigest.getInstance("SHA-256").digest(bytes))) {
                throw new StorageException("sha-256 declarado distinto del recibido");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Blob b = blobs.computeIfAbsent(id(container, blob), k -> new Blob());
        b.versions.add(new Version(bytes, retainUntil));
        b.deleted = false;
    }

    @Override
    public InputStream read(String container, String blob) {
        checkContainer(container);
        Blob b = blobs.get(id(container, blob));
        if (b == null || b.deleted) {
            throw new NotFoundException();
        }
        return new ByteArrayInputStream(b.versions.get(b.versions.size() - 1).data);
    }

    @Override
    public void delete(String container, String blob) {
        checkContainer(container);
        Blob b = blobs.get(id(container, blob));
        if (b != null) {
            b.deleted = true;
        }
    }

    @Override
    public void setLegalHold(String container, String blob, boolean hold) {
        checkContainer(container);
        Blob b = blobs.get(id(container, blob));
        if (b == null || b.deleted) {
            throw new NotFoundException();
        }
        b.versions.get(b.versions.size() - 1).legalHold = hold;
    }

    @Override
    public boolean versionLevelWormEnabled(String container) {
        checkContainer(container);
        return containerWorm.get(container);
    }

    private Version oldest(String container, String blob) {
        Blob b = blobs.get(id(container, blob));
        if (b == null || b.versions.isEmpty()) {
            throw new NotFoundException();
        }
        return b.versions.get(0);
    }

    /** Destruye la version mas antigua; falla mientras tenga retencion vigente o legal hold. */
    void hardDelete(String container, String blob) {
        Version v = oldest(container, blob);
        if (v.legalHold || (v.retainUntil != null && v.retainUntil.isAfter(clock.instant()))) {
            throw new StorageException("version inmutable");
        }
        blobs.get(id(container, blob)).versions.remove(0);
    }

    byte[] retainedContent(String container, String blob) {
        return oldest(container, blob).data;
    }
}
