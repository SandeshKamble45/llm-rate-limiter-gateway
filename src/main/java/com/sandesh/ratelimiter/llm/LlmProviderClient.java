package com.sandesh.ratelimiter.llm;

import com.sandesh.ratelimiter.model.UsageMetadata;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;

@Service
public class LlmProviderClient {

    private final TokenCounter tokenCounter;

    public LlmProviderClient(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    @CircuitBreaker(
            name = "primaryLlm",
            fallbackMethod = "fallbackToSecondaryModel")
    @Retry(name = "primaryLlm")
    public LlmResponse callPrimaryModel(String prompt) {

        // Simulate occasional primary-provider failures.
        if (Math.random() < 0.15) {
            throw new RuntimeException(
                    "Simulated primary provider failure");
        }

        String completion =
                "mock completion for: " + prompt;

        long inputTokens =
                Math.max(1, tokenCounter.countTokens(prompt));

        long outputTokens =
                Math.max(1, tokenCounter.countTokens(completion));

        UsageMetadata usage = new UsageMetadata(
                inputTokens,
                outputTokens,
                inputTokens + outputTokens
        );

        return new LlmResponse(
                "primary-model",
                completion,
                usage
        );
    }

    public LlmResponse fallbackToSecondaryModel(
            String prompt,
            Throwable throwable) {

        String completion =
                "fallback completion for: " + prompt;

        long inputTokens =
                Math.max(1, tokenCounter.countTokens(prompt));

        long outputTokens =
                Math.max(1, tokenCounter.countTokens(completion));

        UsageMetadata usage = new UsageMetadata(
                inputTokens,
                outputTokens,
                inputTokens + outputTokens
        );

        return new LlmResponse(
                "fallback-model",
                completion,
                usage
        );
    }

    public record LlmResponse(
            String modelUsed,
            String completion,
            UsageMetadata usage) {

        /*
         * Backward-compatible constructor for existing tests/callers.
         *
         * The old third argument represented total tokens.
         * New code should use the UsageMetadata constructor.
         */
        public LlmResponse(
                String modelUsed,
                String completion,
                int tokensUsed) {

            this(
                    modelUsed,
                    completion,
                    new UsageMetadata(
                            tokensUsed,
                            0,
                            tokensUsed)
            );
        }
    }
}