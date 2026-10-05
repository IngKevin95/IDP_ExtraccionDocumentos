package com.idp.tenant.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Jobs asincronos: shredding de bajas vencidas (fase 2) y cierre de break-glass expirado. */
public final class ScheduledJobs {
    private ScheduledJobs() {}

    @Component
    @ConditionalOnProperty(name = "idp.tenant.offboarding.job-enabled", havingValue = "true", matchIfMissing = true)
    public static class OffboardingJob {
        private final OffboardingService offboarding;

        public OffboardingJob(OffboardingService offboarding) {
            this.offboarding = offboarding;
        }

        @Scheduled(fixedDelayString = "${idp.tenant.offboarding.job-interval-ms:3600000}")
        public void run() {
            offboarding.shredDueTenants();
        }
    }

    @Component
    @ConditionalOnProperty(name = "idp.tenant.breakglass.expiry-job-enabled", havingValue = "true",
            matchIfMissing = true)
    public static class BreakGlassExpiryJob {
        private final BreakGlassService breakGlass;

        public BreakGlassExpiryJob(BreakGlassService breakGlass) {
            this.breakGlass = breakGlass;
        }

        @Scheduled(fixedDelayString = "${idp.tenant.breakglass.expiry-job-interval-ms:30000}")
        public void run() {
            breakGlass.expireDue();
        }
    }
}
