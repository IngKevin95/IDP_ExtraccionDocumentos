package com.idp.chat.infra;

import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Tokens del LLM consumidos por el tenant del contexto (cada silo es de un tenant) por dia UTC (SEC-049, ADR 0023). */
@Repository
public class TokenUsageRepository {

    private final JdbcTemplate jdbc;

    public TokenUsageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** Tokens (entrada + salida) consumidos hoy. */
    public long usedToday() {
        Long n = jdbc.query("select tokens_in + tokens_out from chat_token_usage where day = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, today());
        return n == null ? 0 : n;
    }

    /** Suma atomica al contador del dia (se ejecuta en la transaccion que persiste la respuesta). */
    public void add(long tokensIn, long tokensOut) {
        if (tokensIn <= 0 && tokensOut <= 0) {
            return;
        }
        jdbc.update("insert into chat_token_usage (day, tokens_in, tokens_out) values (?, ?, ?) "
                + "on conflict (day) do update set tokens_in = chat_token_usage.tokens_in + excluded.tokens_in, "
                + "tokens_out = chat_token_usage.tokens_out + excluded.tokens_out", today(), Math.max(0, tokensIn),
                Math.max(0, tokensOut));
    }
}
