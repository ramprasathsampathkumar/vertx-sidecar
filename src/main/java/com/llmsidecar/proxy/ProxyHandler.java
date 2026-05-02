package com.llmsidecar.proxy;

import com.llmsidecar.config.ProviderConfig;
import com.llmsidecar.middleware.AuthInjectionMiddleware;
import com.llmsidecar.observability.CostEstimator;
import com.llmsidecar.observability.MetricsStore;
import com.llmsidecar.observability.RequestMetrics;
import com.llmsidecar.provider.ProviderRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class ProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyHandler.class);

    private static final Set<String> REQUEST_HOP_BY_HOP = Set.of(
        "host", "connection", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailers", "transfer-encoding",
        "upgrade", "authorization", "x-api-key", "content-length"
    );

    // content-length excluded — we set chunked instead, which works for both
    // streaming (SSE) and non-streaming responses without buffering the body.
    private static final Set<String> RESPONSE_HOP_BY_HOP = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade", "content-length"
    );

    private final HttpClient httpClient;
    private final ProviderRegistry registry;
    private final MetricsStore metricsStore;

    public ProxyHandler(Vertx vertx, ProviderRegistry registry, MetricsStore metricsStore) {
        this.registry = registry;
        this.metricsStore = metricsStore;
        // Force HTTP/1.1: Vert.x defaults to HTTP/2 for TLS connections,
        // but H2 → H1.1 body framing translation drops the response body.
        this.httpClient = vertx.createHttpClient(
            new HttpClientOptions()
                .setSsl(true)
                .setProtocolVersion(HttpVersion.HTTP_1_1)
                .setVerifyHost(true)
                .setTrustAll(false)
                .setConnectTimeout(5000)
                .setIdleTimeout(120)
        );
    }

    public void handle(RoutingContext ctx) {
        HttpServerRequest incoming = ctx.request();

        ProviderConfig provider = registry.resolve(incoming.path()).orElse(null);
        if (provider == null) {
            ctx.response().setStatusCode(404)
                .end("No provider matched path: " + incoming.path());
            return;
        }

        String upstreamPath = incoming.path().substring(provider.prefix().length());
        String query = incoming.query();
        String upstreamUri = provider.upstream() + upstreamPath + (query != null ? "?" + query : "");
        String traceId = Optional.ofNullable(incoming.getHeader("x-request-id"))
            .orElse(UUID.randomUUID().toString());

        log.info("{} {} -> {}", incoming.method(), incoming.path(), upstreamUri);

        String authHeaderName = ctx.get(AuthInjectionMiddleware.CTX_AUTH_HEADER_NAME);
        String authHeaderValue = ctx.get(AuthInjectionMiddleware.CTX_AUTH_HEADER_VALUE);

        // Buffer the request body before opening the upstream connection.
        // LLM request payloads are small JSON — the Vert.x router marks the
        // incoming request as ended before async handlers run, so piping the
        // raw stream would throw "Request has already been read".
        incoming.body()
            .onSuccess(body -> {
                String model = extractModel(body);
                long startMs = System.currentTimeMillis();

                RequestOptions options = new RequestOptions()
                    .setMethod(incoming.method())
                    .setAbsoluteURI(upstreamUri);

                httpClient.request(options)
                    .onSuccess(upstreamReq -> {
                        incoming.headers().forEach(h -> {
                            if (!REQUEST_HOP_BY_HOP.contains(h.getKey().toLowerCase())) {
                                upstreamReq.putHeader(h.getKey(), h.getValue());
                            }
                        });

                        if (authHeaderName != null) {
                            upstreamReq.putHeader(authHeaderName, authHeaderValue);
                        }
                        upstreamReq.putHeader("x-request-id", traceId);
                        String traceparent = incoming.getHeader("traceparent");
                        if (traceparent != null) {
                            upstreamReq.putHeader("traceparent", traceparent);
                        }

                        upstreamReq.send(body)
                            .onSuccess(upstreamResp -> {
                                ctx.response().setStatusCode(upstreamResp.statusCode());
                                upstreamResp.headers().forEach(h -> {
                                    if (!RESPONSE_HOP_BY_HOP.contains(h.getKey().toLowerCase())) {
                                        ctx.response().putHeader(h.getKey(), h.getValue());
                                    }
                                });

                                boolean isStreaming = Optional.ofNullable(upstreamResp.getHeader("content-type"))
                                    .map(ct -> ct.contains("text/event-stream"))
                                    .orElse(false);

                                if (isStreaming) {
                                    handleStreamingResponse(ctx, upstreamResp,
                                        provider.name(), model, traceId, startMs);
                                } else {
                                    handleJsonResponse(ctx, upstreamResp,
                                        provider.name(), model, traceId, startMs);
                                }
                            })
                            .onFailure(err -> {
                                log.error("Upstream call failed for {}: {}", upstreamUri, err.getMessage());
                                if (!ctx.response().ended()) {
                                    ctx.response().setStatusCode(502).end("Upstream error: " + err.getMessage());
                                }
                                recordMetrics(traceId, provider.name(), model, 502, false,
                                    0, 0, System.currentTimeMillis() - startMs, -1);
                            });
                    })
                    .onFailure(err -> {
                        log.error("Failed to open upstream connection to {}: {}", upstreamUri, err.getMessage());
                        ctx.response().setStatusCode(502).end("Could not connect to upstream: " + err.getMessage());
                        recordMetrics(traceId, provider.name(), model, 502, false,
                            0, 0, System.currentTimeMillis() - startMs, -1);
                    });
            })
            .onFailure(err -> {
                log.error("Failed to read request body: {}", err.getMessage());
                ctx.response().setStatusCode(400).end("Failed to read request body: " + err.getMessage());
            });
    }

    private void handleStreamingResponse(RoutingContext ctx, HttpClientResponse upstreamResp,
                                          String providerName, String model, String traceId, long startMs) {
        ctx.response().setChunked(true);
        // [0]=ttftMs, [1]=promptTokens, [2]=completionTokens
        long[] state = {-1L, 0L, 0L};

        upstreamResp.handler(chunk -> {
            ctx.response().write(chunk);
            String s = chunk.toString();

            // First non-empty content delta → TTFT
            if (state[0] < 0 && s.contains("\"content\":\"") && !s.contains("\"content\":\"\"")) {
                state[0] = System.currentTimeMillis() - startMs;
            }

            // Usage chunk (OpenAI sends this as the last data chunk before [DONE])
            if (s.contains("\"prompt_tokens\"")) {
                for (String line : s.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                        try {
                            JsonObject json = new JsonObject(line.substring(6));
                            JsonObject usage = json.getJsonObject("usage");
                            if (usage != null) {
                                state[1] = usage.getLong("prompt_tokens", 0L);
                                state[2] = usage.getLong("completion_tokens", 0L);
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        });

        upstreamResp.endHandler(v -> {
            ctx.response().end();
            recordMetrics(traceId, providerName, model, upstreamResp.statusCode(), true,
                state[1], state[2], System.currentTimeMillis() - startMs, state[0]);
        });

        upstreamResp.exceptionHandler(err -> {
            log.warn("Stream interrupted: {}", err.getMessage());
            if (!ctx.response().ended()) ctx.response().end();
        });
    }

    private void handleJsonResponse(RoutingContext ctx, HttpClientResponse upstreamResp,
                                     String providerName, String model, String traceId, long startMs) {
        upstreamResp.body()
            .onSuccess(body -> {
                long latencyMs = System.currentTimeMillis() - startMs;
                ctx.response().end(body);

                long promptTokens = 0, completionTokens = 0;
                try {
                    JsonObject json = new JsonObject(body);
                    JsonObject usage = json.getJsonObject("usage");
                    if (usage != null) {
                        promptTokens    = usage.getLong("prompt_tokens", 0L);
                        completionTokens = usage.getLong("completion_tokens", 0L);
                    }
                } catch (Exception ignored) {}

                recordMetrics(traceId, providerName, model, upstreamResp.statusCode(), false,
                    promptTokens, completionTokens, latencyMs, -1);
            })
            .onFailure(err -> {
                log.error("Failed to buffer upstream response: {}", err.getMessage());
                if (!ctx.response().ended()) {
                    ctx.response().setStatusCode(502).end("Upstream response error: " + err.getMessage());
                }
            });
    }

    private void recordMetrics(String traceId, String providerName, String model, int statusCode,
                                boolean streaming, long promptTokens, long completionTokens,
                                long latencyMs, long ttftMs) {
        double costUsd = CostEstimator.estimate(model, promptTokens, completionTokens);
        metricsStore.record(new RequestMetrics(
            traceId, providerName, model, statusCode, streaming,
            promptTokens, completionTokens, latencyMs, ttftMs, costUsd,
            System.currentTimeMillis()
        ));

        log.info("{}", new JsonObject()
            .put("event",            "llm_request")
            .put("traceId",          traceId)
            .put("provider",         providerName)
            .put("model",            model)
            .put("statusCode",       statusCode)
            .put("streaming",        streaming)
            .put("promptTokens",     promptTokens)
            .put("completionTokens", completionTokens)
            .put("totalTokens",      promptTokens + completionTokens)
            .put("latencyMs",        latencyMs)
            .put("ttftMs",           ttftMs)
            .put("estimatedCostUsd", costUsd)
            .encode());
    }

    private String extractModel(Buffer body) {
        try {
            return new JsonObject(body).getString("model", "unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
