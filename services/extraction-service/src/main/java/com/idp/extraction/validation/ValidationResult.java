package com.idp.extraction.validation;

/** Resultado estructurado de un validador: pasa, falla (con causa) o no aplica. */
public record ValidationResult(Status status, String code, String detail) {

    public enum Status { PASS, FAIL, NOT_APPLICABLE }

    private static final ValidationResult PASS = new ValidationResult(Status.PASS, "OK", "");
    private static final ValidationResult NA = new ValidationResult(Status.NOT_APPLICABLE, "NA", "");

    public static ValidationResult pass() {
        return PASS;
    }

    public static ValidationResult notApplicable() {
        return NA;
    }

    public static ValidationResult fail(String code, String detail) {
        return new ValidationResult(Status.FAIL, code, detail);
    }

    public boolean failed() {
        return status == Status.FAIL;
    }

    public boolean passed() {
        return status == Status.PASS;
    }
}
