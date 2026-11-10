package in.marg.utility;

/**
 * High-voltage and extra-high-voltage (EHV) overhead utility transmission tiers
 * per Ministry of Power (GoI) RoW guidelines and Central Electricity Authority (CEA)
 * Measures relating to Safety & Electric Supply Regulations 2010 (Reg. 58).
 */
public enum UtilityCorridorTier {
    EHV_400KV_TRANSMISSION(
            "EHV_400KV_GANTRY_GUARD_CRADLE_AND_TALL_TOWER_RAISE",
            46.0,
            8.84,
            400.0),
    EHV_220KV_TRANSMISSION(
            "EHV_220KV_INSULATED_GUARD_NET_AND_TOWER_EXT",
            35.0,
            7.00,
            335.0),
    SUBTRANSMISSION_132KV(
            "HV_132KV_OVERHEAD_CROSSING_CRADLE_GUARD",
            27.0,
            6.10,
            315.0),
    DISTRIBUTION_66KV_AND_BELOW(
            "MV_66KV_PROTECTIVE_GUARD_WIRE_CRADLE",
            18.0,
            6.10,
            250.0);

    private final String crossingProtectionStructureType;
    private final double designRowWidthMeters;
    private final double minimumConductorClearanceMeters;
    private final double typicalSpanMeters;

    UtilityCorridorTier(
            String crossingProtectionStructureType,
            double designRowWidthMeters,
            double minimumConductorClearanceMeters,
            double typicalSpanMeters) {
        this.crossingProtectionStructureType = crossingProtectionStructureType;
        this.designRowWidthMeters = designRowWidthMeters;
        this.minimumConductorClearanceMeters = minimumConductorClearanceMeters;
        this.typicalSpanMeters = typicalSpanMeters;
    }

    public String crossingProtectionStructureType() {
        return crossingProtectionStructureType;
    }

    public double designRowWidthMeters() {
        return designRowWidthMeters;
    }

    public double minimumConductorClearanceMeters() {
        return minimumConductorClearanceMeters;
    }

    public double typicalSpanMeters() {
        return typicalSpanMeters;
    }
}
