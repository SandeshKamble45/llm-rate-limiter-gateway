package com.sandesh.ratelimiter.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandesh.ratelimiter.model.UsageMetadata;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Component
public class OpenAiLlmProvider implements LlmProvider {

    private final RestClient restClient;
    private final OpenAiProperties properties;
    private final ObjectMapper objectMapper;
    private final TokenCounter tokenCounter;

    public OpenAiLlmProvider(
            OpenAiProperties properties,
            ObjectMapper objectMapper,
            TokenCounter tokenCounter) {

        this.properties = properties;
        this.objectMapper = objectMapper;
        this.tokenCounter = tokenCounter;

        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(
                        HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public LlmResponse complete(String prompt) {

        if (properties.getApiKey() == null
                || properties.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not configured");
        }

        Map<String, Object> request = new HashMap<>();
        request.put("model", properties.getModel());
        request.put("input", prompt);

        String responseBody = restClient.post()
                .uri("/responses")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + properties.getApiKey())
                .body(request)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(responseBody);

            String completion = extractOutputText(root);

            long inputTokens = extractUsage(
                    root,
                    "input_tokens");

            long outputTokens = extractUsage(
                    root,
                    "output_tokens");

            /*
             * OpenAI normally provides usage data.
             *
             * The local tokenizer is only a defensive fallback
             * when usage is missing from the provider response.
             */
            if (inputTokens <= 0) {
                inputTokens = Math.max(
                        1,
                        tokenCounter.countTokens(prompt));
            }

            if (outputTokens <= 0) {
                outputTokens = Math.max(
                        1,
                        tokenCounter.countTokens(completion));
            }

            UsageMetadata usage = new UsageMetadata(
                    inputTokens,
                    outputTokens,
                    inputTokens + outputTokens);

            return new LlmResponse(
                    properties.getModel(),
                    completion,
                    usage);

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to parse OpenAI response",
                    exception);
        }
    }

    private String extractOutputText(JsonNode root) {

        JsonNode outputText = root.get("output_text");

        if (outputText != null && outputText.isTextual()) {
            return outputText.asText();
        }

        JsonNode output = root.get("output");

        if (output != null && output.isArray()) {

            StringBuilder completion = new StringBuilder();

            for (JsonNode outputItem : output) {

                JsonNode content = outputItem.get("content");

                if (content == null || !content.isArray()) {
                    continue;
                }

                for (JsonNode contentItem : content) {

                    JsonNode text = contentItem.get("text");

                    if (text != null && text.isTextual()) {
                        completion.append(text.asText());
                    }
                }
            }

            if (!completion.isEmpty()) {
                return completion.toString();
            }
        }

        throw new IllegalStateException(
                "OpenAI response did not contain output text");
    }

    private long extractUsage(
            JsonNode root,
            String fieldName) {

        JsonNode usage = root.get("usage");

        if (usage == null) {
            return 0;
        }

        JsonNode value = usage.get(fieldName);

        if (value == null || !value.isNumber()) {
            return 0;
        }

        return value.asLong();
    }
}