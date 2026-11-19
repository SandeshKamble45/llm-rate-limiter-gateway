namespace LlmGateway.Application.Configuration;

public sealed class GatewayOptions
{
    public const string SectionName = "Gateway";
    public int MaxPromptLength { get; init; } = 10_000;
    public int MaxOutputTokens { get; init; } = 150;
    public long DailyBudgetMicrodollars { get; init; } = 5_000_000;
    public long DailyKeyTtlSeconds { get; init; } = 93_600;
    public long SlidingWindowSeconds { get; init; } = 60;
    public long SlidingWindowMaxRequests { get; init; } = 100;
    public long TokenBucketCapacity { get; init; } = 10_000;
    public double TokenBucketRefillRatePerSecond { get; init; } = 50;
    public string PrimaryModel { get; init; } = "primary-model";
    public string Provider { get; init; } = "mock";
}
