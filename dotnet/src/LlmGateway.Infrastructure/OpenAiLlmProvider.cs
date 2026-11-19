using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using LlmGateway.Application.Abstractions;
using LlmGateway.Application.Configuration;
using LlmGateway.Domain;
using Microsoft.Extensions.Options;

namespace LlmGateway.Infrastructure;

public sealed class OpenAiLlmProvider : ILlmProvider
{
    private readonly HttpClient _httpClient;
    private readonly ProviderOptions _provider;
    private readonly GatewayOptions _gateway;

    public OpenAiLlmProvider(HttpClient httpClient, IOptions<ProviderOptions> provider, IOptions<GatewayOptions> gateway)
    {
        _httpClient = httpClient;
        _provider = provider.Value;
        _gateway = gateway.Value;
        _httpClient.BaseAddress = new Uri(_provider.OpenAiBaseUrl, UriKind.Absolute);
        _httpClient.Timeout = Timeout.InfiniteTimeSpan; // resilience handler owns timeout policy
    }

    public string PrimaryModel => _provider.OpenAiModel;

    public async Task<LlmResponse> CompleteAsync(string model, string prompt, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(_provider.OpenAiApiKey))
            throw new InvalidOperationException("Llm:OpenAiApiKey is not configured.");

        using var request = new HttpRequestMessage(HttpMethod.Post, "responses");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _provider.OpenAiApiKey);
        request.Content = JsonContent.Create(new
        {
            model,
            input = prompt,
            // The original Java implementation reserved against 150 output tokens but did not
            // enforce this limit at the provider. The .NET implementation makes that invariant real.
            max_output_tokens = _gateway.MaxOutputTokens
        });

        using var response = await _httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();

        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
        var root = document.RootElement;

        var completion = ReadOutputText(root);
        var usage = root.TryGetProperty("usage", out var usageElement)
            ? usageElement
            : default;

        var inputTokens = ReadTokenCount(usage, "input_tokens");
        var outputTokens = ReadTokenCount(usage, "output_tokens");

        // The local estimator is a fallback only; exact CL100K tokenizer parity is still pending.
        if (inputTokens <= 0) inputTokens = Math.Max(1, TokenEstimator.Count(prompt));
        if (outputTokens <= 0) outputTokens = Math.Max(1, TokenEstimator.Count(completion));

        return new LlmResponse(model, completion,
            new UsageMetadata(inputTokens, outputTokens, inputTokens + outputTokens));
    }

    private static long ReadTokenCount(JsonElement usage, string property) =>
        usage.ValueKind == JsonValueKind.Object &&
        usage.TryGetProperty(property, out var value) &&
        value.TryGetInt64(out var count) ? count : 0;

    private static string ReadOutputText(JsonElement root)
    {
        if (root.TryGetProperty("output_text", out var flat) && flat.ValueKind == JsonValueKind.String)
            return flat.GetString() ?? string.Empty;

        if (root.TryGetProperty("output", out var output) && output.ValueKind == JsonValueKind.Array)
        {
            var parts = new List<string>();
            foreach (var item in output.EnumerateArray())
            {
                if (!item.TryGetProperty("content", out var content) || content.ValueKind != JsonValueKind.Array)
                    continue;
                foreach (var part in content.EnumerateArray())
                    if (part.TryGetProperty("text", out var text) && text.ValueKind == JsonValueKind.String)
                        parts.Add(text.GetString() ?? string.Empty);
            }
            if (parts.Count > 0) return string.Concat(parts);
        }

        throw new InvalidOperationException("Provider response did not contain output text.");
    }
}
