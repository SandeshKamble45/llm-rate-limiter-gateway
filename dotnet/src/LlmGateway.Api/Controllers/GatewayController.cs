using LlmGateway.Application.Services;
using LlmGateway.Application.Abstractions;
using LlmGateway.Application.Configuration;
using Microsoft.Extensions.Options;
using LlmGateway.Domain;
using LlmGateway.Infrastructure;
using Microsoft.AspNetCore.Mvc;

namespace LlmGateway.Api.Controllers;

[ApiController]
[Route("v1/gateway")]
public sealed class GatewayController(
    GatewayService gateway,
    IGatewayStateStore stateStore,
    ProviderResiliencePipeline providerResilience,
    IOptions<AuthenticationOptions> authenticationOptions) : ControllerBase
{
    [HttpPost("chat")]
    [ProducesResponseType(typeof(GatewayResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status402PaymentRequired)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    [ProducesResponseType(typeof(RateLimitResult), StatusCodes.Status429TooManyRequests)]
    [ProducesResponseType(typeof(GatewayErrorResponse), StatusCodes.Status502BadGateway)]
    public async Task<IActionResult> Chat(
        [FromBody] ChatRequest? request,
        [FromHeader(Name = "X-Demo-Access-Code")] string? demoAccessCode,
        [FromHeader(Name = "Idempotency-Key")] string? idempotencyKey,
        CancellationToken cancellationToken)
    {
        if (request is null)
            return BadRequest("Request body is required");
        if (!IsTenantAuthorized(request.TenantId))
            return Forbid();

        if (!string.IsNullOrWhiteSpace(idempotencyKey) && idempotencyKey.Length > 128)
            return BadRequest("Idempotency-Key cannot exceed 128 characters.");

        try
        {
            var response = await gateway.ProcessAsync(
                request, demoAccessCode, idempotencyKey, cancellationToken);
            return Ok(response);
        }
        catch (GatewayException exception)
        {
            return StatusCode(exception.StatusCode, exception.Payload ?? exception.Message);
        }
        catch (ArgumentException exception)
        {
            return BadRequest(exception.Message);
        }
    }

    [HttpGet("status")]
    [ProducesResponseType(typeof(GatewayStatusResponse), StatusCodes.Status200OK)]
    public async Task<IActionResult> Status([FromQuery] string? tenantId, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(tenantId))
            return BadRequest("tenantId is required");
        if (!IsTenantAuthorized(tenantId))
            return Forbid();

        try
        {
            var response = await gateway.GetStatusAsync(
                tenantId, providerResilience.GetStatus(), cancellationToken);
            return Ok(response);
        }
        catch (ArgumentException exception)
        {
            return BadRequest(exception.Message);
        }
    }

    [HttpPost("check/token-bucket")]
    public async Task<IActionResult> CheckTokenBucket(
        [FromQuery] string? tenantId,
        [FromQuery] long cost = 1,
        CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(tenantId))
            return BadRequest("tenantId is required");
        if (!IsTenantAuthorized(tenantId))
            return Forbid();
        try
        {
            var normalized = TenantId.Normalize(tenantId);
            var result = await stateStore.ConsumeTokenBucketAsync(
                normalized, "primary-model", cost, cancellationToken);
            return Ok(result);
        }
        catch (ArgumentException exception) { return BadRequest(exception.Message); }
    }

    private bool IsTenantAuthorized(string? tenantId)
    {
        var settings = authenticationOptions.Value;
        if (!settings.Enabled)
            return true;

        if (string.IsNullOrWhiteSpace(tenantId))
            return false;

        var claim = User.FindFirst(settings.TenantClaimType)?.Value;
        if (string.IsNullOrWhiteSpace(claim))
            return false;

        try
        {
            return string.Equals(
                TenantId.Normalize(claim),
                TenantId.Normalize(tenantId),
                StringComparison.Ordinal);
        }
        catch (ArgumentException)
        {
            return false;
        }
    }

    [HttpPost("check/sliding-window")]
    public async Task<IActionResult> CheckSlidingWindow(
        [FromQuery] string? tenantId,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(tenantId))
            return BadRequest("tenantId is required");
        if (!IsTenantAuthorized(tenantId))
            return Forbid();
        try
        {
            var normalized = TenantId.Normalize(tenantId);
            var result = await stateStore.ConsumeSlidingWindowAsync(normalized, cancellationToken);
            return Ok(result);
        }
        catch (ArgumentException exception) { return BadRequest(exception.Message); }
    }
}
