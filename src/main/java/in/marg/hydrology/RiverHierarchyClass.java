package in.marg.hydrology;

/**
 * Functional hydraulic hierarchy of perennial rivers & streams for bridge span and cost estimation.
 */
public enum RiverHierarchyClass {
    MAJOR_PERENNIAL_RIVER("MAJOR_RIVER_HIGH_LEVEL_BRIDGE"),
    MEDIUM_PERENNIAL_RIVER("MEDIUM_RIVER_SPAN_BRIDGE"),
    MINOR_PERENNIAL_STREAM("MINOR_BRIDGE_BOX_CULVERT");

    private final String bridgeStructureType;

    RiverHierarchyClass(String bridgeStructureType) {
        this.bridgeStructureType = bridgeStructureType;
    }

    public String bridgeStructureType() {
        return bridgeStructureType;
    }
}
