package com.idp.audit.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Reloj de test avanzable para probar la politica de anclaje por antiguedad. */
public final class MutableClock extends Clock {
    private volatile Instant now = Instant.now();

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    public void reset() {
        now = Instant.now();
    }
}
