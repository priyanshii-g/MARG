package in.marg.demographic;

/**
 * Raster classification codes for {@code assam_population_density_utm46.tif}.
 * Encodes 30 m population density (persons per square kilometre) and concentric
 * demographic exposure buffers around rural villages, roadside habitations,
 * industrial labor estates, and urban municipal wards.
 */
public enum PopulationDensityClass {
    UNINHABITED_OR_BACKGROUND(0, 0.0, 25.0),
    SPARSE_RURAL_DISPERSED(1, 25.0, 150.0),
    LOW_DENSITY_VILLAGE(2, 150.0, 450.0),
    MODERATE_DENSITY_SETTLEMENT(3, 450.0, 1200.0),
    HIGH_DENSITY_POPULATION_CLUSTER(4, 1200.0, 3000.0),
    VERY_HIGH_URBAN_DENSITY_CORE(5, 3000.0, 12000.0),
    NODATA(255, 0.0, 0.0);

    private final int code;
    private final double minPersonsPerSqKm;
    private final double maxPersonsPerSqKm;

    PopulationDensityClass(int code, double minPersonsPerSqKm, double maxPersonsPerSqKm) {
        this.code = code;
        this.minPersonsPerSqKm = minPersonsPerSqKm;
        this.maxPersonsPerSqKm = maxPersonsPerSqKm;
    }

    public int code() {
        return code;
    }

    public double minPersonsPerSqKm() {
        return minPersonsPerSqKm;
    }

    public double maxPersonsPerSqKm() {
        return maxPersonsPerSqKm;
    }

    /**
     * Multi-level grade-separated interchanges (150 m radius loops) must not be sited
     * directly inside high-density population clusters or very-high-density urban cores.
     */
    public boolean isInterchangeExcludedZone() {
        return this == HIGH_DENSITY_POPULATION_CLUSTER
                || this == VERY_HIGH_URBAN_DENSITY_CORE;
    }

    public boolean isHighPopulationExposureZone() {
        return this == MODERATE_DENSITY_SETTLEMENT
                || this == HIGH_DENSITY_POPULATION_CLUSTER
                || this == VERY_HIGH_URBAN_DENSITY_CORE;
    }

    public static PopulationDensityClass fromRasterValue(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return NODATA;
        }
        int rounded = (int) Math.round(value);
        return switch (rounded) {
            case 0 -> UNINHABITED_OR_BACKGROUND;
            case 1 -> SPARSE_RURAL_DISPERSED;
            case 2 -> LOW_DENSITY_VILLAGE;
            case 3 -> MODERATE_DENSITY_SETTLEMENT;
            case 4 -> HIGH_DENSITY_POPULATION_CLUSTER;
            case 5 -> VERY_HIGH_URBAN_DENSITY_CORE;
            case 255 -> NODATA;
            default -> throw new IllegalArgumentException(
                    "Unexpected population density raster code: " + value);
        };
    }
}
