package com.sandesh.ratelimiter.model;

public record TokenBucketStatus(
        double availableTokens,
        long capacity,
        double refillRatePerSecond) {
}