package com.sandesh.ratelimiter.llm;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Wraps calls to an LLM provider with a circuit breaker: if the primary
 * provider/model starts erroring or timing out repeatedly, the breaker trips
 * open and every subsequent call goes straight to the fallback (a cheaper or
 * backup model) instead of hammering a provider that's already struggling.
 * This is the pattern real gateways (TrueFoundry, Zuplo, etc.) call out as
 * the difference between "one bad request" and "cascading outage".
 */
@Service
public class LlmProviderClient {

    private final RestTemplate restTemplate = new RestTemplate();

    // primaryModel="gpt-4o" style call. Swap in a real HTTP call to
    // OpenAI/Anthropic here once you're ready; keep a mock for local dev/tests
    // so you're not burning API credits during load tests.
    @CircuitBreaker(name = "primaryLlm", fallbackMethod = "fallbackToSecondaryModel")
    @Retry(name = "primaryLlm")
    public LlmResponse callPrimaryModel(String prompt) {
        // TODO: replace with real provider call, e.g.:
        // restTemplate.postForObject("https://api.openai.com/v1/chat/completions", ...)
        if (Math.random() < 0.15) {
            // simulate provider errors/timeouts so you can actually watch
            // the breaker trip during your load test — don't skip this,
            // it's the demo moment that makes the project convincing
            throw new RuntimeException("Simulated primary provider failure");
        }
        int simulatedTokens = estimateTokens(prompt);
        return new LlmResponse("primary-model", "mock completion for: " + prompt, simulatedTokens);
    }

    // Called automatically by Resilience4j when the breaker is open or
    // callPrimaryModel throws after retries are exhausted.
    public LlmResponse fallbackToSecondaryModel(String prompt, Throwable t) {
        int simulatedTokens = estimateTokens(prompt);
        return new LlmResponse("fallback-model", "fallback completion for: " + prompt, simulatedTokens / 2);
    }

    private int estimateTokens(String prompt) {
        // Rough placeholder: ~4 chars/token in English. Swap for a real
        // tokenizer (jtokkit is a good Java tiktoken port) before you demo this.
        return Math.max(1, prompt.length() / 4);
    }

    public record LlmResponse(String modelUsed, String completion, int tokensUsed) {}
}
