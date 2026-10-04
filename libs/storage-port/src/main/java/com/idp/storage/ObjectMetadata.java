package com.idp.storage;

public record ObjectMetadata(
    String sha256,
    long length,
    String contentType
) {}
