package com.sandesh.ratelimiter.quota;

public record BudgetSettlementResult(
        long reservedMicrodollars,
        long actualMicrodollars,
        long adjustmentMicrodollars,
        long spentMicrodollars,
        long remainingMicrodollars,
        boolean withinBudget) {
}