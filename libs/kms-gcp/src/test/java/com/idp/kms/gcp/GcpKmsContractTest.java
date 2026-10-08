package com.idp.kms.gcp;

import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.kms.contract.KeyServiceContract;
import com.idp.tenant.TenantId;
import java.util.Optional;

/** KeyServiceContract contra el fake con criptografia real (no hay emulador de Cloud KMS, spike T-00). */
class GcpKmsContractTest extends KeyServiceContract {

    static final String RING = "projects/p/locations/l/keyRings/r";

    private final FakeGcpKmsApi fake = new FakeGcpKmsApi();
    private final GcpKmsKeyService kms = new GcpKmsKeyService(fake, "p", "l", "r");

    static String cryptoKey(TenantId tenant, String keyId) {
        return RING + "/cryptoKeys/" + KeyNames.hashed(tenant, keyId).substring(0, 63);
    }

    @Override
    protected KeyService getKms() {
        return kms;
    }

    @Override
    protected TenantId getTenantA() {
        return new TenantId("t1");
    }

    @Override
    protected TenantId getTenantB() {
        return new TenantId("t2");
    }

    @Override
    protected void createKeyIfNeeded(TenantId tenant, String keyId) {
        if (keyId.equals(SIGNING_KEY)) {
            fake.createSigningKey(cryptoKey(tenant, keyId));
        } else {
            fake.createEncryptionKey(cryptoKey(tenant, keyId));
        }
    }

    @Override
    protected Optional<Runnable> providerFailure() {
        return Optional.of(() -> fake.failure = new IllegalStateException("UNAVAILABLE"));
    }
}
