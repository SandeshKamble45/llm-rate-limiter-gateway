package com.sandesh.ratelimiter.model;

public record UsageMetadata(
        long inputTokens,
        long outputTokens,
        long totalTokens) {
}