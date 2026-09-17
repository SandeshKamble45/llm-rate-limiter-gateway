package com.sandesh.ratelimiter.model;

public record SlidingWindowStatus(
        long currentRequests,
        long maxRequests,
        long windowSeconds,
        long remainingRequests) {
}