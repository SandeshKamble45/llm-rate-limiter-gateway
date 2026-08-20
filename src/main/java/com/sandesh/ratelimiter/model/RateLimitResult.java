package com.sandesh.ratelimiter.model;

public record RateLimitResult(boolean allowed, double remainingTokens, String algorithm) {
}
