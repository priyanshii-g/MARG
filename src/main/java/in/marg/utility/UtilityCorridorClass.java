package in.marg.utility;

/**
 * Raster classification codes for {@code assam_utility_corridors_utm46.tif}.
 * Encodes High-Voltage / Extra-High-Voltage (EHV) overhead power transmission lines,
 * Right-of-Way (RoW) safety widths (per Ministry of Power / CEA guidelines),
 * induction/EMF setback zones, and electrical substations & transmission tower footprints.
 */
public enum UtilityCorridorClass {
    NONE(0),
    UTILITY_PROXIMITY_SETBACK_ZONE(1),
    DISTRIBUTION_LINE_ROW(2),
    SUBTRANSMISSION_132KV_ROW(3),
    EHV_220KV_ROW(4),
    EHV_400KV_ROW(5),
    SUBSTATION_OR_TOWER_FOOTPRINT(6),
    NODATA(255);

    private final int code;

    UtilityCorridorClass(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /**
     * Multi-level grade-separated highway interchanges (high embankment loops, flyovers,
     * high-mast illumination poles, and multi-span viaducts) must never be situated
     * directly under EHV 400kV/220kV transmission spans or on top of substation/tower footprints
     * per IRC:SP:84/IRC:SP:99 and CEA safety clearances.
     */
    public boolean isInterchangeExcludedZone() {
        return this == SUBSTATION_OR_TOWER_FOOTPRINT
                || this == EHV_400KV_ROW
                || this == EHV_220KV_ROW;
    }

    public static UtilityCorridorClass fromRasterValue(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return NODATA;
        }
        int rounded = (int) Math.round(value);
        return switch (rounded) {
            case 0 -> NONE;
            case 1 -> UTILITY_PROXIMITY_SETBACK_ZONE;
            case 2 -> DISTRIBUTION_LINE_ROW;
            case 3 -> SUBTRANSMISSION_132KV_ROW;
            case 4 -> EHV_220KV_ROW;
            case 5 -> EHV_400KV_ROW;
            case 6 -> SUBSTATION_OR_TOWER_FOOTPRINT;
            case 255 -> NODATA;
            default -> throw new IllegalArgumentException(
                    "Unexpected utility corridor raster code: " + value);
        };
    }
}
