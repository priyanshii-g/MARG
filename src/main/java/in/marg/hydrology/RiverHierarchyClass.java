package in.marg.hydrology;

import java.util.Locale;

/**
 * Functional hydraulic hierarchy of perennial rivers & streams for bridge span and cost estimation.
 */
public enum RiverHierarchyClass {
    MAJOR_PERENNIAL_RIVER("MAJOR_RIVER_HIGH_LEVEL_BRIDGE"),
    MEDIUM_PERENNIAL_RIVER("MEDIUM_RIVER_SPAN_BRIDGE"),
    MINOR_PERENNIAL_STREAM("MINOR_BRIDGE_BOX_CULVERT");

    private final String bridgeStructureType;

    RiverHierarchyClass(String bridgeStructureType) {
        this.bridgeStructureType = bridgeStructureType;
    }

    public String bridgeStructureType() {
        return bridgeStructureType;
    }

    public static RiverHierarchyClass fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEDIUM_PERENNIAL_RIVER;
        }
        String clean = raw.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (clean) {
            case "MAJOR", "MAJOR_RIVER", "MAJOR_PERENNIAL_RIVER" -> MAJOR_PERENNIAL_RIVER;
            case "MEDIUM", "MEDIUM_RIVER", "MEDIUM_PERENNIAL_RIVER" -> MEDIUM_PERENNIAL_RIVER;
            case "MINOR", "MINOR_RIVER", "MINOR_PERENNIAL_RIVER", "STREAM", "MINOR_STREAM", "MINOR_PERENNIAL_STREAM" -> MINOR_PERENNIAL_STREAM;
            default -> {
                try {
                    yield RiverHierarchyClass.valueOf(clean);
                } catch (IllegalArgumentException e) {
                    yield clean.contains("MAJOR") ? MAJOR_PERENNIAL_RIVER :
                          clean.contains("MINOR") || clean.contains("STREAM") ? MINOR_PERENNIAL_STREAM :
                          MEDIUM_PERENNIAL_RIVER;
                }
            }
        };
    }
}
