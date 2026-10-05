package com.idp.renderer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fail-closed al arranque: el renderer exige TLS (mTLS) salvo idp.security.dev-mode=true. */
@Component
public final class RendererTlsGuard {

    public RendererTlsGuard(@Value("${server.ssl.bundle:}") String bundle,
            @Value("${server.ssl.key-store:}") String keyStore,
            @Value("${server.ssl.enabled:true}") boolean sslEnabled,
            @Value("${idp.security.dev-mode:false}") boolean devMode) {
        validate(bundle, keyStore, sslEnabled, devMode);
    }

    static void validate(String bundle, String keyStore, boolean sslEnabled, boolean devMode) {
        if (devMode) {
            return;
        }
        boolean configured = sslEnabled && (!bundle.isBlank() || !keyStore.isBlank());
        if (!configured) {
            throw new IllegalStateException(
                "El renderer exige TLS: configure server.ssl.bundle (client-auth=need) o idp.security.dev-mode=true");
        }
    }
}
