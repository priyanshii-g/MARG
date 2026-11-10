package in.marg.routing;

import in.marg.geotech.GeotechHazardClass;
import in.marg.geotech.GeotechHazardTier;

import java.util.EnumMap;
import java.util.Map;

/**
 * Spatial cost policy for Soil Bearing Capacity (CBR / SBC), Seismic Zone V alluvial liquefaction,
 * hillslope landslide susceptibility, tectonic fault/scarp traverse skew multipliers,
 * and longitudinal side-hill toe-undercutting avoidance.
 */
public class GeotechCostPolicy {

    private final Map<GeotechHazardClass, Double> nodePenalties;
    private final Map<GeotechHazardTier, Double> mitigationBaseCostKmEq;
    private final double scarpSkewPenaltyFactor;
    private final double parallelScarpUndercutPenalty;
    private final double parallelScarpRadiusMeters;
    private final boolean blockActiveLandslideZones;
    private final boolean blockNoData;

    public GeotechCostPolicy(
            Map<GeotechHazardClass, Double> nodePenalties,
            Map<GeotechHazardTier, Double> mitigationBaseCostKmEq,
            double scarpSkewPenaltyFactor,
            double parallelScarpUndercutPenalty,
            double parallelScarpRadiusMeters,
            boolean blockActiveLandslideZones,
            boolean blockNoData) {
        this.nodePenalties = new EnumMap<>(GeotechHazardClass.class);
        this.nodePenalties.putAll(nodePenalties);
        this.mitigationBaseCostKmEq = new EnumMap<>(GeotechHazardTier.class);
        this.mitigationBaseCostKmEq.putAll(mitigationBaseCostKmEq);
        this.scarpSkewPenaltyFactor = Math.max(0.0, scarpSkewPenaltyFactor);
        this.parallelScarpUndercutPenalty = Math.max(0.0, parallelScarpUndercutPenalty);
        this.parallelScarpRadiusMeters = Math.max(50.0, parallelScarpRadiusMeters);
        this.blockActiveLandslideZones = blockActiveLandslideZones;
        this.blockNoData = blockNoData;
    }

    public static GeotechCostPolicy defaultPolicy() {
        Map<GeotechHazardClass, Double> nodeDefaults = new EnumMap<>(GeotechHazardClass.class);
        nodeDefaults.put(GeotechHazardClass.COMPETENT_BEDROCK_STABLE_SUBGRADE, 0.0);
        nodeDefaults.put(GeotechHazardClass.WEATHERED_RESIDUAL_MODERATE_SOIL, 0.25);
        nodeDefaults.put(GeotechHazardClass.SOFT_ALLUVIAL_COMPRESSIBLE_CLAY, 0.85);
        nodeDefaults.put(GeotechHazardClass.MODERATE_COLLUVIAL_LANDSLIDE_SUSCEPTIBILITY, 1.20);
        nodeDefaults.put(GeotechHazardClass.LIQUEFIABLE_ORGANIC_MARSH_SUBGRADE, 2.10);
        nodeDefaults.put(GeotechHazardClass.HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP, 2.65);
        nodeDefaults.put(GeotechHazardClass.ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE, 4.20);
        nodeDefaults.put(GeotechHazardClass.NODATA, 0.0);

        Map<GeotechHazardTier, Double> mitigationDefaults = new EnumMap<>(GeotechHazardTier.class);
        mitigationDefaults.put(GeotechHazardTier.ACTIVE_FAULT_SHEAR_ZONE, 2.60);
        mitigationDefaults.put(GeotechHazardTier.UNSTABLE_COLLUVIAL_LANDSLIDE_SCARP, 1.70);
        mitigationDefaults.put(GeotechHazardTier.LIQUEFIABLE_ALLUVIAL_FAULT_TROUGH, 1.15);

        return new GeotechCostPolicy(
                nodeDefaults,
                mitigationDefaults,
                1.40,
                2.50,
                260.0,
                false,
                false);
    }

    public double nodePenalty(GeotechHazardClass hazardClass) {
        return nodePenalties.getOrDefault(hazardClass, 0.0);
    }

    public double baseMitigationCostKmEq(GeotechHazardTier tier) {
        return mitigationBaseCostKmEq.getOrDefault(tier, 1.50);
    }

    /**
     * Scarp / fault traverse length & retaining-wall surcharge multiplier as a function of sin(theta).
     * Perpendicular crossings (90 deg, sin = 1.0) minimize traverse length through crushed fault gouge
     * or unstable colluvium; acute side-hill cuts multiply stabilization length and slide risk.
     */
    public double scarpSkewMultiplier(double sinAngle) {
        double clampedSin = Math.max(0.0, Math.min(1.0, sinAngle));
        double denom = Math.max(clampedSin, 0.25);
        return 1.0 + scarpSkewPenaltyFactor * ((1.0 - clampedSin) / denom);
    }

    public double geotechMitigationCostKmEq(GeotechHazardTier tier, double sinAngle) {
        return baseMitigationCostKmEq(tier) * scarpSkewMultiplier(sinAngle);
    }

    public double scarpSkewPenaltyFactor() {
        return scarpSkewPenaltyFactor;
    }

    public double parallelScarpUndercutPenalty() {
        return parallelScarpUndercutPenalty;
    }

    public double parallelScarpRadiusMeters() {
        return parallelScarpRadiusMeters;
    }

    public boolean blockActiveLandslideZones() {
        return blockActiveLandslideZones;
    }

    public boolean blockNoData() {
        return blockNoData;
    }

    public Map<GeotechHazardClass, Double> nodePenalties() {
        return Map.copyOf(nodePenalties);
    }

    public Map<GeotechHazardTier, Double> mitigationBaseCostKmEq() {
        return Map.copyOf(mitigationBaseCostKmEq);
    }
}
