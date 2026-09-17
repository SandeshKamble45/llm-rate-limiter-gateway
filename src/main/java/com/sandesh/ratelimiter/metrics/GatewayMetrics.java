package com.sandesh.ratelimiter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class GatewayMetrics {

    private final Counter requestsTotal;
    private final Counter requestsRejectedTotal;
    private final Counter llmCallsTotal;
    private final Counter llmFailuresTotal;
    private final Counter tokensTotal;
    private final Counter actualCostMicrodollarsTotal;
    private final Timer llmLatency;

    public GatewayMetrics(MeterRegistry meterRegistry) {

        /*
         * Do not include "_total" in the Micrometer counter name.
         * Prometheus adds the counter suffix automatically.
         */
        this.requestsTotal = Counter.builder("gateway.requests")
                .description("Total number of valid gateway chat requests")
                .register(meterRegistry);

        this.requestsRejectedTotal = Counter.builder("gateway.requests.rejected")
                .description("Total number of gateway requests rejected by rate limiting")
                .register(meterRegistry);

        this.llmCallsTotal = Counter.builder("gateway.llm.calls")
                .description("Total number of gateway LLM operations")
                .register(meterRegistry);

        this.llmFailuresTotal = Counter.builder("gateway.llm.failures")
                .description("Total number of LLM provider failures")
                .register(meterRegistry);

        this.tokensTotal = Counter.builder("gateway.tokens")
                .description("Total number of tokens processed by the gateway")
                .register(meterRegistry);

        this.actualCostMicrodollarsTotal = Counter.builder("gateway.actual.cost.microdollars")
                .description("Total actual LLM cost recorded by the gateway in microdollars")
                .register(meterRegistry);

        this.llmLatency = Timer.builder("gateway.llm.latency")
                .description("LLM provider operation latency")
                .register(meterRegistry);
    }

    public void recordRequest() {
        requestsTotal.increment();
    }

    public void recordRejectedRequest() {
        requestsRejectedTotal.increment();
    }

    public void recordLlmCall() {
        llmCallsTotal.increment();
    }

    public void recordLlmFailure() {
        llmFailuresTotal.increment();
    }

    public void recordTokens(long tokens) {
        if (tokens > 0) {
            tokensTotal.increment(tokens);
        }
    }

    public void recordActualCost(long microdollars) {
        if (microdollars > 0) {
            actualCostMicrodollarsTotal.increment(microdollars);
        }
    }

    public void recordLlmLatency(long durationNanos) {
        if (durationNanos >= 0) {
            llmLatency.record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }
}