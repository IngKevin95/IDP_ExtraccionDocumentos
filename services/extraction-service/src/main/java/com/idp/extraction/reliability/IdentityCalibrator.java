package com.idp.extraction.reliability;

/** Calibracion identidad (por defecto, hasta contar con ajuste sobre el golden set). */
public final class IdentityCalibrator implements Calibrator {

    @Override
    public String id() {
        return "identity";
    }

    @Override
    public double calibrate(String fieldType, double raw) {
        return Math.max(0.0, Math.min(1.0, raw));
    }
}
