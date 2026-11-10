package in.marg.hydrology;

/**
 * Raster classification codes for {@code assam_water_bodies_utm46.tif}.
 */
public enum WaterBodyClass {
    NONE(0),
    RIPARIAN_OUTER_BUFFER(1),
    RIPARIAN_INNER_BUFFER(2),
    MINOR_PERENNIAL_STREAM(3),
    MEDIUM_PERENNIAL_RIVER(4),
    INLAND_WETLAND_WATERBODY(5),
    MAJOR_PERENNIAL_RIVER(6),
    NODATA(255);

    private final int code;

    WaterBodyClass(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean isChannelOrWetlandOrInnerScourZone() {
        return this == RIPARIAN_INNER_BUFFER
                || this == MINOR_PERENNIAL_STREAM
                || this == MEDIUM_PERENNIAL_RIVER
                || this == INLAND_WETLAND_WATERBODY
                || this == MAJOR_PERENNIAL_RIVER;
    }

    public static WaterBodyClass fromRasterValue(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return NODATA;
        }
        int rounded = (int) Math.round(value);
        return switch (rounded) {
            case 0 -> NONE;
            case 1 -> RIPARIAN_OUTER_BUFFER;
            case 2 -> RIPARIAN_INNER_BUFFER;
            case 3 -> MINOR_PERENNIAL_STREAM;
            case 4 -> MEDIUM_PERENNIAL_RIVER;
            case 5 -> INLAND_WETLAND_WATERBODY;
            case 6 -> MAJOR_PERENNIAL_RIVER;
            case 255 -> NODATA;
            default -> throw new IllegalArgumentException(
                    "Unexpected hydrology water-body raster code: " + value);
        };
    }
}
