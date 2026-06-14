package com.aegis.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Thin client over the Anthropic Messages API. This is the "unreliable
 * downstream": it can be slow, rate-limited, or unavailable. Everything in
 * {@link TriageService} exists to keep our service healthy when this is not.
 *
 * If no API key is configured, the client throws, which lets us exercise the
 * circuit breaker and the fallback path with zero external dependencies -- so
 * the whole project runs locally without secrets.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${aegis.anthropic.api-key:}")
    private String apiKey;

    @Value("${aegis.anthropic.model:claude-sonnet-4-20250514}")
    private String model;

    public String summarizeTriage(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            // No key: simulate an unreliable downstream so the resilience
            // patterns are demonstrable offline.
            throw new IllegalStateException("LLM unavailable (no API key configured)");
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", 300,
                    "messages", new Object[]{Map.of("role", "user", "content", prompt)}
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .timeout(Duration.ofSeconds(8))
                    .header("content-type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 429 || resp.statusCode() >= 500) {
                throw new RuntimeException("LLM transient error: HTTP " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            return root.path("content").path(0).path("text").asText("");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed", e);
        }
    }
}
