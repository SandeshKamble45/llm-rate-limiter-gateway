using Xunit;
using LlmGateway.Application.Configuration;
using LlmGateway.Domain;
using LlmGateway.Infrastructure;
using Microsoft.Extensions.Options;
using StackExchange.Redis;
using Testcontainers.Redis;

namespace LlmGateway.IntegrationTests;

/// <summary>
/// Integration tests execute the production Lua scripts against an actual Redis server.
/// A Docker-compatible runtime is required.
/// </summary>
public sealed class RedisGatewayStateStoreTests : IAsyncLifetime
{
    private readonly RedisContainer _redis = new RedisBuilder("redis:7.4-alpine").Build();
    private IConnectionMultiplexer? _connection;
    private RedisGatewayStateStore _store = null!;

    public async ValueTask InitializeAsync()
    {
        await _redis.StartAsync();
        _connection = await ConnectionMultiplexer.ConnectAsync(_redis.GetConnectionString());
        _store = new RedisGatewayStateStore(_connection, Options.Create(new GatewayOptions
        {
            SlidingWindowSeconds = 60,
            SlidingWindowMaxRequests = 3,
            TokenBucketCapacity = 100,
            TokenBucketRefillRatePerSecond = 0.0001,
            DailyBudgetMicrodollars = 100,
            DailyKeyTtlSeconds = 93_600
        }));
    }

    public async ValueTask DisposeAsync()
    {
        _connection?.Dispose();
        await _redis.DisposeAsync();
    }

    [Fact]
    public async Task SlidingWindowRejectsTheFourthRequestInsideTheWindow()
    {
        var results = await Task.WhenAll(Enumerable.Range(0, 4)
            .Select(_ => _store.ConsumeSlidingWindowAsync("window-tenant", CancellationToken.None)));

        Assert.Equal(3, results.Count(x => x.Allowed));
        var rejected = Assert.Single(results, x => !x.Allowed);
        Assert.Equal("sliding-window", rejected.Algorithm);
        Assert.InRange(rejected.RetryAfterSeconds, 1, 60);
    }

    [Fact]
    public async Task TokenBucketConcurrentAdmissionsNeverExceedCapacity()
    {
        var results = await Task.WhenAll(Enumerable.Range(0, 20)
            .Select(_ => _store.ConsumeTokenBucketAsync("bucket-tenant", "primary-model", 10, CancellationToken.None)));

        Assert.Equal(10, results.Count(x => x.Allowed));
        Assert.All(results.Where(x => !x.Allowed), x => Assert.True(x.RetryAfterSeconds >= 1));
    }

    [Fact]
    public async Task ConcurrentBudgetReservationsCannotOverspendTenantBudget()
    {
        var datePrefix = DateTime.UtcNow.ToString("yyyyMMdd");
        var results = await Task.WhenAll(Enumerable.Range(0, 20)
            .Select(i => _store.ReserveBudgetAsync(
                "budget-tenant",
                $"{datePrefix}-{Guid.NewGuid():N}-{i}",
                10,
                CancellationToken.None)));

        Assert.Equal(10, results.Count(x => x.Allowed));
        var status = await _store.GetBudgetStatusAsync("budget-tenant", CancellationToken.None);
        Assert.Equal(100, status.SpentMicrodollars);
        Assert.Equal(0, status.RemainingMicrodollars);
    }

    [Fact]
    public async Task BudgetSettlementIsIdempotentForReservationIdentity()
    {
        var reservationId = $"{DateTime.UtcNow:yyyyMMdd}-{Guid.NewGuid():N}";
        var reservation = await _store.ReserveBudgetAsync(
            "settlement-tenant", reservationId, 40, CancellationToken.None);

        Assert.True(reservation.Allowed);
        Assert.Equal(40, reservation.SpentMicrodollars);

        var first = await _store.SettleBudgetAsync(
            "settlement-tenant", reservationId, 20, CancellationToken.None);
        var duplicate = await _store.SettleBudgetAsync(
            "settlement-tenant", reservationId, 20, CancellationToken.None);

        Assert.Equal(first, duplicate);
        Assert.Equal(20, first.SpentMicrodollars);
        Assert.Equal(-20, first.AdjustmentMicrodollars);

        var status = await _store.GetBudgetStatusAsync("settlement-tenant", CancellationToken.None);
        Assert.Equal(20, status.SpentMicrodollars);
        Assert.Equal(80, status.RemainingMicrodollars);
    }

    [Fact]
    public async Task TenantBudgetKeysRemainIsolated()
    {
        var datePrefix = DateTime.UtcNow.ToString("yyyyMMdd");
        await _store.ReserveBudgetAsync("tenant-alpha", $"{datePrefix}-{Guid.NewGuid():N}", 70, CancellationToken.None);
        await _store.ReserveBudgetAsync("tenant-beta", $"{datePrefix}-{Guid.NewGuid():N}", 30, CancellationToken.None);

        var alpha = await _store.GetBudgetStatusAsync("tenant-alpha", CancellationToken.None);
        var beta = await _store.GetBudgetStatusAsync("tenant-beta", CancellationToken.None);

        Assert.Equal(70, alpha.SpentMicrodollars);
        Assert.Equal(30, beta.SpentMicrodollars);
    }
}
