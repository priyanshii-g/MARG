package in.marg.eia;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "marg.eia.llm")
public record EnvironmentalLlmProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        int timeoutSeconds,
        String fallbackModels,
        int retryDelayMillis
) {
    @ConstructorBinding
    public EnvironmentalLlmProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl;
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null ? "" : model;
        timeoutSeconds = timeoutSeconds < 1 ? 90 : timeoutSeconds;
        fallbackModels = fallbackModels == null ? "" : fallbackModels;
        retryDelayMillis = Math.clamp(retryDelayMillis, 0, 2_000);
    }

    /** Compatibility constructor retained for existing tests and callers. */
    public EnvironmentalLlmProperties(boolean enabled, String baseUrl, String apiKey, String model,
                                      int timeoutSeconds) {
        this(enabled, baseUrl, apiKey, model, timeoutSeconds, "", 500);
    }

    /** Primary model plus at most three distinct fallback models, in configured order. */
    public List<String> modelCandidates() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (!model.isBlank()) candidates.add(model.trim());
        for (String fallback : fallbackModels.split(",")) {
            String candidate = fallback.trim();
            if (!candidate.isBlank() && !candidate.equals(model.trim())) candidates.add(candidate);
            if (candidates.size() >= 4) break;
        }
        return List.copyOf(new ArrayList<>(candidates));
    }
}
