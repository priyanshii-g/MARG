package in.marg.building;

/**
 * Raster classification codes for {@code assam_building_density_utm46.tif}.
 * Encodes 30 m built-up structure concentration (buildings per hectare),
 * village/town settlement boundaries, and institutional/religious compound footprints.
 */
public enum BuildingDensityClass {
    NONE(0),
    SPARSE_HOMESTEAD_FRINGE(1),
    LOW_DENSITY_RURAL_RIBBON(2),
    MEDIUM_DENSITY_VILLAGE_CLUSTER(3),
    HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP(4),
    INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT(5),
    NODATA(255);

    private final int code;

    BuildingDensityClass(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /**
     * Multi-level grade-separated interchanges (150 m radius cloverleaf/trumpet loops)
     * must not be placed directly on top of high-density commercial/urban built-up blocks
     * or schools/medical/religious compounds to prevent mass displacement and social conflict.
     */
    public boolean isInterchangeExcludedZone() {
        return this == HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP
                || this == INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT
                || this == MEDIUM_DENSITY_VILLAGE_CLUSTER;
    }

    public static BuildingDensityClass fromRasterValue(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return NODATA;
        }
        int rounded = (int) Math.round(value);
        return switch (rounded) {
            case 0 -> NONE;
            case 1 -> SPARSE_HOMESTEAD_FRINGE;
            case 2 -> LOW_DENSITY_RURAL_RIBBON;
            case 3 -> MEDIUM_DENSITY_VILLAGE_CLUSTER;
            case 4 -> HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP;
            case 5 -> INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT;
            case 255 -> NODATA;
            default -> throw new IllegalArgumentException(
                    "Unexpected building density raster code: " + value);
        };
    }
}
