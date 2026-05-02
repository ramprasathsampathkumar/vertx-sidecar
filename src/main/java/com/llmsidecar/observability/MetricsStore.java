package com.llmsidecar.observability;

import java.util.*;

// All writes come from the Vert.x event loop (single-threaded per verticle);
// reads from the /metrics handler also run on the same event loop — no locking needed.
public class MetricsStore {

    private static final int MAX_RECENT = 100;
    private static final int MAX_LATENCY_SAMPLES = 1000;

    private final Map<String, ModelAggregate> aggregates = new LinkedHashMap<>();
    private final ArrayDeque<RequestMetrics> recent = new ArrayDeque<>();
    private final Map<String, ArrayDeque<Long>> latencyBuckets = new HashMap<>();

    public void record(RequestMetrics m) {
        String key = m.provider() + "::" + m.model();
        aggregates.computeIfAbsent(key, k -> new ModelAggregate()).add(m);

        recent.addLast(m);
        if (recent.size() > MAX_RECENT) recent.removeFirst();

        ArrayDeque<Long> bucket = latencyBuckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        bucket.addLast(m.latencyMs());
        if (bucket.size() > MAX_LATENCY_SAMPLES) bucket.removeFirst();
    }

    public Map<String, ModelAggregate> aggregates() {
        return Collections.unmodifiableMap(aggregates);
    }

    public List<RequestMetrics> recent() {
        return new ArrayList<>(recent);
    }

    public long[] percentiles(String key) {
        ArrayDeque<Long> bucket = latencyBuckets.getOrDefault(key, new ArrayDeque<>());
        if (bucket.isEmpty()) return new long[]{0, 0, 0};
        long[] sorted = bucket.stream().mapToLong(Long::longValue).sorted().toArray();
        return new long[]{
            percentile(sorted, 50),
            percentile(sorted, 95),
            percentile(sorted, 99)
        };
    }

    private long percentile(long[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, idx)];
    }

    public static class ModelAggregate {
        long requests;
        long promptTokens;
        long completionTokens;
        long errors;
        long latencySum;
        double costUsd;

        void add(RequestMetrics m) {
            requests++;
            promptTokens += m.promptTokens();
            completionTokens += m.completionTokens();
            if (m.isError()) errors++;
            latencySum += m.latencyMs();
            costUsd += m.estimatedCostUsd();
        }

        public long requests() { return requests; }
        public long promptTokens() { return promptTokens; }
        public long completionTokens() { return completionTokens; }
        public long errors() { return errors; }
        public long avgLatencyMs() { return requests == 0 ? 0 : latencySum / requests; }
        public double costUsd() { return costUsd; }
    }
}
