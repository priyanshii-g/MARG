package in.marg.transport;

/**
 * Raster classification codes for {@code assam_transport_network_utm46.tif}.
 */
public enum TransportCorridorClass {
    NONE(0),
    ROAD_PARALLEL_BUFFER(1),
    MDR_CORRIDOR(2),
    SH_CORRIDOR(3),
    NH_CORRIDOR(4),
    RAILWAY_BUFFER(5),
    RAILWAY_CORRIDOR(6),
    NODATA(255);

    private final int code;

    TransportCorridorClass(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static TransportCorridorClass fromRasterValue(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return NODATA;
        }
        int rounded = (int) Math.round(value);
        return switch (rounded) {
            case 0 -> NONE;
            case 1 -> ROAD_PARALLEL_BUFFER;
            case 2 -> MDR_CORRIDOR;
            case 3 -> SH_CORRIDOR;
            case 4 -> NH_CORRIDOR;
            case 5 -> RAILWAY_BUFFER;
            case 6 -> RAILWAY_CORRIDOR;
            case 255 -> NODATA;
            default -> throw new IllegalArgumentException(
                    "Unexpected transport network raster code: " + value);
        };
    }
}
