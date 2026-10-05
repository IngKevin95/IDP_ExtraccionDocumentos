package com.idp.renderer.security;

public enum DocType {
    PDF("pdf"),
    DOCX("docx"),
    PNG("png"),
    JPEG("jpeg"),
    TIFF("tiff");

    private final String format;

    DocType(String format) {
        this.format = format;
    }

    public String format() {
        return format;
    }

    public boolean isImage() {
        return this == PNG || this == JPEG || this == TIFF;
    }
}
