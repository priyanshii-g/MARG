package in.marg.routing;

import in.marg.wildlife.WildlifeCorridorTier;
import in.marg.wildlife.WildlifeZoneClass;

import java.util.EnumMap;
import java.util.Map;

/**
 * Spatial cost policy for Eco-Sensitive Zones (ESZ), wildlife migration corridors,
 * WII-compliant elevated wildlife viaducts / animal underpasses, barrier skew-angle multipliers,
 * and longitudinal corridor fragmentation avoidance.
 */
public class WildlifeCostPolicy {

    private final Map<WildlifeZoneClass, Double> nodePenalties;
    private final Map<WildlifeCorridorTier, Double> passageBaseCostKmEq;
    private final double barrierSkewPenaltyFactor;
    private final double parallelCorridorPenalty;
    private final double parallelCorridorRadiusMeters;
    private final boolean blockCriticalCorridors;
    private final boolean blockNoData;

    public WildlifeCostPolicy(
            Map<WildlifeZoneClass, Double> nodePenalties,
            Map<WildlifeCorridorTier, Double> passageBaseCostKmEq,
            double barrierSkewPenaltyFactor,
            double parallelCorridorPenalty,
            double parallelCorridorRadiusMeters,
            boolean blockCriticalCorridors,
            boolean blockNoData) {
        this.nodePenalties = new EnumMap<>(WildlifeZoneClass.class);
        this.nodePenalties.putAll(nodePenalties);
        this.passageBaseCostKmEq = new EnumMap<>(WildlifeCorridorTier.class);
        this.passageBaseCostKmEq.putAll(passageBaseCostKmEq);
        this.barrierSkewPenaltyFactor = Math.max(0.0, barrierSkewPenaltyFactor);
        this.parallelCorridorPenalty = Math.max(0.0, parallelCorridorPenalty);
        this.parallelCorridorRadiusMeters = Math.max(50.0, parallelCorridorRadiusMeters);
        this.blockCriticalCorridors = blockCriticalCorridors;
        this.blockNoData = blockNoData;
    }

    public static WildlifeCostPolicy defaultPolicy() {
        Map<WildlifeZoneClass, Double> nodeDefaults = new EnumMap<>(WildlifeZoneClass.class);
        nodeDefaults.put(WildlifeZoneClass.NONE, 0.0);
        nodeDefaults.put(WildlifeZoneClass.ESZ_OUTER_TRANSITION_ZONE, 0.35);
        nodeDefaults.put(WildlifeZoneClass.WILDLIFE_CORRIDOR_FRINGE_BUFFER, 0.75);
        nodeDefaults.put(WildlifeZoneClass.ESZ_INNER_BUFFER_ZONE, 1.15);
        nodeDefaults.put(WildlifeZoneClass.REGIONAL_FOREST_STEPPING_CORRIDOR, 1.65);
        nodeDefaults.put(WildlifeZoneClass.CRITICAL_MIGRATION_CORRIDOR_CORE, 2.90);
        nodeDefaults.put(WildlifeZoneClass.PROTECTED_SANCTUARY_HABITAT, 5.00);
        nodeDefaults.put(WildlifeZoneClass.NODATA, 0.0);

        Map<WildlifeCorridorTier, Double> passageDefaults = new EnumMap<>(WildlifeCorridorTier.class);
        passageDefaults.put(WildlifeCorridorTier.CRITICAL_ELEPHANT_RHINO_CORRIDOR, 3.10);
        passageDefaults.put(WildlifeCorridorTier.INTER_SANCTUARY_ELEPHANT_CORRIDOR, 1.85);
        passageDefaults.put(WildlifeCorridorTier.LOCAL_WILDLIFE_DISPERSAL_AXIS, 0.95);

        return new WildlifeCostPolicy(
                nodeDefaults,
                passageDefaults,
                1.45,
                2.60,
                280.0,
                false,
                false);
    }

    public double nodePenalty(WildlifeZoneClass zoneClass) {
        return nodePenalties.getOrDefault(zoneClass, 0.0);
    }

    public double basePassageCostKmEq(WildlifeCorridorTier tier) {
        return passageBaseCostKmEq.getOrDefault(tier, 1.50);
    }

    /**
     * Linear barrier & elevated viaduct span multiplier as a function of sin(theta) in [0, 1].
     * Perpendicular crossings (90 deg, sin = 1.0) minimize habitat fragmentation and viaduct span;
     * oblique crossings drastically lengthen the required elevated wildlife viaduct.
     */
    public double barrierSkewMultiplier(double sinAngle) {
        double clampedSin = Math.max(0.0, Math.min(1.0, sinAngle));
        double denom = Math.max(clampedSin, 0.25);
        return 1.0 + barrierSkewPenaltyFactor * ((1.0 - clampedSin) / denom);
    }

    public double wildlifePassageCostKmEq(WildlifeCorridorTier tier, double sinAngle) {
        return basePassageCostKmEq(tier) * barrierSkewMultiplier(sinAngle);
    }

    public double barrierSkewPenaltyFactor() {
        return barrierSkewPenaltyFactor;
    }

    public double parallelCorridorPenalty() {
        return parallelCorridorPenalty;
    }

    public double parallelCorridorRadiusMeters() {
        return parallelCorridorRadiusMeters;
    }

    public boolean blockCriticalCorridors() {
        return blockCriticalCorridors;
    }

    public boolean blockNoData() {
        return blockNoData;
    }

    public Map<WildlifeZoneClass, Double> nodePenalties() {
        return Map.copyOf(nodePenalties);
    }

    public Map<WildlifeCorridorTier, Double> passageBaseCostKmEq() {
        return Map.copyOf(passageBaseCostKmEq);
    }
}
