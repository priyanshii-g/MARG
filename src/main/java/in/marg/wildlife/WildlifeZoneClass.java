package in.marg.wildlife;

/**
 * Raster classification codes for {@code assam_wildlife_esz_corridors_utm46.tif}.
 */
public enum WildlifeZoneClass {
    NONE(0),
    ESZ_OUTER_TRANSITION_ZONE(1),
    ESZ_INNER_BUFFER_ZONE(2),
    WILDLIFE_CORRIDOR_FRINGE_BUFFER(3),
    REGIONAL_FOREST_STEPPING_CORRIDOR(4),
    CRITICAL_MIGRATION_CORRIDOR_CORE(5),
    PROTECTED_SANCTUARY_HABITAT(6),
    NODATA(255);

    private final int code;

    WildlifeZoneClass(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /**
     * High-intensity highway interchanges (ramps, toll plazas, high-mast lighting)
     * are excluded from inner Eco-Sensitive Zones and active wildlife migration corridors.
     */
    public boolean isInterchangeExcludedZone() {
        return this == ESZ_INNER_BUFFER_ZONE
                || this == REGIONAL_FOREST_STEPPING_CORRIDOR
                || this == CRITICAL_MIGRATION_CORRIDOR_CORE
                || this == PROTECTED_SANCTUARY_HABITAT;
    }

    public static WildlifeZoneClass fromRasterValue(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return NODATA;
        }
        int rounded = (int) Math.round(value);
        return switch (rounded) {
            case 0 -> NONE;
            case 1 -> ESZ_OUTER_TRANSITION_ZONE;
            case 2 -> ESZ_INNER_BUFFER_ZONE;
            case 3 -> WILDLIFE_CORRIDOR_FRINGE_BUFFER;
            case 4 -> REGIONAL_FOREST_STEPPING_CORRIDOR;
            case 5 -> CRITICAL_MIGRATION_CORRIDOR_CORE;
            case 6 -> PROTECTED_SANCTUARY_HABITAT;
            case 255 -> NODATA;
            default -> throw new IllegalArgumentException(
                    "Unexpected wildlife & ESZ raster code: " + value);
        };
    }
}
