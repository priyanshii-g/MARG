package in.marg.routing;

import in.marg.config.UtilityRoutingProperties;
import in.marg.model.GeoPoint;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.utility.UtilityCorridorClass;
import in.marg.utility.UtilityNetworkVectorIndex;
import in.marg.utility.UtilityRasterProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spatial cost layer for High-Voltage / Extra-High-Voltage (EHV) Power Transmission Lines,
 * Substation Yards, Transmission Tower Foundations, and RoW Safety Setbacks:
 * <ul>
 *   <li>Node assessment: penalizes overhead line RoWs, electrical induction setback buffers,
 *       and substation/tower foundation exclusion zones (or hard-blocks them when
 *       {@code blockSubstationsAndTowers=true}).</li>
 *   <li>Edge assessment: detects exact 2D crossings of overhead transmission lines, charges
 *       protective guard cradle / gantry / line-raising costs scaled by approach skew angle
 *       (90 deg optimal perpendicular traverse) and tower proximity clearance, and penalizes
 *       longitudinal highway co-alignment parallel within utility RoW corridors.</li>
 * </ul>
 */
@Component
public class UtilityCorridorCostLayer implements CostLayer {

    private static final String SOURCE = "utility:transmission-and-corridors";
    private static final double PARALLEL_COS_THRESHOLD = 0.70710678; // cos(45 deg)

    private final UtilityRasterProvider rasterProvider;
    private final UtilityNetworkVectorIndex vectorIndex;
    private final CoordinateProjection projection;
    private final UtilityRoutingProperties properties;

    @Autowired
    public UtilityCorridorCostLayer(
            UtilityRasterProvider rasterProvider,
            UtilityNetworkVectorIndex vectorIndex,
            CoordinateProjection projection,
            UtilityRoutingProperties properties) {
        this.rasterProvider = rasterProvider;
        this.vectorIndex = vectorIndex;
        this.projection = projection;
        this.properties = properties;
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        UtilityCorridorClass corridorClass = rasterProvider.sampleClass(point.lat(), point.lon());
        UtilityCostPolicy policy = properties.toPolicy();

        if (corridorClass == UtilityCorridorClass.NODATA && policy.blockNoData()) {
            return CostAssessment.blocked(SOURCE, "UTILITY_NODATA");
        }
        if (corridorClass == UtilityCorridorClass.SUBSTATION_OR_TOWER_FOOTPRINT
                && policy.blockSubstationsAndTowers()) {
            return CostAssessment.blocked(SOURCE, "SUBSTATION_OR_TOWER_FOOTPRINT_BLOCKED");
        }

        double penalty = policy.nodePenalty(corridorClass);
        return new CostAssessment(false, penalty, SOURCE, corridorClass.name());
    }

    @Override
    public boolean hasEdgeCost() {
        return true;
    }

    @Override
    public CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
        MetricPoint a = projection.forward(from);
        MetricPoint b = projection.forward(to);
        double stepMeters = Math.hypot(b.x() - a.x(), b.y() - a.y());
        if (stepMeters <= 1e-6) {
            return CostAssessment.FREE;
        }
        double stepKm = Math.max(0.05, stepMeters / 1000.0);
        UtilityCostPolicy policy = properties.toPolicy();

        List<UtilityNetworkVectorIndex.LineSegmentCrossing> crossings =
                vectorIndex.findStepCrossingsMetric(a, b);

        double totalProtectionCostKmEq = 0.0;
        String primaryProtectionType = null;

        if (!crossings.isEmpty()) {
            for (UtilityNetworkVectorIndex.LineSegmentCrossing sc : crossings) {
                totalProtectionCostKmEq +=
                        policy.utilityCrossingProtectionCostKmEq(
                                sc.tier(),
                                sc.sinAngle(),
                                sc.nearestTowerDistanceMeters());
                if (primaryProtectionType == null) {
                    primaryProtectionType = sc.tier().crossingProtectionStructureType();
                }
            }
        }

        double parallelEncouragementMultiplier = 0.0;
        if (policy.parallelUtilityRowPenalty() > 0.0) {
            UtilityNetworkVectorIndex.ParallelUtilityMatch prox =
                    vectorIndex.findParallelCorridorProximity(
                            a, b, policy.parallelUtilityRadiusMeters());
            if (prox != null && prox.cosAlignment() > PARALLEL_COS_THRESHOLD) {
                double acuteRatio =
                        (prox.cosAlignment() - PARALLEL_COS_THRESHOLD)
                                / (1.0 - PARALLEL_COS_THRESHOLD);
                double tierFactor = switch (prox.tier()) {
                    case EHV_400KV_TRANSMISSION -> 1.50;
                    case EHV_220KV_TRANSMISSION -> 1.20;
                    case SUBTRANSMISSION_132KV -> 0.90;
                    case DISTRIBUTION_66KV_AND_BELOW -> 0.60;
                };
                parallelEncouragementMultiplier =
                        policy.parallelUtilityRowPenalty() * acuteRatio * acuteRatio * tierFactor;
            }
        }

        double totalMultiplier =
                (totalProtectionCostKmEq / stepKm) + parallelEncouragementMultiplier;
        if (totalMultiplier <= 0.0) {
            return CostAssessment.FREE;
        }
        return new CostAssessment(
                false,
                totalMultiplier,
                SOURCE + ":protection",
                primaryProtectionType != null
                        ? primaryProtectionType
                        : "LONGITUDINAL_UTILITY_ROW_ENCROACHMENT");
    }
}
