package com.sandesh.ratelimiter.llm;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;

@Service
public class LlmProviderClient {

    private final LlmProvider primaryProvider;
    private final LlmProvider fallbackProvider;

    public LlmProviderClient(
            MockLlmProvider primaryProvider,
            FallbackLlmProvider fallbackProvider) {

        this.primaryProvider = primaryProvider;
        this.fallbackProvider = fallbackProvider;
    }

    @CircuitBreaker(
            name = "primaryLlm",
            fallbackMethod = "fallbackToSecondaryModel")
    @Retry(name = "primaryLlm")
    public LlmProvider.LlmResponse callPrimaryModel(String prompt) {

        return primaryProvider.complete(prompt);
    }

    public LlmProvider.LlmResponse fallbackToSecondaryModel(
            String prompt,
            Throwable throwable) {

        return fallbackProvider.complete(prompt);
    }
}