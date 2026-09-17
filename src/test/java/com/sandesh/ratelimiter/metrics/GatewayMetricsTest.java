package com.sandesh.ratelimiter.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMetricsTest {

    private MeterRegistry meterRegistry;
    private GatewayMetrics gatewayMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        gatewayMetrics = new GatewayMetrics(meterRegistry);
    }

    @Test
    void shouldRecordGatewayRequest() {
        gatewayMetrics.recordRequest();

        assertThat(
                meterRegistry.counter("gateway.requests").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldRecordRejectedRequest() {
        gatewayMetrics.recordRejectedRequest();

        assertThat(
                meterRegistry.counter("gateway.requests.rejected").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldRecordLlmCall() {
        gatewayMetrics.recordLlmCall();

        assertThat(
                meterRegistry.counter("gateway.llm.calls").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldRecordLlmFailure() {
        gatewayMetrics.recordLlmFailure();

        assertThat(
                meterRegistry.counter("gateway.llm.failures").count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldRecordTokens() {
        gatewayMetrics.recordTokens(19);

        assertThat(
                meterRegistry.counter("gateway.tokens").count())
                .isEqualTo(19.0);
    }

    @Test
    void shouldRecordActualCost() {
        gatewayMetrics.recordActualCost(2);

        assertThat(
                meterRegistry
                        .counter("gateway.actual.cost.microdollars")
                        .count())
                .isEqualTo(2.0);
    }

    @Test
    void shouldIgnoreNonPositiveTokenCount() {
        gatewayMetrics.recordTokens(0);
        gatewayMetrics.recordTokens(-5);

        assertThat(
                meterRegistry.counter("gateway.tokens").count())
                .isEqualTo(0.0);
    }

    @Test
    void shouldIgnoreNonPositiveCost() {
        gatewayMetrics.recordActualCost(0);
        gatewayMetrics.recordActualCost(-5);

        assertThat(
                meterRegistry
                        .counter("gateway.actual.cost.microdollars")
                        .count())
                .isEqualTo(0.0);
    }

    @Test
    void shouldRecordLlmLatency() {
        gatewayMetrics.recordLlmLatency(10_000_000);

        assertThat(
                meterRegistry
                        .timer("gateway.llm.latency")
                        .count())
                .isEqualTo(1);

        assertThat(
                meterRegistry
                        .timer("gateway.llm.latency")
                        .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(10.0);
    }
}