package in.marg.building;

/**
 * Functional classification of human settlements in {@code assam_settlement_boundaries.geojson}.
 */
public enum SettlementClassification {
    RURAL(
            0.45,
            "Rural Village Habitation / Tea Garden Line / Roadside Weekly Haat",
            "RETAINING_WALL_TOE_RESTRICTION_AND_CATTLE_PEDESTRIAN_SUBWAY"),
    URBAN(
            1.15,
            "Urban Municipal Town / Census Town / Commercial Ribbon",
            "ELEVATED_VIADUCT_OR_SERVICE_ROAD_WITH_NOISE_BARRIERS"),
    INDUSTRIAL(
            0.80,
            "Industrial Estate / Cement & Limestone Complex / Tea Processing Cluster",
            "GRADE_SEPARATED_INDUSTRIAL_ACCESS_AND_REINFORCED_ROW_WALL");

    private final double defaultSeverancePenaltyFactor;
    private final String description;
    private final String defaultMitigationStructureType;

    SettlementClassification(
            double defaultSeverancePenaltyFactor,
            String description,
            String defaultMitigationStructureType) {
        this.defaultSeverancePenaltyFactor = defaultSeverancePenaltyFactor;
        this.description = description;
        this.defaultMitigationStructureType = defaultMitigationStructureType;
    }

    public double defaultSeverancePenaltyFactor() {
        return defaultSeverancePenaltyFactor;
    }

    public String description() {
        return description;
    }

    public String defaultMitigationStructureType() {
        return defaultMitigationStructureType;
    }
}
