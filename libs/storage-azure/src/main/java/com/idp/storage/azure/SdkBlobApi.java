package com.idp.storage.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobImmutabilityPolicy;
import com.azure.storage.blob.models.BlobImmutabilityPolicyMode;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlockBlobCommitBlockListOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
import com.idp.storage.ObjectStore.StorageException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** {@link BlobApi} sobre {@link BlobServiceClient}. */
public final class SdkBlobApi implements BlobApi {

    static final int BLOCK_SIZE = 4 * 1024 * 1024;

    /** Limite de Azure para blobs de bloques: 50 000 bloques. */
    private static final long MAX_BLOCKS = 50_000;

    private final BlobServiceClient service;
    private final BlobImmutabilityPolicyMode mode;
    private final long maxObjectBytes;

    public SdkBlobApi(BlobServiceClient service, BlobImmutabilityPolicyMode mode, long maxObjectBytes) {
        if (maxObjectBytes <= 0 || maxObjectBytes > MAX_BLOCKS * BLOCK_SIZE) {
            throw new IllegalArgumentException("idp.storage.max-object-bytes debe estar entre 1 y "
                + MAX_BLOCKS * BLOCK_SIZE);
        }
        this.service = service;
        this.mode = mode;
        this.maxObjectBytes = maxObjectBytes;
    }

    /**
     * Sube en bloques y confirma solo si el SHA-256 y la longitud coinciden con lo declarado. Blob no verifica
     * SHA-256 en el servidor (solo MD5): el hash cubre lo leido en el cliente, no lo almacenado. Se valida antes
     * de confirmar porque un blob con retencion no se podria corregir ni borrar despues. Aborta en cuanto el
     * flujo supera la longitud declarada o el tope configurado.
     */
    @Override
    public void write(String container, String blob, InputStream data, long length, String contentType,
                      byte[] sha256, Instant retainUntil) {
        if (length > maxObjectBytes) {
            throw new StorageException("El objeto supera el tope de " + maxObjectBytes + " bytes");
        }
        BlockBlobClient client = service.getBlobContainerClient(container).getBlobClient(blob).getBlockBlobClient();
        MessageDigest digest = newSha256();
        List<String> ids = new ArrayList<>();
        long total = 0;
        try {
            byte[] chunk = data.readNBytes(BLOCK_SIZE);
            while (chunk.length > 0) {
                total += chunk.length;
                if (total > length) {
                    throw new StorageException("El contenido supera la longitud declarada");
                }
                digest.update(chunk);
                String id = Base64.getEncoder().encodeToString(
                    String.format("%08d", ids.size()).getBytes(StandardCharsets.US_ASCII));
                client.stageBlock(id, BinaryData.fromBytes(chunk));
                ids.add(id);
                chunk = data.readNBytes(BLOCK_SIZE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (total != length) {
            throw new StorageException("La longitud declarada es distinta de la recibida");
        }
        if (sha256 != null && !MessageDigest.isEqual(sha256, digest.digest())) {
            throw new StorageException("El SHA-256 declarado es distinto del contenido recibido");
        }
        BlockBlobCommitBlockListOptions options = new BlockBlobCommitBlockListOptions(ids);
        if (contentType != null) {
            options.setHeaders(new BlobHttpHeaders().setContentType(contentType));
        }
        if (retainUntil != null) {
            options.setImmutabilityPolicy(new BlobImmutabilityPolicy().setPolicyMode(mode)
                .setExpiryTime(retainUntil.atOffset(ZoneOffset.UTC)));
        }
        client.commitBlockListWithResponse(options, null, null);
    }

    @Override
    public InputStream read(String container, String blob) {
        try {
            return service.getBlobContainerClient(container).getBlobClient(blob).openInputStream();
        } catch (BlobStorageException e) {
            throw translate(e);
        }
    }

    @Override
    public void delete(String container, String blob) {
        service.getBlobContainerClient(container).getBlobClient(blob).deleteIfExists();
    }

    /** El legal hold de Blob aplica a la version actual; no se puede dirigir a una version anterior. */
    @Override
    public void setLegalHold(String container, String blob, boolean hold) {
        try {
            service.getBlobContainerClient(container).getBlobClient(blob).setLegalHold(hold);
        } catch (BlobStorageException e) {
            throw translate(e);
        }
    }

    @Override
    public boolean versionLevelWormEnabled(String container) {
        return Boolean.TRUE.equals(
            service.getBlobContainerClient(container).getProperties().isImmutableStorageWithVersioningEnabled());
    }

    private static RuntimeException translate(BlobStorageException e) {
        return BlobErrorCode.BLOB_NOT_FOUND.equals(e.getErrorCode()) ? new NotFoundException() : e;
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
