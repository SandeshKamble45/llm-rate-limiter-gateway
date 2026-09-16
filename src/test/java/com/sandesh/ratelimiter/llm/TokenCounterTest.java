package com.sandesh.ratelimiter.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCounterTest {

    private final TokenCounter tokenCounter =
            new TokenCounter();

    @Test
    void shouldReturnZeroForNullText() {
        assertEquals(
                0,
                tokenCounter.countTokens(null)
        );
    }

    @Test
    void shouldReturnZeroForBlankText() {
        assertEquals(
                0,
                tokenCounter.countTokens("   ")
        );
    }

    @Test
    void shouldCountTokensForNormalText() {
        int tokens =
                tokenCounter.countTokens("Hello gateway");

        assertTrue(tokens > 0);
    }

    @Test
    void shouldCountTokensForLongerText() {
        int shortText =
                tokenCounter.countTokens("Hello");

        int longerText =
                tokenCounter.countTokens(
                        "Hello gateway, this is a longer prompt."
                );

        assertTrue(longerText > shortText);
    }
}