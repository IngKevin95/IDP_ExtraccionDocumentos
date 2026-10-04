package com.idp.llm;

public interface LlmProvider {
    LlmResponse generate(LlmRequest request);
}
