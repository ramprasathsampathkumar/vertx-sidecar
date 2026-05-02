package com.llmsidecar.middleware;

import com.llmsidecar.config.ProviderConfig;
import com.llmsidecar.provider.ProviderRegistry;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthInjectionMiddleware {

    private static final Logger log = LoggerFactory.getLogger(AuthInjectionMiddleware.class);

    public static final String CTX_AUTH_HEADER_NAME = "sidecar.authHeaderName";
    public static final String CTX_AUTH_HEADER_VALUE = "sidecar.authHeaderValue";

    private final ProviderRegistry registry;

    public AuthInjectionMiddleware(ProviderRegistry registry) {
        this.registry = registry;
    }

    public void handle(RoutingContext ctx) {
        registry.resolve(ctx.request().path()).ifPresentOrElse(
            provider -> injectAuth(ctx, provider),
            ctx::next
        );
    }

    private void injectAuth(RoutingContext ctx, ProviderConfig provider) {
        String apiKey = provider.resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No API key found for env var '{}' (provider prefix: {})",
                provider.apiKeyEnvVar(), provider.prefix());
        } else {
            ctx.put(CTX_AUTH_HEADER_NAME, provider.authHeader());
            ctx.put(CTX_AUTH_HEADER_VALUE, provider.authPrefix() + apiKey);
        }
        ctx.next();
    }
}
