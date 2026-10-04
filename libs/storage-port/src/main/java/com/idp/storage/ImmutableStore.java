package com.idp.storage;

import com.idp.tenant.TenantId;
import java.io.InputStream;
import java.time.Duration;

public interface ImmutableStore extends ObjectStore {
    void putWithRetention(TenantId tenantId, String path, InputStream data, ObjectMetadata metadata, Duration retention);
    void applyLegalHold(TenantId tenantId, String path);
    void removeLegalHold(TenantId tenantId, String path);
}
