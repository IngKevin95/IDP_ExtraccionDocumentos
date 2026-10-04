package com.idp.storage;

import com.idp.tenant.TenantId;
import java.io.InputStream;

public interface ObjectStore {
    class StorageException extends RuntimeException {
        public StorageException(String message) { super(message); }
    }
    
    void put(TenantId tenantId, String path, InputStream data, ObjectMetadata metadata);
    InputStream get(TenantId tenantId, String path);
    void delete(TenantId tenantId, String path);
}
