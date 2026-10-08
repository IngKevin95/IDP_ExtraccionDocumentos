package com.idp.kms.aws;

import com.idp.kms.KeyService;
import com.idp.kms.contract.KeyServiceContract;
import com.idp.tenant.TenantId;
import java.util.Optional;

/** KeyServiceContract completa (incluida la firma Ed25519) contra el fake con criptografia real. */
class AwsKmsFakeContractTest extends KeyServiceContract {

    private final FakeAwsKmsApi api = new FakeAwsKmsApi();
    private final AwsKmsKeyService kms = new AwsKmsKeyService(api, 7);

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
        api.createKey(AwsKmsKeyService.alias(tenant, keyId), SIGNING_KEY.equals(keyId));
    }

    @Override
    protected Optional<Runnable> providerFailure() {
        return Optional.of(() -> api.failing = true);
    }
}
