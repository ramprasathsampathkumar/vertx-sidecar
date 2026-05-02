package com.llmsidecar;

import com.llmsidecar.config.SidecarConfig;
import com.llmsidecar.observability.MetricsStore;
import com.llmsidecar.proxy.ProxyRouter;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainVerticle extends AbstractVerticle {

    private static final Logger log = LoggerFactory.getLogger(MainVerticle.class);

    @Override
    public void start(Promise<Void> startPromise) {
        loadConfig()
            .onSuccess(config -> startServer(config, startPromise))
            .onFailure(startPromise::fail);
    }

    private io.vertx.core.Future<SidecarConfig> loadConfig() {
        ConfigRetrieverOptions opts = new ConfigRetrieverOptions()
            .addStore(new ConfigStoreOptions()
                .setType("file")
                .setFormat("yaml")
                .setConfig(new JsonObject().put("path", "config/sidecar.yaml")))
            .addStore(new ConfigStoreOptions()
                .setType("env"));

        return ConfigRetriever.create(vertx, opts)
            .getConfig()
            .map(SidecarConfig::from);
    }

    private void startServer(SidecarConfig config, Promise<Void> startPromise) {
        MetricsStore metricsStore = new MetricsStore();
        vertx.createHttpServer()
            .requestHandler(ProxyRouter.create(vertx, config, metricsStore))
            .listen(config.port())
            .onSuccess(server -> {
                log.info("LLM Sidecar listening on :{}", server.actualPort());
                config.providers().forEach((name, p) ->
                    log.info("  {} {} -> {}", name, p.prefix(), p.upstream()));
                startPromise.complete();
            })
            .onFailure(startPromise::fail);
    }
}
