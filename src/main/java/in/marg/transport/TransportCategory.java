package in.marg.transport;

/**
 * Functional hierarchy of existing linear transport infrastructure.
 */
public enum TransportCategory {
    RAILWAY("RAIL_OVER_BRIDGE_ROB", false),
    NATIONAL_HIGHWAY("HIGHWAY_FLYOVER_UNDERPASS", true),
    STATE_HIGHWAY("HIGHWAY_FLYOVER_UNDERPASS", true),
    MAJOR_DISTRICT_ROAD("VEHICULAR_UNDERPASS_VUP", false),
    LOCAL_ROAD("LIGHT_VEHICULAR_OVERPASS_LVUP", false);

    private final String defaultCrossingStructureType;
    private final boolean highCapacityInterchangeEligible;

    TransportCategory(String defaultCrossingStructureType, boolean highCapacityInterchangeEligible) {
        this.defaultCrossingStructureType = defaultCrossingStructureType;
        this.highCapacityInterchangeEligible = highCapacityInterchangeEligible;
    }

    public String defaultCrossingStructureType() {
        return defaultCrossingStructureType;
    }

    /** True for NH and SH corridors where grade-separated interchanges are spawned per Rule 2. */
    public boolean isHighCapacityInterchangeEligible() {
        return highCapacityInterchangeEligible;
    }
}
