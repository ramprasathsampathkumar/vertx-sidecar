package com.llmsidecar.observability;

import java.util.Map;

public class CostEstimator {

    // [inputPer1M, outputPer1M] in USD — prices as of 2025
    private static final Map<String, double[]> PRICING = Map.ofEntries(
        Map.entry("gpt-4o",                     new double[]{ 2.50,  10.00}),
        Map.entry("gpt-4o-mini",                new double[]{ 0.15,   0.60}),
        Map.entry("gpt-4-turbo",                new double[]{10.00,  30.00}),
        Map.entry("gpt-3.5-turbo",              new double[]{ 0.50,   1.50}),
        Map.entry("o3",                         new double[]{10.00,  40.00}),
        Map.entry("o4-mini",                    new double[]{ 1.10,   4.40}),
        Map.entry("claude-opus-4-7",            new double[]{15.00,  75.00}),
        Map.entry("claude-sonnet-4-6",          new double[]{ 3.00,  15.00}),
        Map.entry("claude-haiku-4-5-20251001",  new double[]{ 0.80,   4.00}),
        Map.entry("claude-3-5-sonnet-20241022", new double[]{ 3.00,  15.00}),
        Map.entry("claude-3-5-haiku-20241022",  new double[]{ 0.80,   4.00}),
        Map.entry("claude-3-haiku-20240307",    new double[]{ 0.25,   1.25}),
        Map.entry("claude-3-opus-20240229",     new double[]{15.00,  75.00})
    );

    private static final double[] FALLBACK = {1.00, 3.00};

    public static double estimate(String model, long promptTokens, long completionTokens) {
        double[] p = PRICING.getOrDefault(model, FALLBACK);
        return (promptTokens * p[0] + completionTokens * p[1]) / 1_000_000.0;
    }
}
