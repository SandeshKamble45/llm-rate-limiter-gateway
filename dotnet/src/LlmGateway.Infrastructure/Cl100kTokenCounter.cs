using LlmGateway.Application.Abstractions;
using SharpToken;

namespace LlmGateway.Infrastructure;

/// <summary>
/// Uses the same CL100K_BASE encoding selected by the original Java jtokkit TokenCounter.
/// The encoding instance is reused across requests.
/// </summary>
public sealed class Cl100kTokenCounter : ITokenCounter
{
    private static readonly GptEncoding Encoding = GptEncoding.GetEncoding("cl100k_base");

    public long CountTokens(string text) =>
        string.IsNullOrWhiteSpace(text) ? 0 : Encoding.CountTokens(text);
}
