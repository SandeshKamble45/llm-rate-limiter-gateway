namespace LlmGateway.Application.Configuration;

/// <summary>
/// Optional JWT boundary for deployments backed by an OIDC identity provider.
/// The demo profile remains anonymous to preserve the current public dashboard contract.
/// </summary>
public sealed class AuthenticationOptions
{
    public const string SectionName = "Authentication";
    public bool Enabled { get; init; }
    public string? Authority { get; init; }
    public string? Audience { get; init; }
    public string TenantClaimType { get; init; } = "tenant_id";
}
