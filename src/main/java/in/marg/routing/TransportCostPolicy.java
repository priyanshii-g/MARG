package in.marg.routing;

import in.marg.transport.TransportCategory;
import in.marg.transport.TransportCorridorClass;

import java.util.EnumMap;
import java.util.Map;

/**
 * Spatial cost policy for existing road & railway corridors, grade-separated crossings
 * (ROB / Flyover / VUP), approach-angle skew multipliers, and parallel ROW encroachment.
 */
public class TransportCostPolicy {

    private final Map<TransportCorridorClass, Double> nodePenalties;
    private final Map<TransportCategory, Double> crossingBaseCostKmEq;
    private final double skewAnglePenaltyFactor;
    private final double parallelAcutePenalty;
    private final double parallelProximityRadiusMeters;
    private final boolean blockNoData;

    public TransportCostPolicy(
            Map<TransportCorridorClass, Double> nodePenalties,
            Map<TransportCategory, Double> crossingBaseCostKmEq,
            double skewAnglePenaltyFactor,
            double parallelAcutePenalty,
            double parallelProximityRadiusMeters,
            boolean blockNoData) {
        this.nodePenalties = new EnumMap<>(TransportCorridorClass.class);
        this.nodePenalties.putAll(nodePenalties);
        this.crossingBaseCostKmEq = new EnumMap<>(TransportCategory.class);
        this.crossingBaseCostKmEq.putAll(crossingBaseCostKmEq);
        this.skewAnglePenaltyFactor = Math.max(0.0, skewAnglePenaltyFactor);
        this.parallelAcutePenalty = Math.max(0.0, parallelAcutePenalty);
        this.parallelProximityRadiusMeters = Math.max(30.0, parallelProximityRadiusMeters);
        this.blockNoData = blockNoData;
    }

    public static TransportCostPolicy defaultPolicy() {
        Map<TransportCorridorClass, Double> nodeDefaults = new EnumMap<>(TransportCorridorClass.class);
        nodeDefaults.put(TransportCorridorClass.NONE, 0.0);
        nodeDefaults.put(TransportCorridorClass.ROAD_PARALLEL_BUFFER, 1.20);
        nodeDefaults.put(TransportCorridorClass.MDR_CORRIDOR, 2.00);
        nodeDefaults.put(TransportCorridorClass.SH_CORRIDOR, 3.00);
        nodeDefaults.put(TransportCorridorClass.NH_CORRIDOR, 4.00);
        nodeDefaults.put(TransportCorridorClass.RAILWAY_BUFFER, 2.00);
        nodeDefaults.put(TransportCorridorClass.RAILWAY_CORRIDOR, 6.00);
        nodeDefaults.put(TransportCorridorClass.NODATA, 0.0);

        Map<TransportCategory, Double> crossingDefaults = new EnumMap<>(TransportCategory.class);
        crossingDefaults.put(TransportCategory.RAILWAY, 5.00);
        crossingDefaults.put(TransportCategory.NATIONAL_HIGHWAY, 3.00);
        crossingDefaults.put(TransportCategory.STATE_HIGHWAY, 1.80);
        crossingDefaults.put(TransportCategory.MAJOR_DISTRICT_ROAD, 1.00);
        crossingDefaults.put(TransportCategory.LOCAL_ROAD, 0.50);

        return new TransportCostPolicy(
                nodeDefaults,
                crossingDefaults,
                1.25,
                2.00,
                150.0,
                false);
    }

    public double nodePenalty(TransportCorridorClass corridorClass) {
        return nodePenalties.getOrDefault(corridorClass, 0.0);
    }

    public double baseCrossingCostKmEq(TransportCategory category) {
        return crossingBaseCostKmEq.getOrDefault(category, 0.75);
    }

    /**
     * Bridge skew multiplier as a function of sin(theta) in [0, 1], where theta = 90 deg (sin=1)
     * is an ideal perpendicular crossing (multiplier = 1.0) and acute crossings (sin -> 0)
     * require longer skew bridge spans and incur heavy penalty.
     */
    public double skewBridgeMultiplier(double sinAngle) {
        double clampedSin = Math.max(0.0, Math.min(1.0, sinAngle));
        double denom = Math.max(clampedSin, 0.25);
        return 1.0 + skewAnglePenaltyFactor * ((1.0 - clampedSin) / denom);
    }

    public double crossingStructureCostKmEq(TransportCategory category, double sinAngle) {
        return baseCrossingCostKmEq(category) * skewBridgeMultiplier(sinAngle);
    }

    public double skewAnglePenaltyFactor() {
        return skewAnglePenaltyFactor;
    }

    public double parallelAcutePenalty() {
        return parallelAcutePenalty;
    }

    public double parallelProximityRadiusMeters() {
        return parallelProximityRadiusMeters;
    }

    public boolean blockNoData() {
        return blockNoData;
    }

    public Map<TransportCorridorClass, Double> nodePenalties() {
        return Map.copyOf(nodePenalties);
    }

    public Map<TransportCategory, Double> crossingBaseCostKmEq() {
        return Map.copyOf(crossingBaseCostKmEq);
    }
}
