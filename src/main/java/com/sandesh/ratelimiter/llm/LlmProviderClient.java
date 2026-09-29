package com.sandesh.ratelimiter.llm;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LlmProviderClient {

    private final LlmProvider primaryProvider;
    private final MockLlmProvider mockLlmProvider;
    private final OpenAiLlmProvider openAiLlmProvider;
    private final FallbackLlmProvider fallbackProvider;

    @Autowired
    public LlmProviderClient(
            MockLlmProvider mockLlmProvider,
            OpenAiLlmProvider openAiLlmProvider,
            FallbackLlmProvider fallbackProvider,
            LlmProviderSelectionProperties properties) {

        this.mockLlmProvider = mockLlmProvider;
        this.openAiLlmProvider = openAiLlmProvider;

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
        this.mockLlmProvider = mockLlmProvider;
        this.openAiLlmProvider = null;
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

    /*
     * Routes a request to the explicitly selected model.
     *
     * The provider call remains protected by the existing
     * retry + circuit breaker configuration.
     */
    @CircuitBreaker(
            name = "primaryLlm",
            fallbackMethod = "fallbackToSelectedModel")
    @Retry(name = "primaryLlm")
    public LlmProvider.LlmResponse callModel(
            String model,
            String prompt) {

        if (mockLlmProvider.getModel().equals(model)) {
            return mockLlmProvider.complete(prompt);
        }

        if (openAiLlmProvider != null
                && openAiLlmProvider.getModel().equals(model)) {
            return openAiLlmProvider.complete(prompt);
        }

        throw new IllegalArgumentException(
                "Unsupported model: " + model);
    }

    public LlmProvider.LlmResponse fallbackToSecondaryModel(
            String prompt,
            Throwable throwable) {

        return fallbackProvider.complete(prompt);
    }

    public LlmProvider.LlmResponse fallbackToSelectedModel(
            String model,
            String prompt,
            Throwable throwable) {

        return fallbackProvider.complete(prompt);
    }
}