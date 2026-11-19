namespace LlmGateway.Application.Services;

public sealed class GatewayException(int statusCode, string message, object? payload = null) : Exception(message)
{
    public int StatusCode { get; } = statusCode;
    public object? Payload { get; } = payload;
}
