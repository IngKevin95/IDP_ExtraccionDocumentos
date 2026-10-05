package com.idp.audit.support;

import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectMetadata;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** ImmutableStore en memoria: registra retenciones y legal holds; permite alterar objetos para pruebas forenses. */
public final class RecordingImmutableStore implements ImmutableStore {

    public record Put(String tenant, String path, Duration retention, ObjectMetadata metadata) {}

    public final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    public final List<Put> puts = new CopyOnWriteArrayList<>();
    public final Set<String> legalHolds = ConcurrentHashMap.newKeySet();
    public volatile boolean failPuts;

    private static String key(TenantId t, String path) {
        return t.value() + "/" + path;
    }

    @Override
    public void putWithRetention(TenantId t, String path, InputStream data, ObjectMetadata m, Duration retention) {
        if (failPuts) {
            throw new StorageException("WORM no disponible");
        }
        objects.put(key(t, path), read(data));
        puts.add(new Put(t.value(), path, retention, m));
    }

    @Override
    public void applyLegalHold(TenantId t, String path) {
        legalHolds.add(key(t, path));
    }

    @Override
    public void removeLegalHold(TenantId t, String path) {
        legalHolds.remove(key(t, path));
    }

    @Override
    public void put(TenantId t, String path, InputStream data, ObjectMetadata m) {
        objects.put(key(t, path), read(data));
    }

    @Override
    public InputStream get(TenantId t, String path) {
        byte[] b = objects.get(key(t, path));
        if (b == null) {
            throw new StorageException("Objeto inexistente");
        }
        return new ByteArrayInputStream(b);
    }

    @Override
    public void delete(TenantId t, String path) {
        throw new StorageException("Objeto bajo retencion: borrado denegado");
    }

    public boolean hasLegalHold(String tenant, String path) {
        return legalHolds.contains(tenant + "/" + path);
    }

    public void tamper(String tenant, String path, byte[] replacement) {
        objects.put(tenant + "/" + path, replacement);
    }

    public byte[] raw(String tenant, String path) {
        return objects.get(tenant + "/" + path);
    }

    private static byte[] read(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new StorageException("Lectura fallida");
        }
    }
}
