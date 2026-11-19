using LlmGateway.Application.Abstractions;
using LlmGateway.Application.Configuration;
using LlmGateway.Domain;
using Microsoft.Extensions.Options;

namespace LlmGateway.Infrastructure;

public sealed class MockLlmProvider : ILlmProvider
{
    private readonly GatewayOptions _options;
    public MockLlmProvider(IOptions<GatewayOptions> options) => _options = options.Value;
    public string PrimaryModel => _options.PrimaryModel;

    public Task<LlmResponse> CompleteAsync(string model, string prompt, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        var completion = $"mock completion for: {prompt}";
        var input = Math.Max(1, TokenEstimator.Count(prompt));
        var output = Math.Max(1, TokenEstimator.Count(completion));
        return Task.FromResult(new LlmResponse(model,
            completion, new UsageMetadata(input, output, input + output)));
    }
}

/// <summary>
/// Conservative heuristic only. Exact CL100K parity is a separate migration requirement.
/// </summary>
internal static class TokenEstimator
{
    public static long Count(string text) =>
        string.IsNullOrWhiteSpace(text) ? 0 : Math.Max(1, (long)Math.Ceiling(text.Length / 4d));
}
