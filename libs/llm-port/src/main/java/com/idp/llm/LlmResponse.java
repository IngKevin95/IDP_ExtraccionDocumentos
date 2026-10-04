package com.idp.llm;

public record LlmResponse(
    String content,
    String modelVersion,
    String finishReason,
    Usage usage
) {
    public record Usage(int inputTokens, int outputTokens) {}
}
