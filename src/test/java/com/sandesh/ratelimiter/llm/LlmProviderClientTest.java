package com.sandesh.ratelimiter.llm;

import com.sandesh.ratelimiter.model.UsageMetadata;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmProviderClientTest {

    @Test
    void shouldReturnUsageMetadataForPrimaryModel() {

        TokenCounter tokenCounter = new TokenCounter();

        LlmProviderClient client =
                new LlmProviderClient(tokenCounter);

        LlmProviderClient.LlmResponse response = null;

        /*
         * The mock provider intentionally has a 15% simulated
         * failure rate, so retry until a primary response succeeds.
         */
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                response =
                        client.callPrimaryModel(
                                "Explain Redis Lua atomicity");
                if (response != null
                        && "primary-model".equals(
                                response.modelUsed())) {
                    break;
                }
            } catch (RuntimeException ignored) {
                // Simulated provider failure.
            }
        }

        assertNotNull(response);
        assertEquals("primary-model", response.modelUsed());
        assertNotNull(response.usage());

        UsageMetadata usage = response.usage();

        assertTrue(usage.inputTokens() > 0);
        assertTrue(usage.outputTokens() > 0);
        assertEquals(
                usage.inputTokens() + usage.outputTokens(),
                usage.totalTokens());
    }

    @Test
    void fallbackShouldReturnFallbackModelUsage() {

        TokenCounter tokenCounter = new TokenCounter();

        LlmProviderClient client =
                new LlmProviderClient(tokenCounter);

        LlmProviderClient.LlmResponse response =
                client.fallbackToSecondaryModel(
                        "Explain token bucket",
                        new RuntimeException("provider failure"));

        assertEquals(
                "fallback-model",
                response.modelUsed());

        assertNotNull(response.usage());

        assertTrue(
                response.usage().inputTokens() > 0);

        assertTrue(
                response.usage().outputTokens() > 0);

        assertEquals(
                response.usage().inputTokens()
                        + response.usage().outputTokens(),
                response.usage().totalTokens());
    }
}