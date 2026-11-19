using LlmGateway.Domain;

namespace LlmGateway.Application.Abstractions;

public interface IGatewayStateStore
{
    Task<RateLimitResult> ConsumeSlidingWindowAsync(string tenantId, CancellationToken cancellationToken);
    Task<RateLimitResult> ConsumeTokenBucketAsync(string tenantId, string model, long requestedTokens, CancellationToken cancellationToken);
    Task<TokenBucketStatus> GetTokenBucketStatusAsync(string tenantId, string model, CancellationToken cancellationToken);
    Task<SlidingWindowStatus> GetSlidingWindowStatusAsync(string tenantId, CancellationToken cancellationToken);
    Task<BudgetReservationResult> ReserveBudgetAsync(string tenantId, string reservationId, long requestedMicrodollars, CancellationToken cancellationToken);
    Task<BudgetSettlementResult> SettleBudgetAsync(string tenantId, string reservationId, long actualMicrodollars, CancellationToken cancellationToken);
    Task<BudgetReservationResult> GetBudgetStatusAsync(string tenantId, CancellationToken cancellationToken);
}

public interface ILlmProvider
{
    string PrimaryModel { get; }
    Task<LlmResponse> CompleteAsync(string model, string prompt, CancellationToken cancellationToken);
}

public interface ITokenCounter
{
    long CountTokens(string text);
}

public interface IModelPricingService
{
    ModelPricing GetPricing(string model);
}
