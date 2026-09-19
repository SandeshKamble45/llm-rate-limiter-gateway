package com.sandesh.ratelimiter.llm;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LlmProviderClient {

    private final LlmProvider primaryProvider;
    private final FallbackLlmProvider fallbackProvider;

    @Autowired
    public LlmProviderClient(
            MockLlmProvider mockLlmProvider,
            OpenAiLlmProvider openAiLlmProvider,
            FallbackLlmProvider fallbackProvider,
            LlmProviderSelectionProperties properties) {

        this.primaryProvider =
                properties.isOpenAi()
                        ? openAiLlmProvider
                        : mockLlmProvider;

        this.fallbackProvider = fallbackProvider;
    }

    /*
     * Backward-compatible constructor for existing unit tests
     * and direct callers that explicitly provide the mock provider.
     */
    public LlmProviderClient(
            MockLlmProvider mockLlmProvider,
            FallbackLlmProvider fallbackProvider) {

        this.primaryProvider = mockLlmProvider;
        this.fallbackProvider = fallbackProvider;
    }

    public String getPrimaryModel() {
        return primaryProvider.getModel();
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
