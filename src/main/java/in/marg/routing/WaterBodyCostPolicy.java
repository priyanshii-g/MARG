package in.marg.routing;

import in.marg.hydrology.RiverHierarchyClass;
import in.marg.hydrology.WaterBodyClass;

import java.util.EnumMap;
import java.util.Map;

/**
 * Spatial cost policy for perennial rivers, inland wetlands/beels, riparian scour buffers,
 * river bridge spans, hydraulic skew-angle multipliers, and parallel riverbank encroachment.
 */
public class WaterBodyCostPolicy {

    private final Map<WaterBodyClass, Double> nodePenalties;
    private final Map<RiverHierarchyClass, Double> bridgeBaseCostKmEq;
    private final double hydraulicSkewPenaltyFactor;
    private final double parallelBankPenalty;
    private final double parallelBankRadiusMeters;
    private final boolean blockWetlands;
    private final boolean blockNoData;

    public WaterBodyCostPolicy(
            Map<WaterBodyClass, Double> nodePenalties,
            Map<RiverHierarchyClass, Double> bridgeBaseCostKmEq,
            double hydraulicSkewPenaltyFactor,
            double parallelBankPenalty,
            double parallelBankRadiusMeters,
            boolean blockWetlands,
            boolean blockNoData) {
        this.nodePenalties = new EnumMap<>(WaterBodyClass.class);
        this.nodePenalties.putAll(nodePenalties);
        this.bridgeBaseCostKmEq = new EnumMap<>(RiverHierarchyClass.class);
        this.bridgeBaseCostKmEq.putAll(bridgeBaseCostKmEq);
        this.hydraulicSkewPenaltyFactor = Math.max(0.0, hydraulicSkewPenaltyFactor);
        this.parallelBankPenalty = Math.max(0.0, parallelBankPenalty);
        this.parallelBankRadiusMeters = Math.max(30.0, parallelBankRadiusMeters);
        this.blockWetlands = blockWetlands;
        this.blockNoData = blockNoData;
    }

    public static WaterBodyCostPolicy defaultPolicy() {
        Map<WaterBodyClass, Double> nodeDefaults = new EnumMap<>(WaterBodyClass.class);
        nodeDefaults.put(WaterBodyClass.NONE, 0.0);
        nodeDefaults.put(WaterBodyClass.RIPARIAN_OUTER_BUFFER, 0.35);
        nodeDefaults.put(WaterBodyClass.RIPARIAN_INNER_BUFFER, 0.95);
        nodeDefaults.put(WaterBodyClass.MINOR_PERENNIAL_STREAM, 1.60);
        nodeDefaults.put(WaterBodyClass.MEDIUM_PERENNIAL_RIVER, 2.60);
        nodeDefaults.put(WaterBodyClass.INLAND_WETLAND_WATERBODY, 3.40);
        nodeDefaults.put(WaterBodyClass.MAJOR_PERENNIAL_RIVER, 4.00);
        nodeDefaults.put(WaterBodyClass.NODATA, 0.0);

        Map<RiverHierarchyClass, Double> bridgeDefaults = new EnumMap<>(RiverHierarchyClass.class);
        bridgeDefaults.put(RiverHierarchyClass.MAJOR_PERENNIAL_RIVER, 2.80);
        bridgeDefaults.put(RiverHierarchyClass.MEDIUM_PERENNIAL_RIVER, 1.45);
        bridgeDefaults.put(RiverHierarchyClass.MINOR_PERENNIAL_STREAM, 0.65);

        return new WaterBodyCostPolicy(
                nodeDefaults,
                bridgeDefaults,
                1.40,
                2.40,
                180.0,
                false,
                false);
    }

    public double nodePenalty(WaterBodyClass waterClass) {
        return nodePenalties.getOrDefault(waterClass, 0.0);
    }

    public double baseBridgeCostKmEq(RiverHierarchyClass riverClass) {
        return bridgeBaseCostKmEq.getOrDefault(riverClass, 1.0);
    }

    /**
     * Hydraulic scour & skew bridge span multiplier as a function of sin(theta) in [0, 1].
     * Perpendicular river crossings (90 deg, sin = 1.0) have multiplier 1.0; acute crossings
     * increase deck span and pier hydraulic scour protection cost steeply.
     */
    public double hydraulicSkewMultiplier(double sinAngle) {
        double clampedSin = Math.max(0.0, Math.min(1.0, sinAngle));
        double denom = Math.max(clampedSin, 0.25);
        return 1.0 + hydraulicSkewPenaltyFactor * ((1.0 - clampedSin) / denom);
    }

    public double riverBridgeCostKmEq(RiverHierarchyClass riverClass, double sinAngle) {
        return baseBridgeCostKmEq(riverClass) * hydraulicSkewMultiplier(sinAngle);
    }

    public double hydraulicSkewPenaltyFactor() {
        return hydraulicSkewPenaltyFactor;
    }

    public double parallelBankPenalty() {
        return parallelBankPenalty;
    }

    public double parallelBankRadiusMeters() {
        return parallelBankRadiusMeters;
    }

    public boolean blockWetlands() {
        return blockWetlands;
    }

    public boolean blockNoData() {
        return blockNoData;
    }

    public Map<WaterBodyClass, Double> nodePenalties() {
        return Map.copyOf(nodePenalties);
    }

    public Map<RiverHierarchyClass, Double> bridgeBaseCostKmEq() {
        return Map.copyOf(bridgeBaseCostKmEq);
    }
}
