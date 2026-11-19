using System.Net;
using LlmGateway.Domain;
using Polly;
using Polly.CircuitBreaker;
using Polly.Timeout;

namespace LlmGateway.Infrastructure;

public sealed class ProviderRateLimitedException(string message) : Exception(message);

public sealed class ProviderResiliencePipeline
{
    private readonly ResiliencePipeline<LlmResponse> _pipeline;
    private readonly CircuitBreakerStateProvider _stateProvider = new();
    private readonly object _gate = new();
    private readonly Queue<(DateTimeOffset At, bool Failed)> _outcomes = new();

    public ProviderResiliencePipeline()
    {
        var retry = new RetryStrategyOptions<LlmResponse>
        {
            MaxRetryAttempts = 1,
            Delay = TimeSpan.FromMilliseconds(200),
            BackoffType = DelayBackoffType.Exponential,
            UseJitter = true,
            // Only retry an explicit provider 429 rejection. A generic timeout or 5xx
            // after a POST has ambiguous billing semantics and is deliberately not retried.
            ShouldHandle = new PredicateBuilder<LlmResponse>()
                .Handle<ProviderRateLimitedException>()
        };

        var circuitBreaker = new CircuitBreakerStrategyOptions<LlmResponse>
        {
            FailureRatio = 0.40,
            SamplingDuration = TimeSpan.FromSeconds(30),
            MinimumThroughput = 20,
            BreakDuration = TimeSpan.FromSeconds(10),
            StateProvider = _stateProvider,
            ShouldHandle = new PredicateBuilder<LlmResponse>()
                .Handle<HttpRequestException>()
                .Handle<ProviderRateLimitedException>()
                .Handle<TimeoutRejectedException>()
        };

        _pipeline = new ResiliencePipelineBuilder<LlmResponse>()
            .AddRetry(retry)
            .AddCircuitBreaker(circuitBreaker)
            .AddTimeout(TimeSpan.FromSeconds(40))
            .Build();
    }

    public async Task<LlmResponse> ExecuteAsync(
        Func<CancellationToken, Task<LlmResponse>> operation,
        CancellationToken cancellationToken)
    {
        try
        {
            var result = await _pipeline.ExecuteAsync(
                async token => await operation(token).ConfigureAwait(false),
                cancellationToken);
            RecordOutcome(failed: false);
            return result;
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            RecordOutcome(failed: true);
            throw;
        }
    }

    public CircuitBreakerStatus GetStatus()
    {
        lock (_gate)
        {
            PruneOutcomes(DateTimeOffset.UtcNow);
            var failed = _outcomes.Count(x => x.Failed);
            var failureRate = _outcomes.Count == 0 ? -1f : (float)failed / _outcomes.Count * 100f;
            var state = _stateProvider.CircuitState.ToString().ToUpperInvariant();
            return new CircuitBreakerStatus("primaryLlm", state, failureRate, _outcomes.Count, failed);
        }
    }

    private void RecordOutcome(bool failed)
    {
        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow;
            PruneOutcomes(now);
            _outcomes.Enqueue((now, failed));
        }
    }

    private void PruneOutcomes(DateTimeOffset now)
    {
        while (_outcomes.Count > 0 && now - _outcomes.Peek().At > TimeSpan.FromSeconds(30))
            _outcomes.Dequeue();
    }
}
