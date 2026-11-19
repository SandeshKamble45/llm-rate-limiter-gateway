using LlmGateway.Domain;

namespace LlmGateway.Infrastructure;

public sealed class FallbackLlmProvider
{
    public string PrimaryModel => "fallback-model";

    public Task<LlmResponse> CompleteAsync(string model, string prompt, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        const string completion = "Fallback response returned by LLM Gateway.";
        var input = Math.Max(1, (long)Math.Ceiling(prompt.Length / 4d));
        const long output = 10;
        return Task.FromResult(new LlmResponse(model, completion, new UsageMetadata(input, output, input + output)));
    }
}
