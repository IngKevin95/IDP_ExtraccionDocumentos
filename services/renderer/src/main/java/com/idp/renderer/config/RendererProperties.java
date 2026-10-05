package com.idp.renderer.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("renderer")
public record RendererProperties(
        @DefaultValue Clamav clamav,
        @DefaultValue Limits limits,
        @DefaultValue Libreoffice libreoffice,
        @DefaultValue Raster raster) {

    public record Clamav(
            @DefaultValue("127.0.0.1") String host,
            @DefaultValue("3310") int port,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("60s") Duration readTimeout) {}

    public record Limits(
            @DefaultValue("52428800") long maxFileBytes,
            @DefaultValue("100") int maxPages,
            @DefaultValue("268435456") long maxUncompressedBytes,
            @DefaultValue("25000000") long maxPixelsPerPage,
            @DefaultValue("120s") Duration renderTimeout,
            @DefaultValue("4") int maxConcurrent) {}

    public record Libreoffice(
            @DefaultValue("soffice") String binary,
            @DefaultValue("30s") Duration timeout) {}

    public record Raster(@DefaultValue("150") int dpi) {}
}
