package com.idp.notification.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Reloj controlable por las pruebas (backoff, expiracion de secretos, ventana anti-replay). */
public final class MutableClock extends Clock {

    private volatile Instant now = Instant.parse("2026-10-05T12:00:00Z");

    public void advance(Duration d) {
        now = now.plus(d);
    }

    public void set(Instant i) {
        now = i;
    }

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
}
