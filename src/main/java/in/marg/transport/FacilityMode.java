package in.marg.transport;

import java.util.Locale;

/**
 * Highway facility design mode controlling mainline accessibility pull,
 * minimum inter-interchange spacing, hub population thresholds, and design capacity.
 */
public enum FacilityMode {
    /**
     * Pure long-distance access-controlled expressway:
     * accessibilityPull = 0.0 (no mainline bending toward cities),
     * min interchange spacing = 15.0 km, high hub population threshold,
     * cities served strictly via post-hoc dedicated Feeder/Spur roads.
     */
    EXPRESSWAY,

    /**
     * Regional collector national highway:
     * accessibilityPull > 0.0 (gentle two-stage pull within 1.5–5.0 km collector ring of approved hubs),
     * min interchange spacing = 8.0 km, moderate regional hub population threshold,
     * dedicated Feeder/Spur roads tie into existing urban bypass / ring-road junctions.
     */
    NATIONAL_HIGHWAY;

    public static FacilityMode fromParam(String raw, FacilityMode defaultMode) {
        if (raw == null || raw.isBlank()) {
            return defaultMode;
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (norm) {
            case "EXPRESSWAY", "EXP" -> EXPRESSWAY;
            // The UI exposes State Hwy as a regional facility class. The routing
            // engine models regional collectors using the NATIONAL_HIGHWAY profile.
            case "NATIONAL_HIGHWAY", "NH", "HIGHWAY", "REGIONAL_HIGHWAY", "STATE_HIGHWAY", "SH" -> NATIONAL_HIGHWAY;
            default -> throw new IllegalArgumentException(
                    "Unsupported facilityMode '" + raw + "'. Use EXPRESSWAY or NATIONAL_HIGHWAY.");
        };
    }
}
