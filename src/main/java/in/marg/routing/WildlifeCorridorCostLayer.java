package in.marg.routing;

import in.marg.config.WildlifeRoutingProperties;
import in.marg.model.GeoPoint;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.wildlife.WildlifeCorridorVectorIndex;
import in.marg.wildlife.WildlifeRasterProvider;
import in.marg.wildlife.WildlifeZoneClass;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spatial cost layer for Eco-Sensitive Zones (ESZ), wildlife migration corridors,
 * and WII-compliant Elevated Wildlife Viaducts / Animal Underpasses:
 * <ul>
 *   <li>Node assessment: penalizes ESZ outer/inner buffers and wildlife migration corridor
 *       footprints (or hard-blocks critical elephant/rhino corridors when configured).</li>
 *   <li>Edge assessment: detects exact 2D crossings of wildlife migration axes, charges
 *       elevated wildlife viaduct / underpass construction costs scaled by barrier skew angle
 *       (90 deg optimal minimum barrier footprint), and penalizes longitudinal fragmentation
 *       parallel to migration funnels.</li>
 * </ul>
 */
@Component
public class WildlifeCorridorCostLayer implements CostLayer {

    private static final String SOURCE = "ecology:wildlife-corridors";
    private static final double PARALLEL_COS_THRESHOLD = 0.70710678; // cos(45 deg)

    private final WildlifeRasterProvider rasterProvider;
    private final WildlifeCorridorVectorIndex corridorVectorIndex;
    private final CoordinateProjection projection;
    private final WildlifeRoutingProperties properties;

    @Autowired
    public WildlifeCorridorCostLayer(
            WildlifeRasterProvider rasterProvider,
            WildlifeCorridorVectorIndex corridorVectorIndex,
            CoordinateProjection projection,
            WildlifeRoutingProperties properties) {
        this.rasterProvider = rasterProvider;
        this.corridorVectorIndex = corridorVectorIndex;
        this.projection = projection;
        this.properties = properties;
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        WildlifeZoneClass zoneClass = rasterProvider.sampleClass(point.lat(), point.lon());
        WildlifeCostPolicy policy = properties.toPolicy();

        if (zoneClass == WildlifeZoneClass.NODATA && policy.blockNoData()) {
            return CostAssessment.blocked(SOURCE, "WILDLIFE_NODATA");
        }
        if (zoneClass == WildlifeZoneClass.CRITICAL_MIGRATION_CORRIDOR_CORE
                && policy.blockCriticalCorridors()) {
            return CostAssessment.blocked(SOURCE, "CRITICAL_WILDLIFE_CORRIDOR_PROTECTED");
        }

        double penalty = policy.nodePenalty(zoneClass);
        return new CostAssessment(false, penalty, SOURCE, zoneClass.name());
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
        WildlifeCostPolicy policy = properties.toPolicy();

        List<WildlifeCorridorVectorIndex.CorridorSegmentCrossing> crossings =
                corridorVectorIndex.findStepCrossingsMetric(a, b);

        double totalPassageCostKmEq = 0.0;
        String primaryStructureType = null;

        if (!crossings.isEmpty()) {
            for (WildlifeCorridorVectorIndex.CorridorSegmentCrossing sc : crossings) {
                totalPassageCostKmEq +=
                        policy.wildlifePassageCostKmEq(sc.corridorTier(), sc.sinAngle());
                if (primaryStructureType == null) {
                    primaryStructureType = sc.corridorTier().passageStructureType();
                }
            }
        }

        double parallelFragmentationMultiplier = 0.0;
        if (policy.parallelCorridorPenalty() > 0.0) {
            WildlifeCorridorVectorIndex.ParallelCorridorMatch prox =
                    corridorVectorIndex.findParallelCorridorProximity(
                            a, b, policy.parallelCorridorRadiusMeters());
            if (prox != null && prox.cosAlignment() > PARALLEL_COS_THRESHOLD) {
                double acuteRatio =
                        (prox.cosAlignment() - PARALLEL_COS_THRESHOLD)
                                / (1.0 - PARALLEL_COS_THRESHOLD);
                double tierFactor = switch (prox.corridorTier()) {
                    case CRITICAL_ELEPHANT_RHINO_CORRIDOR -> 1.50;
                    case INTER_SANCTUARY_ELEPHANT_CORRIDOR -> 1.15;
                    case LOCAL_WILDLIFE_DISPERSAL_AXIS -> 0.75;
                };
                parallelFragmentationMultiplier =
                        policy.parallelCorridorPenalty() * acuteRatio * acuteRatio * tierFactor;
            }
        }

        double totalMultiplier =
                (totalPassageCostKmEq / stepKm) + parallelFragmentationMultiplier;
        if (totalMultiplier <= 0.0) {
            return CostAssessment.FREE;
        }
        return new CostAssessment(
                false,
                totalMultiplier,
                SOURCE + ":animal-passage",
                primaryStructureType != null
                        ? primaryStructureType
                        : "LONGITUDINAL_CORRIDOR_FRAGMENTATION");
    }
}
