package in.marg.geotech;

/**
 * Raster classification codes for {@code assam_geotech_landslide_utm46.tif}.
 * Encodes subgrade California Bearing Ratio (CBR) / Safe Bearing Capacity (SBC),
 * Seismic Zone V alluvial liquefaction susceptibility, and Karbi hillslope landslide hazard.
 */
public enum GeotechHazardClass {
    COMPETENT_BEDROCK_STABLE_SUBGRADE(0),
    WEATHERED_RESIDUAL_MODERATE_SOIL(1),
    SOFT_ALLUVIAL_COMPRESSIBLE_CLAY(2),
    LIQUEFIABLE_ORGANIC_MARSH_SUBGRADE(3),
    MODERATE_COLLUVIAL_LANDSLIDE_SUSCEPTIBILITY(4),
    HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP(5),
    ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE(6),
    NODATA(255);

    private final int code;

    GeotechHazardClass(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /**
     * Multi-level grade-separated highway interchanges (heavy high-fill ramp embankments and
     * multi-span bridge abutments) must not be founded on liquefiable organic marsh clays,
     * steep high-susceptibility landslide scarps, or active tectonic shear cores.
     */
    public boolean isInterchangeUnsafeSubgrade() {
        return this == LIQUEFIABLE_ORGANIC_MARSH_SUBGRADE
                || this == HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP
                || this == ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE;
    }

    public static GeotechHazardClass fromRasterValue(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return NODATA;
        }
        int rounded = (int) Math.round(value);
        return switch (rounded) {
            case 0 -> COMPETENT_BEDROCK_STABLE_SUBGRADE;
            case 1 -> WEATHERED_RESIDUAL_MODERATE_SOIL;
            case 2 -> SOFT_ALLUVIAL_COMPRESSIBLE_CLAY;
            case 3 -> LIQUEFIABLE_ORGANIC_MARSH_SUBGRADE;
            case 4 -> MODERATE_COLLUVIAL_LANDSLIDE_SUSCEPTIBILITY;
            case 5 -> HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP;
            case 6 -> ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE;
            case 255 -> NODATA;
            default -> throw new IllegalArgumentException(
                    "Unexpected geotechnical & landslide raster code: " + value);
        };
    }
}
