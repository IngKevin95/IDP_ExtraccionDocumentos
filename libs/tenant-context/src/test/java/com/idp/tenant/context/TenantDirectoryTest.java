package com.idp.tenant.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TenantDirectoryTest {

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    };

    @Test
    void cachesWithinTtlAndRefreshesAfter() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(eq(JdbcTenantDirectory.SQL), eq(String.class))).thenReturn(List.of("a"), List.of("a", "b"));
        JdbcTenantDirectory dir = new JdbcTenantDirectory(jdbc, Duration.ofSeconds(30), clock);
        assertEquals(List.of("a"), dir.activeTenants());
        now = now.plusSeconds(10);
        assertEquals(List.of("a"), dir.activeTenants());
        verify(jdbc, times(1)).queryForList(eq(JdbcTenantDirectory.SQL), eq(String.class));
        now = now.plusSeconds(60);
        assertEquals(List.of("a", "b"), dir.activeTenants());
    }

    @Test
    void keepsLastKnownListWhenControlDbFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(any(String.class), eq(String.class))).thenReturn(List.of("a"))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        JdbcTenantDirectory dir = new JdbcTenantDirectory(jdbc, Duration.ofSeconds(1), clock);
        assertEquals(List.of("a"), dir.activeTenants());
        now = now.plusSeconds(5);
        assertEquals(List.of("a"), dir.activeTenants());
    }

    @Test
    void staticDirectoryParsesCsv() {
        assertEquals(List.of("x", "y"), StaticTenantDirectory.fromCsv("x, y").activeTenants());
        assertEquals(List.of(), StaticTenantDirectory.fromCsv("").activeTenants());
    }
}
