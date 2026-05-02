package com.llmsidecar.config;

import io.vertx.core.json.JsonObject;

public record ProviderConfig(
    String name,
    String upstream,
    String prefix,
    String authHeader,
    String authPrefix,
    String apiKeyEnvVar
) {
    public static ProviderConfig from(String name, JsonObject json) {
        return new ProviderConfig(
            name,
            json.getString("upstream"),
            json.getString("prefix"),
            json.getString("authHeader"),
            json.getString("authPrefix", ""),
            json.getString("apiKeyEnvVar")
        );
    }

    public String resolveApiKey() {
        return System.getenv(apiKeyEnvVar);
    }
}
