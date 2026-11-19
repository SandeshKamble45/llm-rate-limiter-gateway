using System.Text;
using System.Text.RegularExpressions;

namespace LlmGateway.Domain;

/// <summary>
/// Validates tenant identifiers before they are used in Redis keys.
/// A URL-safe encoding prevents caller-controlled delimiters from changing key structure.
/// </summary>
public static partial class TenantId
{
    [GeneratedRegex(@"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", RegexOptions.CultureInvariant)]
    private static partial Regex AllowedPattern();

    public static string Normalize(string value)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value);
        var normalized = value.Trim();
        if (!AllowedPattern().IsMatch(normalized))
            throw new ArgumentException("tenantId contains unsupported characters or exceeds 128 characters.", nameof(value));
        return normalized;
    }

    public static string ToKeySegment(string normalized) =>
        Convert.ToBase64String(Encoding.UTF8.GetBytes(normalized))
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
}
