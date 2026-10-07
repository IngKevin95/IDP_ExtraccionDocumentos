package com.idp.storage.gcs;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Fake de {@link GcsBlobApi} con la semantica WORM de GCS: no se sobrescribe ni se borra un objeto con retencion
 * vigente o hold temporal. El reloj es controlable para avanzar mas alla de la retencion.
 */
class InMemoryGcsBlobApi implements GcsBlobApi {

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(java.time.Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class Blob {
        byte[] data;
        Instant retainUntil;
        boolean hold;
    }

    final MutableClock clock = new MutableClock();
    boolean failing;
    private final Map<String, Blob> blobs = new HashMap<>();
    private final Set<String> bucketsWithRetention = new HashSet<>();

    InMemoryGcsBlobApi withObjectRetention(String bucket) {
        bucketsWithRetention.add(bucket);
        return this;
    }

    private static String id(String bucket, String key) {
        return bucket + "/" + key;
    }

    private void checkUp() {
        if (failing) {
            throw new BlobApiException("error del proveedor (codigo 503)");
        }
    }

    private boolean locked(Blob b) {
        return b.hold || (b.retainUntil != null && clock.instant().isBefore(b.retainUntil));
    }

    @Override
    public void write(String bucket, String key, byte[] data, String contentType, Instant retainUntil) {
        checkUp();
        Blob current = blobs.get(id(bucket, key));
        if (current != null && locked(current)) {
            throw new BlobApiException("error del proveedor (codigo 403)");
        }
        Blob b = new Blob();
        b.data = data.clone();
        b.retainUntil = retainUntil;
        blobs.put(id(bucket, key), b);
    }

    @Override
    public byte[] read(String bucket, String key) {
        checkUp();
        Blob b = blobs.get(id(bucket, key));
        if (b == null) {
            throw new BlobNotFoundException();
        }
        return b.data.clone();
    }

    @Override
    public void delete(String bucket, String key) {
        checkUp();
        Blob b = blobs.get(id(bucket, key));
        if (b == null) {
            return;
        }
        if (locked(b)) {
            throw new BlobApiException("error del proveedor (codigo 403)");
        }
        blobs.remove(id(bucket, key));
    }

    @Override
    public void setTemporaryHold(String bucket, String key, boolean hold) {
        checkUp();
        Blob b = blobs.get(id(bucket, key));
        if (b == null) {
            throw new BlobNotFoundException();
        }
        b.hold = hold;
    }

    @Override
    public boolean objectRetentionEnabled(String bucket) {
        checkUp();
        return bucketsWithRetention.contains(bucket);
    }

    /** Contenido que el proveedor conserva, sin pasar por las reglas de escritura. */
    byte[] rawContent(String bucket, String key) {
        return blobs.get(id(bucket, key)).data.clone();
    }
}
