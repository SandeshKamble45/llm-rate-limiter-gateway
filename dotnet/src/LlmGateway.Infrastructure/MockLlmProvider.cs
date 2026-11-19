using LlmGateway.Application.Abstractions;
using LlmGateway.Application.Configuration;
using LlmGateway.Domain;
using Microsoft.Extensions.Options;

namespace LlmGateway.Infrastructure;

public sealed class MockLlmProvider : ILlmProvider
{
    private readonly GatewayOptions _options;
    private readonly ITokenCounter _tokenCounter;

    public MockLlmProvider(IOptions<GatewayOptions> options, ITokenCounter tokenCounter)
    {
        _options = options.Value;
        _tokenCounter = tokenCounter;
    }

    public string PrimaryModel => _options.PrimaryModel;

    public Task<LlmResponse> CompleteAsync(string model, string prompt, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        const string completion = "Mock response returned by LLM Gateway.";
        var input = Math.Max(1, _tokenCounter.CountTokens(prompt));
        var output = Math.Max(1, _tokenCounter.CountTokens(completion));
        return Task.FromResult(new LlmResponse(model, completion,
            new UsageMetadata(input, output, input + output)));
    }
}
