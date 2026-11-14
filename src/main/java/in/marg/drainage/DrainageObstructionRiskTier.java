package in.marg.drainage;

/**
 * Classification of highway embankment drainage obstruction and hydrological severance risk
 * (per IRC:SP:42 Guidelines on Road Drainage and IRC:SP:13 Small Bridges and Culverts).
 */
public enum DrainageObstructionRiskTier {
    LOW_OBSTRUCTION_RISK(
            1.00,
            "STANDARD_ROADSIDE_TOE_DRAIN_AND_PIPE_CULVERT"),
    MODERATE_SHEET_FLOW_PONDING_RISK(
            1.28,
            "EQUALIZER_PIPE_CULVERTS_AND_INTERCEPTOR_DITCH"),
    HIGH_THALWEG_EMBANKMENT_DAMMING_RISK(
            1.65,
            "MULTI_CELL_BOX_CULVERT_WITH_ENERGY_DISSIPATOR_BASIN"),
    CRITICAL_WETLAND_OR_CANAL_SEVERANCE_RISK(
            2.15,
            "ELEVATED_PILE_VIADUCT_OR_CLEAR_SPAN_CANAL_SYPHON_AQUEDUCT");

    private final double obstructionRiskMultiplier;
    private final String recommendedDrainageMitigation;

    DrainageObstructionRiskTier(
            double obstructionRiskMultiplier,
            String recommendedDrainageMitigation) {
        this.obstructionRiskMultiplier = obstructionRiskMultiplier;
        this.recommendedDrainageMitigation = recommendedDrainageMitigation;
    }

    public double obstructionRiskMultiplier() {
        return obstructionRiskMultiplier;
    }

    public String recommendedDrainageMitigation() {
        return recommendedDrainageMitigation;
    }
}
