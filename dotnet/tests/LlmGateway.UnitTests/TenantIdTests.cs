using Xunit;
using LlmGateway.Domain;

namespace LlmGateway.UnitTests;

public sealed class TenantIdTests
{
    [Theory]
    [InlineData("tenant-1")]
    [InlineData("customer_42")]
    [InlineData("abc.example")]
    public void AcceptsSupportedTenantIdentifiers(string tenantId)
    {
        Assert.Equal(tenantId, TenantId.Normalize(tenantId));
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("tenant:other")]
    [InlineData("tenant/other")]
    [InlineData("../root")]
    public void RejectsInvalidTenantIdentifiers(string tenantId)
    {
        Assert.Throws<ArgumentException>(() => TenantId.Normalize(tenantId));
    }

    [Fact]
    public void KeyEncodingIsDeterministicAndDoesNotPreserveDelimiters()
    {
        var encoded = TenantId.ToKeySegment("tenant:one");
        Assert.Equal(encoded, TenantId.ToKeySegment("tenant:one"));
        Assert.DoesNotContain(":", encoded);
        Assert.DoesNotContain("/", encoded);
    }
}
