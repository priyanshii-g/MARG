package in.marg.geotech;

/**
 * Geological & geotechnical hazard hierarchy for tectonic shear zones, unstable landslide scarps,
 * and Seismic Zone V liquefiable alluvial troughs per GSI NLSM, IRC:37, and BIS IS:1893 guidelines.
 */
public enum GeotechHazardTier {
    ACTIVE_FAULT_SHEAR_ZONE("SEISMIC_SHEAR_ZONE_PILED_VIADUCT_OR_ANCHORED_CUT"),
    UNSTABLE_COLLUVIAL_LANDSLIDE_SCARP("ROCK_BOLTED_ANCHORED_RETAINING_WALL"),
    LIQUEFIABLE_ALLUVIAL_FAULT_TROUGH("STONE_COLUMN_PVD_LIQUEFACTION_MITIGATION");

    private final String mitigationStructureType;

    GeotechHazardTier(String mitigationStructureType) {
        this.mitigationStructureType = mitigationStructureType;
    }

    public String mitigationStructureType() {
        return mitigationStructureType;
    }
}
