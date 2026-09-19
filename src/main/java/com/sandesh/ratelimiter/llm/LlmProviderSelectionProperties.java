package com.sandesh.ratelimiter.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProviderSelectionProperties {

    private String provider = "mock";

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public boolean isOpenAi() {
        return "openai".equalsIgnoreCase(provider);
    }
}
