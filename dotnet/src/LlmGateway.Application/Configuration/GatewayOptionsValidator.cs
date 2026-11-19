using Microsoft.Extensions.Options;

namespace LlmGateway.Application.Configuration;

public sealed class GatewayOptionsValidator : IValidateOptions<GatewayOptions>
{
    public ValidateOptionsResult Validate(string? name, GatewayOptions options)
    {
        var errors = new List<string>();
        if (options.MaxPromptLength <= 0) errors.Add("Gateway:MaxPromptLength must be positive.");
        if (options.MaxOutputTokens <= 0) errors.Add("Gateway:MaxOutputTokens must be positive.");
        if (options.DailyBudgetMicrodollars <= 0) errors.Add("Gateway:DailyBudgetMicrodollars must be positive.");
        if (options.DailyKeyTtlSeconds < 86_400) errors.Add("Gateway:DailyKeyTtlSeconds must be at least 86400.");
        if (options.SlidingWindowSeconds <= 0 || options.SlidingWindowMaxRequests <= 0)
            errors.Add("Sliding window duration and request limit must be positive.");
        if (options.TokenBucketCapacity <= 0 || options.TokenBucketRefillRatePerSecond <= 0)
            errors.Add("Token bucket capacity and refill rate must be positive.");
        if (string.IsNullOrWhiteSpace(options.PrimaryModel)) errors.Add("Gateway:PrimaryModel is required.");
        if (options.Provider is not ("mock" or "openai")) errors.Add("Gateway:Provider must be 'mock' or 'openai'.");
        return errors.Count == 0 ? ValidateOptionsResult.Success : ValidateOptionsResult.Fail(errors);
    }
}
