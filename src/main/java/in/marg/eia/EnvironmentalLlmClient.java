package in.marg.eia;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/** Small OpenAI-compatible chat adapter; credentials and provider URL are deployment configuration. */
@Component
public class EnvironmentalLlmClient {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentalLlmClient.class);
    private static final int SERVICE_UNAVAILABLE = 503;
    private static final int MAX_BACKOFF_MILLIS = 2_000;

    private final EnvironmentalLlmProperties properties;
    private final RestClient client;

    @Autowired
    public EnvironmentalLlmClient(EnvironmentalLlmProperties properties) {
        this(properties, createClient(properties));
    }

    EnvironmentalLlmClient(EnvironmentalLlmProperties properties, RestClient client) {
        this.properties = properties;
        this.client = client;
    }

    private static RestClient createClient(EnvironmentalLlmProperties properties) {
        var httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(properties.timeoutSeconds(), 30))).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.timeoutSeconds()));
        return RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    public boolean isConfigured() {
        return properties.enabled() && !properties.apiKey().isBlank() && !properties.model().isBlank();
    }

    public String completeJson(String systemPrompt, String userPrompt) {
        return completeJson("llm-request", systemPrompt, userPrompt);
    }

    public String completeJson(String operation, String systemPrompt, String userPrompt) {
        if (!isConfigured()) {
            throw new IllegalStateException("LLM drafting is disabled or missing MARG_EIA_LLM_API_KEY / MARG_EIA_LLM_MODEL.");
        }
        List<String> candidates = properties.modelCandidates();
        if (candidates.isEmpty()) {
            throw new IllegalStateException("LLM drafting is enabled but no primary model is configured.");
        }
        List<String> attempted = new ArrayList<>();
        HttpStatusCodeException lastUnavailable = null;
        int consecutiveUnavailable = 0;

        for (int modelIndex = 0; modelIndex < candidates.size(); modelIndex++) {
            String model = candidates.get(modelIndex);
            int attemptsForModel = modelIndex == 0 ? 2 : 1;
            for (int attempt = 0; attempt < attemptsForModel; attempt++) {
                if (!attempted.isEmpty()) backoff(consecutiveUnavailable, operation, model);
                attempted.add(model);
                try {
                    String result = completeWithModel(model, systemPrompt, userPrompt);
                    if (modelIndex > 0 || attempt > 0) {
                        log.info("LLM operation '{}' succeeded with fallback model '{}' after {} unavailable attempt(s).",
                                operation, model, consecutiveUnavailable);
                    } else {
                        log.info("LLM operation '{}' succeeded with primary model '{}'.", operation, model);
                    }
                    return result;
                } catch (HttpStatusCodeException exception) {
                    if (exception.getStatusCode().value() != SERVICE_UNAVAILABLE) {
                        throw exception;
                    }
                    lastUnavailable = exception;
                    consecutiveUnavailable++;
                    log.warn("LLM operation '{}' received HTTP 503 from model '{}' (attempt {}).",
                            operation, model, attempt + 1);
                }
            }
        }

        String attemptedModels = attempted.stream().distinct().reduce((left, right) -> left + ", " + right).orElse("none");
        throw new IllegalStateException("LLM service returned HTTP 503 for operation '" + operation
                + "' across configured models: " + attemptedModels + ". No report was produced.", lastUnavailable);
    }

    private String completeWithModel(String model, String systemPrompt, String userPrompt) {
        if (properties.baseUrl().contains("generativelanguage.googleapis.com")) {
            return completeWithGemini(model, systemPrompt, userPrompt);
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.1,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        @SuppressWarnings("unchecked")
        Map<String, Object> response = client.post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(properties.apiKey()))
                .body(body).retrieve().body(Map.class);
        if (response == null || !(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("Configured LLM returned no completion choices.");
        }
        Object choice = choices.getFirst();
        if (!(choice instanceof Map<?, ?> choiceMap) || !(choiceMap.get("message") instanceof Map<?, ?> message)
                || !(message.get("content") instanceof String content) || content.isBlank()) {
            throw new IllegalStateException("Configured LLM returned an empty or unsupported response shape.");
        }
        return content;
    }

    private String completeWithGemini(String model, String systemPrompt, String userPrompt) {
        String cleanModel = model.startsWith("models/") ? model.substring("models/".length()) : model;
        String combinedUserText = (systemPrompt != null && !systemPrompt.isBlank())
                ? "System Instructions:\n" + systemPrompt + "\n\nUser Prompt:\n" + userPrompt
                : userPrompt;

        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of("role", "user", "parts", List.of(Map.of("text", combinedUserText)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "temperature", 0.1));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = client.post()
                .uri("/models/" + cleanModel + ":generateContent?key=" + properties.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(Map.class);

        if (response == null || !(response.get("candidates") instanceof List<?> candidates) || candidates.isEmpty()) {
            throw new IllegalStateException("Gemini returned no candidate completions.");
        }
        Object candidate = candidates.getFirst();
        if (!(candidate instanceof Map<?, ?> candMap) || !(candMap.get("content") instanceof Map<?, ?> contentMap)
                || !(contentMap.get("parts") instanceof List<?> parts) || parts.isEmpty()) {
            throw new IllegalStateException("Gemini candidate has no content parts.");
        }
        Object part = parts.getFirst();
        if (!(part instanceof Map<?, ?> partMap) || !(partMap.get("text") instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Gemini part text is blank or missing.");
        }
        return text;
    }

    private void backoff(int consecutiveUnavailable, String operation, String nextModel) {
        int base = properties.retryDelayMillis();
        if (base <= 0) return;
        int exponent = Math.min(Math.max(consecutiveUnavailable - 1, 0), 10);
        long delay = Math.min(MAX_BACKOFF_MILLIS, (long) base << exponent);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off before trying model '" + nextModel
                    + "' for operation '" + operation + "'.", exception);
        }
    }
}
