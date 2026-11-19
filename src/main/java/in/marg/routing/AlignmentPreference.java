package in.marg.routing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request-scoped multi-objective alignment preference.
 * <p>
 * Controls the relative weights of competing highway alignment objectives
 * on the fly without altering global model defaults or permanently mutating layer policies:
 * <ul>
 *   <li><b>environmentalWeight</b>: Forest canopy, wildlife corridors/ESZ, perennial rivers, wetlands.</li>
 *   <li><b>socialWeight</b>: Village boundaries, rural homesteads, demographic exposure, displacement.</li>
 *   <li><b>directnessWeight</b>: Geometric straightness, minimal deviation from baseline, cut/fill & earthwork economy.</li>
 * </ul>
 */
public record AlignmentPreference(
        double environmentalWeight,
        double socialWeight,
        double directnessWeight
) {
    public static final AlignmentPreference BALANCED = new AlignmentPreference(1.0, 1.0, 1.0);
    public static final AlignmentPreference ECO_SHIELD = new AlignmentPreference(2.5, 1.0, 0.8);
    public static final AlignmentPreference COMMUNITY_SAFEGUARD = new AlignmentPreference(1.0, 2.5, 0.8);
    public static final AlignmentPreference SHORTEST_PATH = new AlignmentPreference(0.8, 0.8, 2.5);

    public AlignmentPreference {
        environmentalWeight = clamp(environmentalWeight);
        socialWeight = clamp(socialWeight);
        directnessWeight = clamp(directnessWeight);
    }

    private static double clamp(double w) {
        if (!Double.isFinite(w)) return 1.0;
        return Math.max(0.2, Math.min(4.0, Math.round(w * 100.0) / 100.0));
    }

    public static AlignmentPreference of(String preset, Double env, Double social, Double directness) {
        if (preset != null && !preset.isBlank() && !"CUSTOM".equalsIgnoreCase(preset)) {
            return fromPreset(preset);
        }
        return new AlignmentPreference(
                env != null ? env : 1.0,
                social != null ? social : 1.0,
                directness != null ? directness : 1.0);
    }

    public static AlignmentPreference fromPreset(String preset) {
        if (preset == null || preset.isBlank()) return BALANCED;
        return switch (preset.toUpperCase()) {
            case "ECO_SHIELD", "PROTECT_NATURE", "ENVIRONMENT" -> ECO_SHIELD;
            case "COMMUNITY_SAFEGUARD", "PROTECT_COMMUNITIES", "SOCIAL" -> COMMUNITY_SAFEGUARD;
            case "SHORTEST_PATH", "EXPRESS", "DIRECTNESS", "DIRECT" -> SHORTEST_PATH;
            default -> BALANCED;
        };
    }

    public String classifyPreset() {
        if (Math.abs(environmentalWeight - 2.5) < 0.15
                && Math.abs(socialWeight - 1.0) < 0.15
                && Math.abs(directnessWeight - 0.8) < 0.15) {
            return "ECO_SHIELD";
        }
        if (Math.abs(environmentalWeight - 1.0) < 0.15
                && Math.abs(socialWeight - 2.5) < 0.15
                && Math.abs(directnessWeight - 0.8) < 0.15) {
            return "COMMUNITY_SAFEGUARD";
        }
        if (Math.abs(environmentalWeight - 0.8) < 0.15
                && Math.abs(socialWeight - 0.8) < 0.15
                && Math.abs(directnessWeight - 2.5) < 0.15) {
            return "SHORTEST_PATH";
        }
        if (Math.abs(environmentalWeight - 1.0) < 0.05
                && Math.abs(socialWeight - 1.0) < 0.05
                && Math.abs(directnessWeight - 1.0) < 0.05) {
            return "BALANCED";
        }
        return "CUSTOM";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("environmentalWeight", environmentalWeight);
        m.put("socialWeight", socialWeight);
        m.put("directnessWeight", directnessWeight);
        m.put("preset", classifyPreset());
        return m;
    }
}
