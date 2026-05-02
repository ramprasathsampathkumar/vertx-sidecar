package com.llmsidecar.proxy;

import com.llmsidecar.config.SidecarConfig;
import com.llmsidecar.middleware.AuthInjectionMiddleware;
import com.llmsidecar.provider.ProviderRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

public class ProxyRouter {

    public static Router create(Vertx vertx, SidecarConfig config) {
        Router router = Router.router(vertx);

        ProviderRegistry registry = new ProviderRegistry(config.providers());
        AuthInjectionMiddleware authMiddleware = new AuthInjectionMiddleware(registry);
        ProxyHandler proxyHandler = new ProxyHandler(vertx, registry);

        router.get("/health").handler(ctx -> {
            JsonArray providerNames = new JsonArray();
            config.providers().keySet().forEach(providerNames::add);
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(new JsonObject()
                    .put("status", "up")
                    .put("providers", providerNames)
                    .encode());
        });

        // Auth middleware runs first on all proxy paths (no body consumption)
        router.route("/*").handler(authMiddleware::handle);

        // One catch-all proxy route — provider resolved inside ProxyHandler
        config.providers().values().forEach(provider ->
            router.route(provider.prefix() + "/*").handler(proxyHandler::handle)
        );

        router.route().handler(ctx ->
            ctx.response().setStatusCode(404)
                .end("Unknown path: " + ctx.request().path())
        );

        return router;
    }
}
