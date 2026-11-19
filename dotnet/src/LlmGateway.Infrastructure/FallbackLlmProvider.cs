using LlmGateway.Application.Abstractions;
using LlmGateway.Domain;

namespace LlmGateway.Infrastructure;

public sealed class FallbackLlmProvider(ITokenCounter tokenCounter)
{
    public string PrimaryModel => "fallback-model";

    public Task<LlmResponse> CompleteAsync(string model, string prompt, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        const string completion = "Fallback response returned by LLM Gateway.";
        var input = Math.Max(1, tokenCounter.CountTokens(prompt));
        var output = Math.Max(1, tokenCounter.CountTokens(completion));
        return Task.FromResult(new LlmResponse(model, completion,
            new UsageMetadata(input, output, input + output)));
    }
}
