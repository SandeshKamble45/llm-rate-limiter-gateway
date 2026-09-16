package com.sandesh.ratelimiter.pricing;

public record ModelPricing(
        String model,
        long inputMicrodollarsPerMillionTokens,
        long outputMicrodollarsPerMillionTokens) {

    public long calculateInputCost(long inputTokens) {
        return calculateCost(
                inputTokens,
                inputMicrodollarsPerMillionTokens);
    }

    public long calculateOutputCost(long outputTokens) {
        return calculateCost(
                outputTokens,
                outputMicrodollarsPerMillionTokens);
    }

    private long calculateCost(
            long tokens,
            long microdollarsPerMillionTokens) {

        if (tokens <= 0) {
            return 0;
        }

        return Math.max(
                1,
                (tokens * microdollarsPerMillionTokens)
                        / 1_000_000L);
    }
}