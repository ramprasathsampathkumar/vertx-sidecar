package com.llmsidecar.provider;

import com.llmsidecar.config.ProviderConfig;

import java.util.Map;
import java.util.Optional;

public class ProviderRegistry {

    private final Map<String, ProviderConfig> providers;

    public ProviderRegistry(Map<String, ProviderConfig> providers) {
        this.providers = providers;
    }

    public Optional<ProviderConfig> resolve(String requestPath) {
        return providers.values().stream()
            .filter(p -> requestPath.startsWith(p.prefix()))
            .findFirst();
    }

    public Map<String, ProviderConfig> all() {
        return providers;
    }
}
