package in.marg.settlement;

/**
 * Discrete settlement and built-up proximity classes encoded in the 30 m UTM 46N
 * settlement exposure GeoTIFF (assam_settlement_exposure_utm46.tif).
 */
public enum SettlementExposureClass {
    NONE(0),
    OUTER_BUFFER(1),
    INNER_BUFFER(2),
    RURAL_CORE(3),
    URBAN_CORE(4),
    NODATA(255);

    private final int rasterCode;

    SettlementExposureClass(int rasterCode) {
        this.rasterCode = rasterCode;
    }

    public int rasterCode() {
        return rasterCode;
    }

    public static SettlementExposureClass fromRasterValue(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return NODATA;
        }
        int code = (int) Math.round(value);
        return switch (code) {
            case 0 -> NONE;
            case 1 -> OUTER_BUFFER;
            case 2 -> INNER_BUFFER;
            case 3 -> RURAL_CORE;
            case 4 -> URBAN_CORE;
            case 255 -> NODATA;
            default -> throw new IllegalStateException("Unexpected settlement raster code: " + code);
        };
    }
}
