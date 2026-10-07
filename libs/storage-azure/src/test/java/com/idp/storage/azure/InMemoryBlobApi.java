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
 * deja las versiones y una version bajo retencion o hold no se puede destruir con {@link #hardDelete}.
 */
class InMemoryBlobApi implements BlobApi {

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

    private void checkAvailable() {
        if (failing) {
            throw new IllegalStateException("proveedor no disponible");
        }
    }

    private static String id(String container, String blob) {
        return container + "|" + blob;
    }

    @Override
    public void write(String container, String blob, InputStream data, long length, String contentType,
                      byte[] sha256, Instant retainUntil) {
        checkAvailable();
        byte[] bytes;
        try {
            bytes = data.readAllBytes();
            if (bytes.length != length) {
                throw new IllegalArgumentException("longitud declarada distinta de la recibida");
            }
            if (sha256 != null && !MessageDigest.isEqual(sha256, MessageDigest.getInstance("SHA-256").digest(bytes))) {
                throw new IllegalArgumentException("sha-256 declarado distinto del recibido");
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
        checkAvailable();
        Blob b = blobs.get(id(container, blob));
        if (b == null || b.deleted) {
            throw new NotFoundException();
        }
        return new ByteArrayInputStream(b.versions.get(b.versions.size() - 1).data);
    }

    @Override
    public void delete(String container, String blob) {
        checkAvailable();
        Blob b = blobs.get(id(container, blob));
        if (b != null) {
            b.deleted = true;
        }
    }

    @Override
    public void setLegalHold(String container, String blob, boolean hold) {
        checkAvailable();
        Blob b = blobs.get(id(container, blob));
        if (b == null || b.deleted) {
            throw new NotFoundException();
        }
        b.versions.get(b.versions.size() - 1).legalHold = hold;
    }

    @Override
    public boolean versionLevelWormEnabled(String container) {
        checkAvailable();
        Boolean worm = containerWorm.get(container);
        if (worm == null) {
            throw new IllegalStateException("contenedor inexistente");
        }
        return worm;
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
