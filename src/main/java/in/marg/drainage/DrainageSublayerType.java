package in.marg.drainage;

/**
 * The five spatial sublayers of MARG Layer 13 (Wetlands, Seasonal Streams & Drainage Network),
 * plus local watercourses within the natural drainage network.
 */
public enum DrainageSublayerType {
    WETLAND(
            "Natural and designated wetland areas (inland beels, oxbow swamps, seasonal marshes)",
            "WETLAND_PILE_VIADUCT_OR_EQUALIZER_CAUSEWAY",
            2.40),
    SEASONAL_STREAM(
            "Non-perennial monsoon hill torrents and seasonal streams (Jan / Langso / Nullah)",
            "MULTI_CELL_BOX_CULVERT_OR_MINOR_BRIDGE",
            1.35),
    NATURAL_DRAINAGE_CHANNEL(
            "Natural flow paths, valley thalwegs, and sheet-flow drainage swales",
            "SLAB_OR_SINGLE_BOX_CULVERT",
            0.95),
    IRRIGATION_CANAL(
            "Existing water infrastructure (main, branch, and distributary irrigation canals)",
            "CANAL_SYPHON_AQUEDUCT_OR_CLEAR_SPAN_BRIDGE",
            1.65),
    LOCAL_WATERCOURSE(
            "Local agricultural and foothill field watercourses",
            "PIPE_CULVERT_IRC_SP13",
            0.55),
    WATERSHED(
            "Hydrologic catchment boundaries and sub-basin divides",
            "WATERSHED_RIDGE_SIDE_DRAIN_AND_CHUTE",
            0.25);

    private final String description;
    private final String defaultInfrastructureStructure;
    private final double baseCrossingCostKmEq;

    DrainageSublayerType(
            String description,
            String defaultInfrastructureStructure,
            double baseCrossingCostKmEq) {
        this.description = description;
        this.defaultInfrastructureStructure = defaultInfrastructureStructure;
        this.baseCrossingCostKmEq = baseCrossingCostKmEq;
    }

    public String description() {
        return description;
    }

    public String defaultInfrastructureStructure() {
        return defaultInfrastructureStructure;
    }

    public double baseCrossingCostKmEq() {
        return baseCrossingCostKmEq;
    }
}
