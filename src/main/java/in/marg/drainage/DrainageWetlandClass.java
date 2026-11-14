package in.marg.drainage;

/**
 * Raster classification codes for {@code assam_drainage_wetland_utm46.tif} (Layer 13).
 */
public enum DrainageWetlandClass {
    WELL_DRAINED_UPLAND_OR_NONE(0, "Well-drained upland terrain outside drainage swales and wetlands"),
    WATERSHED_VALLEY_DRAINAGE_SWALE(1, "Watershed valley drainage swale and outer flow-accumulation zone"),
    LOCAL_WATERCOURSE_AND_SHEET_FLOW_ZONE(2, "Local watercourse, valley thalweg, and sheet-flow ponding zone"),
    SEASONAL_STREAM_AND_TORRENT_CORRIDOR(3, "Non-perennial seasonal hill torrent (Jan/Langso) channel and scour belt"),
    IRRIGATION_CANAL_COMMAND_ROW(4, "Existing irrigation canal channel and command embankment Right-of-Way"),
    SEASONAL_MARSH_AND_WETLAND_BUFFER(5, "Seasonal marsh depression and 120 m wetland hydrologic recharge buffer"),
    NATURAL_OR_DESIGNATED_WETLAND_CORE(6, "Natural or designated inland wetland (beel) and oxbow swamp core"),
    NODATA(255, "Outside raster extent or missing drainage data");

    private final int code;
    private final String description;

    DrainageWetlandClass(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }

    public boolean isWetlandOrMarshZone() {
        return this == SEASONAL_MARSH_AND_WETLAND_BUFFER
                || this == NATURAL_OR_DESIGNATED_WETLAND_CORE;
    }

    public boolean isActiveDrainageOrCanalChannel() {
        return this == LOCAL_WATERCOURSE_AND_SHEET_FLOW_ZONE
                || this == SEASONAL_STREAM_AND_TORRENT_CORRIDOR
                || this == IRRIGATION_CANAL_COMMAND_ROW
                || this == NATURAL_OR_DESIGNATED_WETLAND_CORE;
    }

    public boolean isInterchangeExcludedZone() {
        return this == SEASONAL_STREAM_AND_TORRENT_CORRIDOR
                || this == IRRIGATION_CANAL_COMMAND_ROW
                || this == SEASONAL_MARSH_AND_WETLAND_BUFFER
                || this == NATURAL_OR_DESIGNATED_WETLAND_CORE;
    }

    public static DrainageWetlandClass fromRasterValue(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return NODATA;
        }
        int rounded = (int) Math.round(value);
        return switch (rounded) {
            case 0 -> WELL_DRAINED_UPLAND_OR_NONE;
            case 1 -> WATERSHED_VALLEY_DRAINAGE_SWALE;
            case 2 -> LOCAL_WATERCOURSE_AND_SHEET_FLOW_ZONE;
            case 3 -> SEASONAL_STREAM_AND_TORRENT_CORRIDOR;
            case 4 -> IRRIGATION_CANAL_COMMAND_ROW;
            case 5 -> SEASONAL_MARSH_AND_WETLAND_BUFFER;
            case 6 -> NATURAL_OR_DESIGNATED_WETLAND_CORE;
            case 255 -> NODATA;
            default -> throw new IllegalArgumentException(
                    "Unexpected Layer 13 drainage/wetland raster code: " + value);
        };
    }
}
