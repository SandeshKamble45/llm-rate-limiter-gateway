using Xunit;
using LlmGateway.Infrastructure;

namespace LlmGateway.UnitTests;

public sealed class Cl100kTokenCounterTests
{
    [Fact]
    public void MatchesKnownCl100kBaseTokenVector()
    {
        var counter = new Cl100kTokenCounter();
        Assert.Equal(4, counter.CountTokens("Hello, world!"));
    }

    [Fact]
    public void ReturnsZeroForNullOrWhitespaceText()
    {
        var counter = new Cl100kTokenCounter();
        Assert.Equal(0, counter.CountTokens(null!));
        Assert.Equal(0, counter.CountTokens("   "));
    }
}
