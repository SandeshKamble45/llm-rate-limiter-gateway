using System.Globalization;
using System.Reflection;
using LlmGateway.Application.Abstractions;
using LlmGateway.Application.Configuration;
using LlmGateway.Domain;
using Microsoft.Extensions.Options;
using StackExchange.Redis;

namespace LlmGateway.Infrastructure;

public sealed class RedisGatewayStateStore : IGatewayStateStore
{
    private const string DailyDateFormat = "yyyyMMdd";
    private readonly IDatabase _database;
    private readonly GatewayOptions _options;
    private readonly IReadOnlyDictionary<string, string> _scripts;

    public RedisGatewayStateStore(IConnectionMultiplexer connection, IOptions<GatewayOptions> options)
    {
        _database = connection.GetDatabase();
        _options = options.Value;
        _scripts = LoadScripts();
    }

    public async Task<RateLimitResult> ConsumeSlidingWindowAsync(string tenantId, CancellationToken cancellationToken)
    {
        var key = SlidingWindowKey(tenantId);
        var result = await _database.ScriptEvaluateAsync(
            _scripts["sliding_window.lua"],
            [key],
            [_options.SlidingWindowSeconds, _options.SlidingWindowMaxRequests, Guid.NewGuid().ToString("N")])
            .WaitAsync(cancellationToken);

        var values = (RedisResult[])result!;
        return new RateLimitResult(
            (long)values[0] == 1,
            Math.Max(0, _options.SlidingWindowMaxRequests - (long)values[1]),
            "sliding-window",
            (long)values[2]);
    }

    public async Task<RateLimitResult> ConsumeTokenBucketAsync(
        string tenantId, string model, long requestedTokens, CancellationToken cancellationToken)
    {
        if (requestedTokens <= 0)
            throw new ArgumentOutOfRangeException(nameof(requestedTokens), "Requested token cost must be positive.");
        if (requestedTokens > _options.TokenBucketCapacity)
            throw new ArgumentOutOfRangeException(nameof(requestedTokens), "Requested token cost cannot exceed bucket capacity.");

        var result = await _database.ScriptEvaluateAsync(
            _scripts["token_bucket.lua"],
            [TokenBucketKey(tenantId, model)],
            [_options.TokenBucketCapacity, _options.TokenBucketRefillRatePerSecond, requestedTokens])
            .WaitAsync(cancellationToken);

        var values = (RedisResult[])result!;
        return new RateLimitResult(
            (long)values[0] == 1,
            (double)values[1],
            "token-bucket",
            (long)values[2]);
    }

    public async Task<TokenBucketStatus> GetTokenBucketStatusAsync(
        string tenantId, string model, CancellationToken cancellationToken)
    {
        var result = await _database.ScriptEvaluateAsync(
            _scripts["token_bucket_status.lua"],
            [TokenBucketKey(tenantId, model)],
            [_options.TokenBucketCapacity, _options.TokenBucketRefillRatePerSecond])
            .WaitAsync(cancellationToken);

        var values = (RedisResult[])result!;
        return new TokenBucketStatus(
            (double)values[0],
            _options.TokenBucketCapacity,
            _options.TokenBucketRefillRatePerSecond);
    }

    public async Task<SlidingWindowStatus> GetSlidingWindowStatusAsync(
        string tenantId, CancellationToken cancellationToken)
    {
        var result = await _database.ScriptEvaluateAsync(
            _scripts["sliding_window_status.lua"],
            [SlidingWindowKey(tenantId)],
            [_options.SlidingWindowSeconds, _options.SlidingWindowMaxRequests])
            .WaitAsync(cancellationToken);

        var values = (RedisResult[])result!;
        return new SlidingWindowStatus(
            (long)values[0], (long)values[1], (long)values[2], (long)values[3]);
    }

    public async Task<BudgetReservationResult> ReserveBudgetAsync(
        string tenantId, string reservationId, long requestedMicrodollars, CancellationToken cancellationToken)
    {
        if (requestedMicrodollars <= 0)
            throw new ArgumentOutOfRangeException(nameof(requestedMicrodollars));

        var (budgetKey, reservationKey) = BudgetKeys(tenantId, reservationId);
        var result = await _database.ScriptEvaluateAsync(
            _scripts["budget_reservation.lua"],
            [budgetKey, reservationKey],
            [requestedMicrodollars, _options.DailyBudgetMicrodollars, _options.DailyKeyTtlSeconds])
            .WaitAsync(cancellationToken);

        var values = (RedisResult[])result!;
        var state = (long)values[0];
        var spent = (long)values[1];
        return new BudgetReservationResult(
            state == 1,
            spent,
            Math.Max(0, _options.DailyBudgetMicrodollars - spent),
            InProgress: state == 2,
            AlreadySettled: state == 3);
    }

    public async Task<BudgetSettlementResult> SettleBudgetAsync(
        string tenantId, string reservationId, long actualMicrodollars, CancellationToken cancellationToken)
    {
        if (actualMicrodollars < 0)
            throw new ArgumentOutOfRangeException(nameof(actualMicrodollars));

        var (budgetKey, reservationKey) = BudgetKeys(tenantId, reservationId);
        var result = await _database.ScriptEvaluateAsync(
            _scripts["budget_settlement.lua"],
            [budgetKey, reservationKey],
            [actualMicrodollars, _options.DailyBudgetMicrodollars, _options.DailyKeyTtlSeconds])
            .WaitAsync(cancellationToken);

        var values = (RedisResult[])result!;
        if ((long)values[0] != 1)
            throw new InvalidOperationException($"No active budget reservation exists for reservation '{reservationId}'.");

        return new BudgetSettlementResult(
            (long)values[1],
            (long)values[2],
            (long)values[3],
            (long)values[4],
            (long)values[5],
            (long)values[6] == 1);
    }

    public async Task<BudgetReservationResult> GetBudgetStatusAsync(
        string tenantId, CancellationToken cancellationToken)
    {
        var date = DateTime.UtcNow.ToString(DailyDateFormat, CultureInfo.InvariantCulture);
        var key = DailyBudgetKey(tenantId, date);
        var raw = await _database.StringGetAsync(key).WaitAsync(cancellationToken);
        var spent = raw.IsNull ? 0L : (long)raw;
        return new BudgetReservationResult(
            spent < _options.DailyBudgetMicrodollars,
            spent,
            Math.Max(0, _options.DailyBudgetMicrodollars - spent));
    }

    private RedisKey SlidingWindowKey(string tenantId) =>
        $"ratelimit:sw:{{{TenantId.ToKeySegment(tenantId)}}}";

    private RedisKey TokenBucketKey(string tenantId, string model) =>
        $"ratelimit:tb:{{{TenantId.ToKeySegment(tenantId)}}}:{TenantId.ToKeySegment(model)}";

    private static (RedisKey Budget, RedisKey Reservation) BudgetKeys(string tenantId, string reservationId)
    {
        // Carry the UTC reservation date in the ID so a request crossing midnight settles
        // against the exact daily bucket it reserved from.
        if (reservationId.Length < 10 || reservationId[8] != '-' ||
            !DateTime.TryParseExact(reservationId[..8], DailyDateFormat, CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal, out _))
            throw new ArgumentException("reservationId must begin with yyyyMMdd-.", nameof(reservationId));

        var tenant = TenantId.ToKeySegment(tenantId);
        var date = reservationId[..8];
        var hashTag = $"{tenant}:{date}";
        return ($"quota:daily:{{{hashTag}}}", $"quota:reservation:{{{hashTag}}}:{reservationId[9..]}");
    }

    private static RedisKey DailyBudgetKey(string tenantId, string date) =>
        $"quota:daily:{{{TenantId.ToKeySegment(tenantId)}:{date}}}";

    private static IReadOnlyDictionary<string, string> LoadScripts()
    {
        var assembly = Assembly.GetExecutingAssembly();
        var map = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var name in new[]
        {
            "sliding_window.lua", "token_bucket.lua", "sliding_window_status.lua",
            "token_bucket_status.lua", "budget_reservation.lua", "budget_settlement.lua"
        })
        {
            var resource = assembly.GetManifestResourceNames()
                .SingleOrDefault(x => x.EndsWith($".{name}", StringComparison.Ordinal))
                ?? throw new InvalidOperationException($"Embedded Redis script '{name}' was not found.");
            using var stream = assembly.GetManifestResourceStream(resource)
                ?? throw new InvalidOperationException($"Unable to read Redis script '{name}'.");
            using var reader = new StreamReader(stream);
            map.Add(name, reader.ReadToEnd());
        }
        return map;
    }
}
