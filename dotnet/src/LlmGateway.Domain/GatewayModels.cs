namespace LlmGateway.Domain;

public sealed record UsageMetadata(long InputTokens, long OutputTokens, long TotalTokens);

public sealed record LlmResponse(string ModelUsed, string Completion, UsageMetadata Usage);

public sealed record RateLimitResult(bool Allowed, double RemainingTokens, string Algorithm, long RetryAfterSeconds);

public sealed record TokenBucketStatus(double AvailableTokens, long Capacity, double RefillRatePerSecond);

public sealed record SlidingWindowStatus(long CurrentRequests, long MaxRequests, long WindowSeconds, long RemainingRequests);

public sealed record BudgetReservationResult(bool Allowed, long SpentMicrodollars, long RemainingMicrodollars, bool InProgress = false, bool AlreadySettled = false);

public sealed record BudgetSettlementResult(
    long ReservedMicrodollars,
    long ActualMicrodollars,
    long AdjustmentMicrodollars,
    long SpentMicrodollars,
    long RemainingMicrodollars,
    bool WithinBudget);

public sealed record GatewayResponse(
    LlmResponse Response,
    long EstimatedCostMicrodollars,
    long ActualCostMicrodollars,
    BudgetSettlementResult Settlement,
    long LatencyMs);

public sealed record GatewayErrorResponse(
    string Message,
    long ReleasedReservationMicrodollars,
    BudgetSettlementResult Settlement);

public sealed record GatewayStatusResponse(
    string TenantId,
    TokenBucketStatus TokenBucket,
    SlidingWindowStatus SlidingWindow,
    BudgetReservationResult DailyBudget,
    CircuitBreakerStatus CircuitBreaker);

public sealed record CircuitBreakerStatus(
    string Name,
    string State,
    float FailureRate,
    int BufferedCalls,
    int FailedCalls);

public sealed record ChatRequest(string TenantId, string Prompt, string? Model);

public sealed record ModelPricing(
    string Model,
    long InputMicrodollarsPerMillionTokens,
    long OutputMicrodollarsPerMillionTokens)
{
    public long CalculateInputCost(long inputTokens) =>
        CalculateCost(inputTokens, InputMicrodollarsPerMillionTokens);

    public long CalculateOutputCost(long outputTokens) =>
        CalculateCost(outputTokens, OutputMicrodollarsPerMillionTokens);

    private static long CalculateCost(long tokens, long rate)
    {
        if (tokens <= 0) return 0;
        return Math.Max(1, checked(tokens * rate) / 1_000_000L);
    }
}
