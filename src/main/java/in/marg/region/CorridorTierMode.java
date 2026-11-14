package in.marg.region;

import java.util.Locale;

/**
 * Two-Tier Execution Mode for MARG Pan-India & Regional Benchmark Routing.
 *
 * <ul>
 *   <li>{@link #TIER_2_PRECACHED_BENCHMARK}: High-resolution 13-layer pre-cached regional
 *       demonstration corridors ("Presets") lazily loaded on demand into RAM, where
 *       evaluators can click any arbitrary start/end points inside the highlighted region.</li>
 *   <li>{@link #TIER_1_DYNAMIC_PAN_INDIA}: Dynamic on-demand Pan-India routing engine
 *       capable of routing between any arbitrary coordinates across India (UTM Zones 42N–47N).</li>
 * </ul>
 */
public enum CorridorTierMode {
    TIER_2_PRECACHED_BENCHMARK,
    TIER_1_DYNAMIC_PAN_INDIA;

    public static CorridorTierMode fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "TIER_1", "TIER1", "TIER_1_DYNAMIC_PAN_INDIA", "DYNAMIC", "PAN_INDIA", "INDIA" ->
                    TIER_1_DYNAMIC_PAN_INDIA;
            case "TIER_2", "TIER2", "TIER_2_PRECACHED_BENCHMARK", "PRESET", "BENCHMARK", "PRECACHED" ->
                    TIER_2_PRECACHED_BENCHMARK;
            default -> throw new IllegalArgumentException(
                    "Unsupported tierMode '" + raw + "'. Use TIER_2_PRECACHED_BENCHMARK or TIER_1_DYNAMIC_PAN_INDIA.");
        };
    }
}
