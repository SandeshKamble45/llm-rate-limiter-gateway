package com.sandesh.ratelimiter.llm;

import com.sandesh.ratelimiter.model.UsageMetadata;
import org.springframework.stereotype.Component;

@Component
public class FallbackLlmProvider implements LlmProvider {

    private final TokenCounter tokenCounter;

    public FallbackLlmProvider(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    @Override
    public LlmResponse complete(String prompt) {

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
}