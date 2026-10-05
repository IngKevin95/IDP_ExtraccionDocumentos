package com.idp.extraction.llm;

import com.idp.llm.LlmResponse;
import com.idp.tenant.TenantId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Acumulador de una ejecucion: tokens consumidos, versiones de modelo y huellas de prompts usados. */
public final class ExtractionRun {

    private final TenantId tenant;
    private int tokensIn;
    private int tokensOut;
    private final Set<String> models = new TreeSet<>();
    private final List<String> promptHashes = new ArrayList<>();
    private int llmCalls;

    public ExtractionRun(TenantId tenant) {
        this.tenant = tenant;
    }

    public TenantId tenant() {
        return tenant;
    }

    void record(String promptHash, LlmResponse response) {
        llmCalls++;
        promptHashes.add(promptHash);
        if (response.modelVersion() != null) {
            models.add(response.modelVersion());
        }
        if (response.usage() != null) {
            tokensIn += Math.max(0, response.usage().inputTokens());
            tokensOut += Math.max(0, response.usage().outputTokens());
        }
    }

    public int tokensIn() {
        return tokensIn;
    }

    public int tokensOut() {
        return tokensOut;
    }

    public int llmCalls() {
        return llmCalls;
    }

    public java.util.SortedSet<String> modelVersions() {
        return java.util.Collections.unmodifiableSortedSet(new TreeSet<>(models));
    }

    public List<String> promptHashes() {
        return List.copyOf(promptHashes);
    }
}
