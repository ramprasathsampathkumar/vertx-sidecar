package com.llmsidecar.observability;

public record RequestMetrics(
    String traceId,
    String provider,
    String model,
    int statusCode,
    boolean streaming,
    long promptTokens,
    long completionTokens,
    long latencyMs,
    long ttftMs,
    double estimatedCostUsd,
    long timestampEpochMs
) {
    public long totalTokens() { return promptTokens + completionTokens; }
    public boolean isError() { return statusCode >= 400; }
}
