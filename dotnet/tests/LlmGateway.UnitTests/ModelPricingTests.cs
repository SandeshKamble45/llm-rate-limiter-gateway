using Xunit;
using LlmGateway.Domain;

namespace LlmGateway.UnitTests;

public sealed class ModelPricingTests
{
    [Fact]
    public void CalculatesInputAndOutputCostInIntegerMicrodollars()
    {
        var pricing = new ModelPricing("test", 15_000, 60_000);
        Assert.Equal(15_000, pricing.CalculateInputCost(1_000_000));
        Assert.Equal(60_000, pricing.CalculateOutputCost(1_000_000));
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    public void NonPositiveTokenCountsHaveZeroCost(long tokens)
    {
        var pricing = new ModelPricing("test", 15_000, 60_000);
        Assert.Equal(0, pricing.CalculateInputCost(tokens));
        Assert.Equal(0, pricing.CalculateOutputCost(tokens));
    }

    [Fact]
    public void TinyPositiveUsageIsRoundedUpToOneMicrodollar()
    {
        var pricing = new ModelPricing("test", 15_000, 60_000);
        Assert.Equal(1, pricing.CalculateInputCost(1));
    }

    [Fact]
    public void CostCalculationUsesCheckedArithmetic()
    {
        var pricing = new ModelPricing("test", long.MaxValue, long.MaxValue);
        Assert.Throws<OverflowException>(() => pricing.CalculateInputCost(2));
    }
}
