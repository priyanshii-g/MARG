package in.marg.routing;

import in.marg.utility.UtilityCorridorClass;
import in.marg.utility.UtilityCorridorTier;

import java.util.EnumMap;
import java.util.Map;

/**
 * Spatial cost policy for high-voltage transmission lines, Right-of-Way (RoW) safety buffers,
 * crossing approach skew angle multipliers, transmission tower foundation proximity penalties,
 * and longitudinal utility RoW co-alignment avoidance.
 */
public class UtilityCostPolicy {

    private final Map<UtilityCorridorClass, Double> nodePenalties;
    private final Map<UtilityCorridorTier, Double> crossingProtectionBaseCostKmEq;
    private final double crossingSkewPenaltyFactor;
    private final double parallelUtilityRowPenalty;
    private final double parallelUtilityRadiusMeters;
    private final double towerProximityThresholdMeters;
    private final double towerProximitySurchargeFactor;
    private final boolean blockSubstationsAndTowers;
    private final boolean blockNoData;

    public UtilityCostPolicy(
            Map<UtilityCorridorClass, Double> nodePenalties,
            Map<UtilityCorridorTier, Double> crossingProtectionBaseCostKmEq,
            double crossingSkewPenaltyFactor,
            double parallelUtilityRowPenalty,
            double parallelUtilityRadiusMeters,
            double towerProximityThresholdMeters,
            double towerProximitySurchargeFactor,
            boolean blockSubstationsAndTowers,
            boolean blockNoData) {
        this.nodePenalties = new EnumMap<>(UtilityCorridorClass.class);
        this.nodePenalties.putAll(nodePenalties);
        this.crossingProtectionBaseCostKmEq = new EnumMap<>(UtilityCorridorTier.class);
        this.crossingProtectionBaseCostKmEq.putAll(crossingProtectionBaseCostKmEq);
        this.crossingSkewPenaltyFactor = Math.max(0.0, crossingSkewPenaltyFactor);
        this.parallelUtilityRowPenalty = Math.max(0.0, parallelUtilityRowPenalty);
        this.parallelUtilityRadiusMeters = Math.max(30.0, parallelUtilityRadiusMeters);
        this.towerProximityThresholdMeters = Math.max(10.0, towerProximityThresholdMeters);
        this.towerProximitySurchargeFactor = Math.max(0.0, towerProximitySurchargeFactor);
        this.blockSubstationsAndTowers = blockSubstationsAndTowers;
        this.blockNoData = blockNoData;
    }

    public static UtilityCostPolicy defaultPolicy() {
        Map<UtilityCorridorClass, Double> nodeDefaults = new EnumMap<>(UtilityCorridorClass.class);
        nodeDefaults.put(UtilityCorridorClass.NONE, 0.0);
        nodeDefaults.put(UtilityCorridorClass.UTILITY_PROXIMITY_SETBACK_ZONE, 0.15);
        nodeDefaults.put(UtilityCorridorClass.DISTRIBUTION_LINE_ROW, 0.50);
        nodeDefaults.put(UtilityCorridorClass.SUBTRANSMISSION_132KV_ROW, 1.10);
        nodeDefaults.put(UtilityCorridorClass.EHV_220KV_ROW, 1.90);
        nodeDefaults.put(UtilityCorridorClass.EHV_400KV_ROW, 2.80);
        nodeDefaults.put(UtilityCorridorClass.SUBSTATION_OR_TOWER_FOOTPRINT, 4.50);
        nodeDefaults.put(UtilityCorridorClass.NODATA, 0.0);

        Map<UtilityCorridorTier, Double> crossingDefaults = new EnumMap<>(UtilityCorridorTier.class);
        crossingDefaults.put(UtilityCorridorTier.EHV_400KV_TRANSMISSION, 2.80);
        crossingDefaults.put(UtilityCorridorTier.EHV_220KV_TRANSMISSION, 2.00);
        crossingDefaults.put(UtilityCorridorTier.SUBTRANSMISSION_132KV, 1.30);
        crossingDefaults.put(UtilityCorridorTier.DISTRIBUTION_66KV_AND_BELOW, 0.75);

        return new UtilityCostPolicy(
                nodeDefaults,
                crossingDefaults,
                1.40,
                2.50,
                100.0,
                50.0,
                0.50,
                false,
                false);
    }

    public double nodePenalty(UtilityCorridorClass corridorClass) {
        return nodePenalties.getOrDefault(corridorClass, 0.0);
    }

    public double baseCrossingCostKmEq(UtilityCorridorTier tier) {
        return crossingProtectionBaseCostKmEq.getOrDefault(tier, 1.50);
    }

    /**
     * Crossing angle multiplier: perpendicular crossing (90 deg, sin = 1.0) minimizes
     * the highway length exposed directly under charged conductors.
     * Acute crossing angles drag the road under conductors and multiply protective cradle spans.
     */
    public double crossingSkewMultiplier(double sinAngle) {
        double clampedSin = Math.max(0.0, Math.min(1.0, sinAngle));
        double denom = Math.max(clampedSin, 0.25);
        return 1.0 + crossingSkewPenaltyFactor * ((1.0 - clampedSin) / denom);
    }

    /**
     * Total crossing cost including skew penalty and tower proximity surcharge.
     */
    public double utilityCrossingProtectionCostKmEq(
            UtilityCorridorTier tier,
            double sinAngle,
            double nearestTowerDistanceMeters) {
        double base = baseCrossingCostKmEq(tier);
        double skew = crossingSkewMultiplier(sinAngle);
        double towerFactor = 1.0;
        if (nearestTowerDistanceMeters <= towerProximityThresholdMeters) {
            double ratio = 1.0 - (Math.max(0.0, nearestTowerDistanceMeters) / towerProximityThresholdMeters);
            towerFactor = 1.0 + (towerProximitySurchargeFactor * ratio);
        }
        return base * skew * towerFactor;
    }

    public double crossingSkewPenaltyFactor() {
        return crossingSkewPenaltyFactor;
    }

    public double parallelUtilityRowPenalty() {
        return parallelUtilityRowPenalty;
    }

    public double parallelUtilityRadiusMeters() {
        return parallelUtilityRadiusMeters;
    }

    public double towerProximityThresholdMeters() {
        return towerProximityThresholdMeters;
    }

    public double towerProximitySurchargeFactor() {
        return towerProximitySurchargeFactor;
    }

    public boolean blockSubstationsAndTowers() {
        return blockSubstationsAndTowers;
    }

    public boolean blockNoData() {
        return blockNoData;
    }

    public Map<UtilityCorridorClass, Double> nodePenalties() {
        return Map.copyOf(nodePenalties);
    }

    public Map<UtilityCorridorTier, Double> crossingProtectionBaseCostKmEq() {
        return Map.copyOf(crossingProtectionBaseCostKmEq);
    }
}
