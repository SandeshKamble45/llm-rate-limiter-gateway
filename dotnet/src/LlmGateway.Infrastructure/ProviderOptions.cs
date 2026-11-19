namespace LlmGateway.Infrastructure;

public sealed class ProviderOptions
{
    public const string SectionName = "Llm";
    public string OpenAiBaseUrl { get; init; } = "https://api.openai.com/v1/";
    public string? OpenAiApiKey { get; init; }
    public string OpenAiModel { get; init; } = "gpt-5-mini";
    public string? DemoAccessCode { get; init; }
}
