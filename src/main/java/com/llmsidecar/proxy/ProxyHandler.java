package com.llmsidecar.proxy;

import com.llmsidecar.config.ProviderConfig;
import com.llmsidecar.middleware.AuthInjectionMiddleware;
import com.llmsidecar.provider.ProviderRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class ProxyHandler {

    private static final Logger log = LoggerFactory.getLogger(ProxyHandler.class);

    // Headers that must not be forwarded between hops
    private static final Set<String> REQUEST_HOP_BY_HOP = Set.of(
        "host", "connection", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailers", "transfer-encoding",
        "upgrade", "authorization", "x-api-key"
    );

    private static final Set<String> RESPONSE_HOP_BY_HOP = Set.of(
        "connection", "keep-alive", "proxy-authenticate",
        "proxy-authorization", "te", "trailers", "transfer-encoding", "upgrade"
    );

    private final HttpClient httpClient;
    private final ProviderRegistry registry;

    public ProxyHandler(Vertx vertx, ProviderRegistry registry) {
        this.registry = registry;
        this.httpClient = vertx.createHttpClient(
            new HttpClientOptions()
                .setSsl(true)
                .setVerifyHost(true)
                .setTrustAll(false)
                .setConnectTimeout(5000)
                .setIdleTimeout(60)
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

        log.info("{} {} -> {}", incoming.method(), incoming.path(), upstreamUri);

        String authHeaderName = ctx.get(AuthInjectionMiddleware.CTX_AUTH_HEADER_NAME);
        String authHeaderValue = ctx.get(AuthInjectionMiddleware.CTX_AUTH_HEADER_VALUE);

        RequestOptions options = new RequestOptions()
            .setMethod(incoming.method())
            .setAbsoluteURI(upstreamUri);

        httpClient.request(options)
            .onSuccess(upstreamReq -> {
                // Forward safe headers from incoming request
                incoming.headers().forEach(h -> {
                    if (!REQUEST_HOP_BY_HOP.contains(h.getKey().toLowerCase())) {
                        upstreamReq.putHeader(h.getKey(), h.getValue());
                    }
                });

                // Inject provider auth
                if (authHeaderName != null) {
                    upstreamReq.putHeader(authHeaderName, authHeaderValue);
                }

                // Stream the request body directly to upstream and await response
                upstreamReq.send(incoming)
                    .onSuccess(upstreamResp -> {
                        ctx.response().setStatusCode(upstreamResp.statusCode());

                        // Forward safe response headers
                        upstreamResp.headers().forEach(h -> {
                            if (!RESPONSE_HOP_BY_HOP.contains(h.getKey().toLowerCase())) {
                                ctx.response().putHeader(h.getKey(), h.getValue());
                            }
                        });

                        // Pipe response body — handles both JSON and SSE streaming
                        upstreamResp.pipeTo(ctx.response())
                            .onFailure(err -> log.warn("Response pipe interrupted: {}", err.getMessage()));
                    })
                    .onFailure(err -> {
                        log.error("Upstream call failed for {}: {}", upstreamUri, err.getMessage());
                        if (!ctx.response().ended()) {
                            ctx.response().setStatusCode(502).end("Upstream error: " + err.getMessage());
                        }
                    });
            })
            .onFailure(err -> {
                log.error("Failed to open upstream connection to {}: {}", upstreamUri, err.getMessage());
                ctx.response().setStatusCode(502).end("Could not connect to upstream: " + err.getMessage());
            });
    }
}
