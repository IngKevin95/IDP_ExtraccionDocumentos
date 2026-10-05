package com.idp.renderer.core;

import java.time.Duration;

/** Limite de tiempo de procesamiento cooperativo (se verifica entre paginas). */
public final class Deadline {

    private final long expiresAtNanos;

    public Deadline(Duration budget) {
        this.expiresAtNanos = System.nanoTime() + budget.toNanos();
    }

    public void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw RenderException.limitExceeded("Se excedio el tiempo maximo de procesamiento.");
        }
        if (System.nanoTime() - expiresAtNanos >= 0) {
            throw RenderException.limitExceeded("Se excedio el tiempo maximo de procesamiento.");
        }
    }
}
