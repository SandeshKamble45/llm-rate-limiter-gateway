package com.sandesh.ratelimiter.model;

public record CircuitBreakerStatus(
        String name,
        String state,
        float failureRate,
        int bufferedCalls,
        int failedCalls) {
}