package com.sandesh.ratelimiter.web;

import com.redis.testcontainers.RedisContainer;
import com.sandesh.ratelimiter.llm.LlmProviderClient;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    static final RedisContainer redis =
            new RedisContainer(
                    DockerImageName.parse("redis:7-alpine")
            );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @MockBean
    private LlmProviderClient llmProviderClient;

    @DynamicPropertySource
    static void redisProperties(
            DynamicPropertyRegistry registry) {

        registry.add(
                "spring.data.redis.host",
                redis::getHost
        );

        registry.add(
                "spring.data.redis.port",
                redis::getFirstMappedPort
        );
    }

    @BeforeEach
    void cleanRedis() {

        redisTemplate.delete(
                "ratelimit:tb:test-tenant:default-model"
        );

        redisTemplate.delete(
                "ratelimit:sw:test-tenant"
        );

        redisTemplate.delete(
                "quota:daily:test-tenant:"
                        + java.time.LocalDate.now(
                                java.time.ZoneOffset.UTC
                        )
        );

        redisTemplate.delete(
                "ratelimit:tb:rate-limit-tenant:default-model"
        );

        redisTemplate.delete(
                "ratelimit:sw:rate-limit-tenant"
        );

        redisTemplate.delete(
                "quota:daily:rate-limit-tenant:"
                        + java.time.LocalDate.now(
                                java.time.ZoneOffset.UTC
                        )
        );
    }

    @Test
    void shouldProcessValidChatRequest()
            throws Exception {

        when(
                llmProviderClient.callPrimaryModel(
                        anyString()
                )
        ).thenReturn(
                new LlmProviderClient.LlmResponse(
                        "mock-model",
                        "mock response",
                        10
                )
        );

        mockMvc.perform(
                        post("/v1/gateway/chat")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "tenantId": "test-tenant",
                                          "prompt": "Hello gateway"
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(
                        content().json("""
                                {
                                  "modelUsed": "mock-model",
                                  "completion": "mock response",
                                  "tokensUsed": 10
                                }
                                """)
                );
    }

    @Test
    void shouldRejectRequestWhenTenantIdIsMissing()
            throws Exception {

        mockMvc.perform(
                        post("/v1/gateway/chat")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "prompt": "Hello gateway"
                                        }
                                        """)
                )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        content().string(
                                "tenantId is required"
                        )
                );
    }

    @Test
    void shouldRejectRequestWhenPromptIsMissing()
            throws Exception {

        mockMvc.perform(
                        post("/v1/gateway/chat")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content("""
                                        {
                                          "tenantId": "test-tenant"
                                        }
                                        """)
                )
                .andExpect(
                        status().isBadRequest()
                )
                .andExpect(
                        content().string(
                                "prompt is required"
                        )
                );
    }

    @Test
    void shouldRejectRequestWhenPromptIsTooLarge()
            throws Exception {

        String oversizedPrompt =
                "a".repeat(10_001);

        mockMvc.perform(
                        post("/v1/gateway/chat")
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        """
                                        {
                                          "tenantId": "test-tenant",
                                          "prompt": "%s"
                                        }
                                        """.formatted(
                                                oversizedPrompt
                                        )
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );
    }

    @Test
    void shouldRejectRequestWhenSlidingWindowIsFull()
            throws Exception {

        when(
                llmProviderClient.callPrimaryModel(
                        anyString()
                )
        ).thenReturn(
                new LlmProviderClient.LlmResponse(
                        "mock-model",
                        "mock response",
                        10
                )
        );

        String request =
                """
                {
                  "tenantId": "rate-limit-tenant",
                  "prompt": "Hello"
                }
                """;

        mockMvc.perform(
                post("/v1/gateway/chat")
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(request)
        ).andExpect(status().isOk());

        mockMvc.perform(
                post("/v1/gateway/chat")
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(request)
        ).andExpect(status().isOk());

        mockMvc.perform(
                post("/v1/gateway/chat")
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(request)
        ).andExpect(status().isOk());

        mockMvc.perform(
                post("/v1/gateway/chat")
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content(request)
        ).andExpect(
                status().isTooManyRequests()
        );
    }
}