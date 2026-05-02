package com.llmsidecar.config;

import io.vertx.core.json.JsonObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public record SidecarConfig(int port, Map<String, ProviderConfig> providers) {

    public static SidecarConfig from(JsonObject config) {
        int port = config.getJsonObject("server", new JsonObject()).getInteger("port", 8080);

        Map<String, ProviderConfig> providers = new HashMap<>();
        JsonObject providersJson = config.getJsonObject("providers", new JsonObject());
        providersJson.forEach(entry ->
            providers.put(entry.getKey(), ProviderConfig.from((JsonObject) entry.getValue()))
        );

        return new SidecarConfig(port, Collections.unmodifiableMap(providers));
    }
}
