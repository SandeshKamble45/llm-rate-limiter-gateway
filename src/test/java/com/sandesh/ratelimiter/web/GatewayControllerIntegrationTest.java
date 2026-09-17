package com.sandesh.ratelimiter.web;

import com.redis.testcontainers.RedisContainer;
import com.sandesh.ratelimiter.llm.LlmProviderClient;
import com.sandesh.ratelimiter.model.RateLimitResult;
import com.sandesh.ratelimiter.model.SlidingWindowStatus;
import com.sandesh.ratelimiter.model.TokenBucketStatus;
import com.sandesh.ratelimiter.pricing.ModelPricing;
import com.sandesh.ratelimiter.pricing.ModelPricingService;
import com.sandesh.ratelimiter.ratelimit.SlidingWindowRateLimiter;
import com.sandesh.ratelimiter.ratelimit.TokenBucketRateLimiter;
import com.sandesh.ratelimiter.quota.TenantQuotaService;
import com.sandesh.ratelimiter.llm.TokenCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "ratelimit.token-bucket.capacity=100",
    "ratelimit.token-bucket.refill-rate-per-sec=10",
    "ratelimit.sliding-window.window-seconds=60",
    "ratelimit.sliding-window.max-requests=3",
    "quota.daily-budget-microdollars=5000000"
})
class GatewayControllerIntegrationTest {

  @Container
  static final RedisContainer redis = new RedisContainer(
      DockerImageName.parse("redis:7-alpine"));

  @Autowired
  MockMvc mockMvc;

  @Autowired
  RedisTemplate<String, Object> redisTemplate;

  @Autowired
  TokenCounter tokenCounter;

  @MockBean
  LlmProviderClient llmProviderClient;

  @Autowired
  private ModelPricingService modelPricingService;

  @Autowired
  private TokenBucketRateLimiter tokenBucketLimiter;

  @Autowired
  private SlidingWindowRateLimiter slidingWindowLimiter;

  @Autowired
  private TenantQuotaService quotaService;

  @DynamicPropertySource
  static void redisProperties(
      DynamicPropertyRegistry registry) {

    registry.add(
        "spring.data.redis.host",
        redis::getHost);

    registry.add(
        "spring.data.redis.port",
        redis::getFirstMappedPort);
  }

  @BeforeEach
  void cleanRedis() {

    redisTemplate.delete(
        "ratelimit:tb:test-tenant:default-model");

    redisTemplate.delete(
        "ratelimit:sw:test-tenant");

    redisTemplate.delete(
        "quota:daily:test-tenant:"
            + LocalDate.now(ZoneOffset.UTC));

    redisTemplate.delete(
        "ratelimit:tb:rate-limit-tenant:default-model");

    redisTemplate.delete(
        "ratelimit:sw:rate-limit-tenant");

    redisTemplate.delete(
        "quota:daily:rate-limit-tenant:"
            + LocalDate.now(ZoneOffset.UTC));

    redisTemplate.delete(
        "ratelimit:tb:token-count-tenant:default-model");

    redisTemplate.delete(
        "ratelimit:sw:token-count-tenant");

    redisTemplate.delete(
        "quota:daily:token-count-tenant:"
            + LocalDate.now(ZoneOffset.UTC));
  }

  @Test
  void shouldProcessValidChatRequest() throws Exception {

    when(llmProviderClient.callPrimaryModel(anyString()))
        .thenReturn(
            new LlmProviderClient.LlmResponse(
                "primary-model",
                "mock response",
                10));

    mockMvc.perform(
        post("/v1/gateway/chat")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "test-tenant",
                  "prompt": "Hello gateway"
                }
                """))
        .andExpect(status().isOk())
        .andExpect(
            content().json("""
                {
                  "response": {
                    "modelUsed": "primary-model",
                    "completion": "mock response",
                    "usage": {
                      "inputTokens": 10,
                      "outputTokens": 0,
                      "totalTokens": 10
                    }
                  },
                  "estimatedCostMicrodollars": 10,
                  "actualCostMicrodollars": 1,
                  "settlement": {
                    "reservedMicrodollars": 10,
                    "actualMicrodollars": 1,
                    "adjustmentMicrodollars": -9,
                    "spentMicrodollars": 1,
                    "remainingMicrodollars": 4999999,
                    "withinBudget": true
                  }
                }
                """));
  }

  @Test
  void shouldRejectRequestWhenTenantIdIsMissing()
      throws Exception {

    mockMvc.perform(
        post("/v1/gateway/chat")
            .contentType(
                MediaType.APPLICATION_JSON)
            .content("""
                {
                  "prompt": "Hello gateway"
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(
            content().string(
                "tenantId is required"));
  }

  @Test
  void shouldRejectRequestWhenPromptIsMissing()
      throws Exception {

    mockMvc.perform(
        post("/v1/gateway/chat")
            .contentType(
                MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "test-tenant"
                }
                """))
        .andExpect(status().isBadRequest())
        .andExpect(
            content().string(
                "prompt is required"));
  }

  @Test
  void shouldRejectRequestWhenPromptIsTooLarge()
      throws Exception {

    String largePrompt = "a".repeat(10_001);

    mockMvc.perform(
        post("/v1/gateway/chat")
            .contentType(
                MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId": "test-tenant",
                  "prompt": "%s"
                }
                """.formatted(largePrompt)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldRejectRequestWhenSlidingWindowIsFull()
      throws Exception {

    when(llmProviderClient.callPrimaryModel(anyString()))
        .thenReturn(
            new LlmProviderClient.LlmResponse(
                "primary-model",
                "mock response",
                10));

    for (int i = 0; i < 3; i++) {
      mockMvc.perform(
          post("/v1/gateway/chat")
              .contentType(
                  MediaType.APPLICATION_JSON)
              .content("""
                  {
                    "tenantId":
                      "rate-limit-tenant",
                    "prompt": "Hello"
                  }
                  """))
          .andExpect(status().isOk());
    }

    mockMvc.perform(
        post("/v1/gateway/chat")
            .contentType(
                MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId":
                    "rate-limit-tenant",
                  "prompt": "Hello"
                }
                """))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void shouldUseTokenCounterForTokenBucketCost()
      throws Exception {

    String prompt = "This is a deliberately longer prompt so that "
        + "the tokenizer produces a meaningful "
        + "number of tokens.";

    int expectedTokens = Math.max(
        1,
        tokenCounter.countTokens(prompt));

    when(llmProviderClient.callPrimaryModel(anyString()))
        .thenReturn(
            new LlmProviderClient.LlmResponse(
                "primary-model",
                "mock response",
                expectedTokens));

    mockMvc.perform(
        post("/v1/gateway/chat")
            .contentType(
                MediaType.APPLICATION_JSON)
            .content("""
                {
                "tenantId":
                        "token-count-tenant",
                "prompt": "%s"
                }
                """.formatted(prompt)))
        .andExpect(status().isOk())
        .andExpect(
            content().json(
                """
                           {
                        "response": {
                            "usage": {
                                "inputTokens": %d,
                                "outputTokens": 0,
                                "totalTokens": %d
                            }
                        }
                    }
                        """.formatted(
                    expectedTokens,
                    expectedTokens)));
  }

  @Test
  void shouldReleaseReservedBudgetWhenLlmProviderFails()
      throws Exception {

    String tenantId = "provider-failure-tenant";
    String prompt = "Explain distributed systems.";

    int estimatedInputTokens = Math.max(
        1,
        tokenCounter.countTokens(prompt));

    ModelPricing pricing = modelPricingService.getPricing(
        "primary-model");

    long expectedReservation = pricing.calculateInputCost(
        estimatedInputTokens)
        + pricing.calculateOutputCost(
            150);

    doThrow(
        new RuntimeException("Provider unavailable"))
        .when(llmProviderClient)
        .callPrimaryModel(anyString());

    mockMvc.perform(
        post("/v1/gateway/chat")
            .contentType(
                MediaType.APPLICATION_JSON)
            .content("""
                {
                  "tenantId":
                    "provider-failure-tenant",
                  "prompt":
                    "Explain distributed systems."
                }
                """))
        .andExpect(
            status().isBadGateway())
        .andExpect(
            content().json(
                """
                    {
                      "message":
                        "LLM provider unavailable",
                      "releasedReservationMicrodollars":
                        %d,
                      "settlement": {
                        "reservedMicrodollars":
                          %d,
                        "actualMicrodollars": 0,
                        "adjustmentMicrodollars":
                          %d,
                        "spentMicrodollars": 0,
                        "remainingMicrodollars":
                          %d,
                        "withinBudget": true
                      }
                    }
                    """.formatted(
                    expectedReservation,
                    expectedReservation,
                    -expectedReservation,
                    5_000_000L)));
  }

  @Test
  void shouldReturnReadOnlyGatewayStatus()
      throws Exception {

    String tenantId = "status-tenant";

    // Create known limiter state.
    tokenBucketLimiter.tryConsume(
        tenantId + ":primary-model",
        25);

    slidingWindowLimiter.tryConsume(
        tenantId);

    quotaService.reserveBudget(
        tenantId,
        100);

    TokenBucketStatus beforeTokenBucket = tokenBucketLimiter.getStatus(
        tenantId + ":primary-model");

    SlidingWindowStatus beforeSlidingWindow = slidingWindowLimiter.getStatus(
        tenantId);

    TenantQuotaService.BudgetReservationResult beforeBudget = quotaService.getBudgetStatus(
        tenantId);

    mockMvc.perform(
        get("/v1/gateway/status")
            .param(
                "tenantId",
                tenantId))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.tenantId")
                .value(tenantId))
        .andExpect(
            jsonPath(
                "$.tokenBucket.capacity")
                .value(100))
        .andExpect(
            jsonPath(
                "$.tokenBucket.refillRatePerSecond")
                .value(10.0))
        .andExpect(
            jsonPath(
                "$.slidingWindow.maxRequests")
                .value(3))
        .andExpect(
            jsonPath(
                "$.slidingWindow.windowSeconds")
                .value(60))
        .andExpect(
            jsonPath(
                "$.dailyBudget.spentMicrodollars")
                .value(
                    beforeBudget
                        .spentMicrodollars()));

    TokenBucketStatus afterTokenBucket = tokenBucketLimiter.getStatus(
        tenantId + ":primary-model");

    SlidingWindowStatus afterSlidingWindow = slidingWindowLimiter.getStatus(
        tenantId);

    TenantQuotaService.BudgetReservationResult afterBudget = quotaService.getBudgetStatus(
        tenantId);

    assertThat(afterTokenBucket.availableTokens())
        .isGreaterThanOrEqualTo(
            beforeTokenBucket.availableTokens())
        .isLessThanOrEqualTo(100.0);

    assertThat(afterSlidingWindow.currentRequests())
        .isEqualTo(
            beforeSlidingWindow.currentRequests());

    assertThat(afterBudget.spentMicrodollars())
        .isEqualTo(
            beforeBudget.spentMicrodollars());
  }

  @Test
  void shouldReturnCircuitBreakerStatus()
      throws Exception {

    mockMvc.perform(
        get("/v1/gateway/status")
            .param(
                "tenantId",
                "circuit-breaker-status-tenant"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                "$.circuitBreaker.name")
                .value("primaryLlm"))
        .andExpect(
            jsonPath(
                "$.circuitBreaker.state")
                .value("CLOSED"))
        .andExpect(
            jsonPath(
                "$.circuitBreaker.failureRate")
                .exists())
        .andExpect(
            jsonPath(
                "$.circuitBreaker.bufferedCalls")
                .exists())
        .andExpect(
            jsonPath(
                "$.circuitBreaker.failedCalls")
                .exists());
  }

  @Test
  void shouldRejectStatusRequestWhenTenantIdIsBlank()
      throws Exception {

    mockMvc.perform(
        get("/v1/gateway/status")
            .param("tenantId", ""))
        .andExpect(
            status().isBadRequest());
  }
}