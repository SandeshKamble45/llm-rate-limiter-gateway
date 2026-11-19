using System.Diagnostics.Metrics;

namespace LlmGateway.Application.Services;

/// <summary>
/// Low-cardinality business metrics for gateway admission, provider execution and billing.
/// Do not add tenant IDs or prompt text as metric labels.
/// </summary>
public sealed class GatewayMetrics : IDisposable
{
    public const string MeterName = "LlmGateway.Metrics";
    private readonly Meter _meter = new(MeterName, "1.0.0");
    private readonly Counter<long> _requests;
    private readonly Counter<long> _rejectedRequests;
    private readonly Counter<long> _providerCalls;
    private readonly Counter<long> _providerFailures;
    private readonly Counter<long> _tokens;
    private readonly Counter<long> _actualCostMicrodollars;
    private readonly Histogram<double> _providerLatencyMs;

    public GatewayMetrics()
    {
        _requests = _meter.CreateCounter<long>("gateway.requests", description: "Valid chat requests admitted to the gateway pipeline.");
        _rejectedRequests = _meter.CreateCounter<long>("gateway.requests.rejected", description: "Requests rejected by rate limiting.");
        _providerCalls = _meter.CreateCounter<long>("gateway.llm.calls", description: "LLM provider operations.");
        _providerFailures = _meter.CreateCounter<long>("gateway.llm.failures", description: "LLM provider operations that failed without a fallback response.");
        _tokens = _meter.CreateCounter<long>("gateway.tokens", description: "Provider-reported tokens processed.");
        _actualCostMicrodollars = _meter.CreateCounter<long>("gateway.actual.cost.microdollars", description: "Actual LLM cost in integer microdollars.");
        _providerLatencyMs = _meter.CreateHistogram<double>("gateway.llm.latency", "ms", "End-to-end provider operation latency.");
    }

    public void RecordRequest() => _requests.Add(1);
    public void RecordRejectedRequest(string algorithm) =>
        _rejectedRequests.Add(1, new KeyValuePair<string, object?>("algorithm", algorithm));
    public void RecordProviderCall() => _providerCalls.Add(1);
    public void RecordProviderFailure() => _providerFailures.Add(1);
    public void RecordTokens(long tokens)
    {
        if (tokens > 0) _tokens.Add(tokens);
    }
    public void RecordActualCost(long microdollars)
    {
        if (microdollars > 0) _actualCostMicrodollars.Add(microdollars);
    }
    public void RecordProviderLatency(double milliseconds) =>
        _providerLatencyMs.Record(Math.Max(0, milliseconds));

    public void Dispose() => _meter.Dispose();
}
