package com.sandesh.ratelimiter.llm;

import com.sandesh.ratelimiter.model.UsageMetadata;
import org.springframework.stereotype.Component;

@Component
public class MockLlmProvider implements LlmProvider {

    private static final String MODEL = "primary-model";

    private final TokenCounter tokenCounter;

    public MockLlmProvider(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    @Override
    public String getModel() {
        return MODEL;
    }

    @Override
    public LlmResponse complete(String prompt) {

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
                MODEL,
                completion,
                usage
        );
    }
}