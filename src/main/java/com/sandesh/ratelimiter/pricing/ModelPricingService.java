package com.sandesh.ratelimiter.pricing;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ModelPricingService {

    private final Map<String, ModelPricing> pricingByModel =
            Map.of(
                    "primary-model",
                    new ModelPricing(
                            "primary-model",
                            15_000L,
                            60_000L),

                    "fallback-model",
                    new ModelPricing(
                            "fallback-model",
                            10_000L,
                            40_000L),

                    "gpt-5-mini",
                    new ModelPricing(
                            "gpt-5-mini",
                            250_000L,
                            2_000_000L)
            );

    public ModelPricing getPricing(String model) {
        ModelPricing pricing = pricingByModel.get(model);

        if (pricing == null) {
            throw new IllegalArgumentException(
                    "Unsupported model: " + model);
        }

        return pricing;
    }
}