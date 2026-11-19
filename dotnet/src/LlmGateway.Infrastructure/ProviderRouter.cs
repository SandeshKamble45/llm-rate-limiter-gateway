using LlmGateway.Application.Abstractions;
using LlmGateway.Application.Configuration;
using LlmGateway.Domain;
using Microsoft.Extensions.Options;

namespace LlmGateway.Infrastructure;

public sealed class ProviderRouter : ILlmProvider
{
    private readonly GatewayOptions _gateway;
    private readonly MockLlmProvider _mock;
    private readonly OpenAiLlmProvider _openAi;
    private readonly FallbackLlmProvider _fallback;
    private readonly ProviderCircuitState _circuit;

    public ProviderRouter(
        IOptions<GatewayOptions> gateway,
        MockLlmProvider mock,
        OpenAiLlmProvider openAi,
        FallbackLlmProvider fallback,
        ProviderCircuitState circuit)
    {
        _gateway = gateway.Value;
        _mock = mock;
        _openAi = openAi;
        _fallback = fallback;
        _circuit = circuit;
    }

    public string PrimaryModel => string.Equals(_gateway.Provider, "openai", StringComparison.OrdinalIgnoreCase)
        ? _openAi.PrimaryModel
        : _mock.PrimaryModel;

    public async Task<LlmResponse> CompleteAsync(string model, string prompt, CancellationToken cancellationToken)
    {
        if (string.Equals(model, _mock.PrimaryModel, StringComparison.Ordinal))
            return await _mock.CompleteAsync(model, prompt, cancellationToken);

        if (string.Equals(model, _openAi.PrimaryModel, StringComparison.Ordinal))
        {
            try
            {
                var result = await _openAi.CompleteAsync(model, prompt, cancellationToken);
                _circuit.RecordSuccess();
                return result;
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception)
            {
                _circuit.RecordFailure();
                return await _fallback.CompleteAsync(_fallback.PrimaryModel, prompt, cancellationToken);
            }
        }

        throw new ArgumentException($"Unsupported model: {model}", nameof(model));
    }
}
