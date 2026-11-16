package in.marg.district;

import in.marg.model.GeoPoint;

import java.util.List;

/**
 * Record representing an Indian Administrative Unit (District, Tehsil, Sub-Division, or Mandi Hub).
 */
public record DistrictRecord(
        String id,
        String name,
        String adminLevel,
        String parentDistrict,
        String state,
        String stateCode,
        String headquarters,
        double lat,
        double lon,
        int tier,
        List<String> aliases
) {
    public DistrictRecord(
            String id,
            String name,
            String state,
            String stateCode,
            String headquarters,
            double lat,
            double lon,
            int tier,
            List<String> aliases
    ) {
        this(id, name, "DISTRICT", name, state, stateCode, headquarters, lat, lon, tier, aliases);
    }

    public GeoPoint toGeoPoint() {
        return new GeoPoint(lat, lon);
    }

    public boolean isDistrict() {
        return "DISTRICT".equalsIgnoreCase(adminLevel);
    }

    public boolean isTehsil() {
        return "TEHSIL".equalsIgnoreCase(adminLevel)
                || "SUBDIVISION".equalsIgnoreCase(adminLevel)
                || "TALUK".equalsIgnoreCase(adminLevel)
                || "MANDAL".equalsIgnoreCase(adminLevel)
                || "TOWN".equalsIgnoreCase(adminLevel);
    }

    public String displayLabel() {
        if (isTehsil() && parentDistrict != null && !parentDistrict.equalsIgnoreCase(name)) {
            String levelLabel = adminLevel != null && !adminLevel.isBlank()
                    ? adminLevel.substring(0, 1).toUpperCase() + adminLevel.substring(1).toLowerCase()
                    : "Tehsil";
            return name + " (" + levelLabel + ", " + parentDistrict + ", " + stateCode + ")";
        }
        return name + " (" + stateCode + ")";
    }
}

