using LlmGateway.Application.Abstractions;
using LlmGateway.Domain;

namespace LlmGateway.Application.Services;

public sealed class ModelPricingService : IModelPricingService
{
    private static readonly IReadOnlyDictionary<string, ModelPricing> Prices =
        new Dictionary<string, ModelPricing>(StringComparer.Ordinal)
        {
            ["primary-model"] = new("primary-model", 15_000, 60_000),
            ["fallback-model"] = new("fallback-model", 10_000, 40_000),
            ["gpt-5-mini"] = new("gpt-5-mini", 250_000, 2_000_000)
        };

    public ModelPricing GetPricing(string model) =>
        Prices.TryGetValue(model, out var pricing)
            ? pricing
            : throw new ArgumentException($"Unsupported model: {model}", nameof(model));
}
