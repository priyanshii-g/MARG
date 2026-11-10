package in.marg.building;

/**
 * Individual structure classification for 2D building footprints in
 * {@code assam_building_footprints.geojson}.
 * <p>
 * Distinguishes residential homesteads, roadside commercial shops, schools/clinics,
 * religious structures (Namghars, temples, churches), industrial mills/godowns,
 * and village community infrastructure for Right-of-Way (RoW) acquisition and
 * RFCTLARR 2013 Rehabilitation & Resettlement (R&R) social impact modeling.
 */
public enum BuildingType {
    RESIDENTIAL(
            0.28,
            "Residential Homestead / Dwelling",
            "RFCTLARR_2013_RESIDENTIAL_REHABILITATION_AND_COMPENSATION"),
    COMMERCIAL(
            0.42,
            "Commercial Shop / Weekly Bazaar Stall / Roadside Establishment",
            "COMMERCIAL_LIVELIHOOD_RESETTLEMENT_AND_SERVICE_ROAD_ACCESS"),
    INSTITUTIONAL(
            0.85,
            "School / Primary Health Center / Public Institution",
            "ELEVATED_VIADUCT_OR_ALIGNMENT_SHIFT_TO_PRESERVE_INSTITUTION"),
    RELIGIOUS(
            0.95,
            "Namghar / Temple / Church / Sacred Community Compound",
            "STRICT_REALIGNMENT_OR_RETAINING_WALL_TO_PROTECT_SACRED_SITE"),
    INDUSTRIAL(
            0.55,
            "Tea Factory / Rice & Bamboo Mill / Storage Godown",
            "INDUSTRIAL_ASSET_COMPENSATION_AND_HEAVY_VEHICLE_UNDERPASS"),
    VILLAGE_INFRASTRUCTURE(
            0.35,
            "Panchayat Hall / Community Granary / Rural Water Supply Tank",
            "COMMUNITY_INFRASTRUCTURE_RELOCATION_AND_PEDESTRIAN_UNDERPASS");

    private final double defaultDemolitionConflictKmEq;
    private final String description;
    private final String recommendedMitigationType;

    BuildingType(
            double defaultDemolitionConflictKmEq,
            String description,
            String recommendedMitigationType) {
        this.defaultDemolitionConflictKmEq = defaultDemolitionConflictKmEq;
        this.description = description;
        this.recommendedMitigationType = recommendedMitigationType;
    }

    public double defaultDemolitionConflictKmEq() {
        return defaultDemolitionConflictKmEq;
    }

    public String description() {
        return description;
    }

    public String recommendedMitigationType() {
        return recommendedMitigationType;
    }

    public boolean isHighSocialSensitivity() {
        return this == INSTITUTIONAL || this == RELIGIOUS;
    }
}
