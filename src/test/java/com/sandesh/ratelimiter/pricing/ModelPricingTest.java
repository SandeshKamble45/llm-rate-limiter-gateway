package com.sandesh.ratelimiter.pricing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelPricingTest {

    private final ModelPricing pricing =
            new ModelPricing(
                    "test-model",
                    15_000L,
                    60_000L);

    @Test
    void shouldCalculateInputCost() {
        assertEquals(
                15_000L,
                pricing.calculateInputCost(1_000_000));
    }

    @Test
    void shouldCalculateOutputCost() {
        assertEquals(
                60_000L,
                pricing.calculateOutputCost(1_000_000));
    }

    @Test
    void shouldReturnZeroForZeroTokens() {
        assertEquals(
                0,
                pricing.calculateInputCost(0));

        assertEquals(
                0,
                pricing.calculateOutputCost(0));
    }

    @Test
    void shouldReturnZeroForNegativeTokens() {
        assertEquals(
                0,
                pricing.calculateInputCost(-100));

        assertEquals(
                0,
                pricing.calculateOutputCost(-100));
    }

    @Test
    void shouldRoundTinyPositiveCostUpToOneMicrodollar() {
        assertEquals(
                1,
                pricing.calculateInputCost(1));
    }

    @Test
    void shouldRejectUnsupportedModel() {
        ModelPricingService service =
                new ModelPricingService();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.getPricing("unknown-model"));
    }

    @Test
    void shouldReturnConfiguredPricing() {
        ModelPricingService service =
                new ModelPricingService();

        ModelPricing result =
                service.getPricing("primary-model");

        assertEquals(
                "primary-model",
                result.model());

        assertEquals(
                15_000L,
                result.inputMicrodollarsPerMillionTokens());

        assertEquals(
                60_000L,
                result.outputMicrodollarsPerMillionTokens());
    }
}