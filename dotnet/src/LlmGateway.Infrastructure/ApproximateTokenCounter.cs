using LlmGateway.Application.Abstractions;

namespace LlmGateway.Infrastructure;

/// <summary>
/// Temporary deterministic estimator. It is not an exact CL100K tokenizer.
/// Replace with a tokenizer validated against Java jtokkit test vectors before claiming parity.
/// </summary>
public sealed class ApproximateTokenCounter : ITokenCounter
{
    public long CountTokens(string text) =>
        string.IsNullOrWhiteSpace(text) ? 0 : Math.Max(1, (long)Math.Ceiling(text.Length / 4d));
}
