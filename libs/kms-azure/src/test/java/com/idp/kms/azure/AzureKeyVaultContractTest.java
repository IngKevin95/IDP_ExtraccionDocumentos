package com.idp.kms.azure;

import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.kms.contract.KeyServiceContract;
import com.idp.tenant.TenantId;
import java.util.Optional;

/** KeyServiceContract contra el adaptador con Key Vault falso (criptografia local real). */
class AzureKeyVaultContractTest extends KeyServiceContract {

    private final FakeKeyVaultApi fake = new FakeKeyVaultApi();
    private final AzureKeyVaultKeyService kms = new AzureKeyVaultKeyService(fake);

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
        String name = KeyNames.hashed(tenant, keyId);
        if (SIGNING_KEY.equals(keyId)) {
            fake.provisionEc(name);
        } else {
            fake.provisionRsa(name);
        }
    }

    @Override
    protected Optional<Runnable> providerFailure() {
        createKeyIfNeeded(getTenantA(), "datos");
        return Optional.of(() -> fake.failure = new IllegalStateException("caida simulada"));
    }
}
