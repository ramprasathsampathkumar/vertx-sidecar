package com.llmsidecar.observability;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.util.Map;

public class MetricsFormatter {

    public static String toPrometheus(MetricsStore store) {
        StringBuilder sb = new StringBuilder();
        appendMeta(sb, "llm_requests_total",           "counter", "Total LLM requests by provider and model");
        appendMeta(sb, "llm_errors_total",             "counter", "Total LLM request errors");
        appendMeta(sb, "llm_prompt_tokens_total",      "counter", "Total prompt tokens consumed");
        appendMeta(sb, "llm_completion_tokens_total",  "counter", "Total completion tokens generated");
        appendMeta(sb, "llm_cost_usd_total",           "counter", "Estimated cost in USD");
        appendMeta(sb, "llm_avg_latency_ms",           "gauge",   "Average request latency ms");
        appendMeta(sb, "llm_p50_latency_ms",           "gauge",   "P50 request latency ms");
        appendMeta(sb, "llm_p95_latency_ms",           "gauge",   "P95 request latency ms");
        appendMeta(sb, "llm_p99_latency_ms",           "gauge",   "P99 request latency ms");

        for (Map.Entry<String, MetricsStore.ModelAggregate> e : store.aggregates().entrySet()) {
            String[] parts = e.getKey().split("::", 2);
            String provider = parts[0];
            String model = parts.length > 1 ? parts[1] : "unknown";
            String lbl = String.format("{provider=\"%s\",model=\"%s\"}", provider, model);
            MetricsStore.ModelAggregate a = e.getValue();
            long[] p = store.percentiles(e.getKey());

            sb.append("llm_requests_total").append(lbl).append(" ").append(a.requests()).append("\n");
            sb.append("llm_errors_total").append(lbl).append(" ").append(a.errors()).append("\n");
            sb.append("llm_prompt_tokens_total").append(lbl).append(" ").append(a.promptTokens()).append("\n");
            sb.append("llm_completion_tokens_total").append(lbl).append(" ").append(a.completionTokens()).append("\n");
            sb.append(String.format("llm_cost_usd_total%s %.6f%n", lbl, a.costUsd()));
            sb.append("llm_avg_latency_ms").append(lbl).append(" ").append(a.avgLatencyMs()).append("\n");
            sb.append("llm_p50_latency_ms").append(lbl).append(" ").append(p[0]).append("\n");
            sb.append("llm_p95_latency_ms").append(lbl).append(" ").append(p[1]).append("\n");
            sb.append("llm_p99_latency_ms").append(lbl).append(" ").append(p[2]).append("\n");
        }

        return sb.toString();
    }

    public static JsonObject toJson(MetricsStore store) {
        long totalRequests = 0, totalPrompt = 0, totalCompletion = 0, totalErrors = 0;
        double totalCost = 0;
        JsonObject byModel = new JsonObject();

        for (Map.Entry<String, MetricsStore.ModelAggregate> e : store.aggregates().entrySet()) {
            String[] parts = e.getKey().split("::", 2);
            String model = parts.length > 1 ? parts[1] : "unknown";
            MetricsStore.ModelAggregate a = e.getValue();
            long[] p = store.percentiles(e.getKey());

            totalRequests  += a.requests();
            totalPrompt    += a.promptTokens();
            totalCompletion+= a.completionTokens();
            totalErrors    += a.errors();
            totalCost      += a.costUsd();

            byModel.put(model, new JsonObject()
                .put("requests",        a.requests())
                .put("promptTokens",    a.promptTokens())
                .put("completionTokens",a.completionTokens())
                .put("estimatedCostUsd",a.costUsd())
                .put("errors",          a.errors())
                .put("avgLatencyMs",    a.avgLatencyMs())
                .put("p50LatencyMs",    p[0])
                .put("p95LatencyMs",    p[1])
                .put("p99LatencyMs",    p[2])
            );
        }

        JsonArray recentArr = new JsonArray();
        for (RequestMetrics r : store.recent()) {
            recentArr.add(new JsonObject()
                .put("traceId",          r.traceId())
                .put("timestamp",        Instant.ofEpochMilli(r.timestampEpochMs()).toString())
                .put("provider",         r.provider())
                .put("model",            r.model())
                .put("statusCode",       r.statusCode())
                .put("streaming",        r.streaming())
                .put("promptTokens",     r.promptTokens())
                .put("completionTokens", r.completionTokens())
                .put("latencyMs",        r.latencyMs())
                .put("ttftMs",           r.ttftMs())
                .put("estimatedCostUsd", r.estimatedCostUsd())
            );
        }

        return new JsonObject()
            .put("totals", new JsonObject()
                .put("requests",        totalRequests)
                .put("promptTokens",    totalPrompt)
                .put("completionTokens",totalCompletion)
                .put("estimatedCostUsd",totalCost)
                .put("errors",          totalErrors))
            .put("byModel",        byModel)
            .put("recentRequests", recentArr);
    }

    private static void appendMeta(StringBuilder sb, String name, String type, String help) {
        sb.append("# HELP ").append(name).append(" ").append(help).append("\n");
        sb.append("# TYPE ").append(name).append(" ").append(type).append("\n");
    }
}
