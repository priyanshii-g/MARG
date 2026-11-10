package in.marg.routing;

import in.marg.config.GeotechRoutingProperties;
import in.marg.geotech.GeotechHazardClass;
import in.marg.geotech.GeotechLineamentVectorIndex;
import in.marg.geotech.GeotechRasterProvider;
import in.marg.model.GeoPoint;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spatial cost layer for Geotechnical Subgrade Bearing Capacity (CBR / SBC),
 * Seismic Zone V Alluvial Liquefaction, and Karbi Hillslope Landslide Susceptibility:
 * <ul>
 *   <li>Node assessment: penalizes weak compressible alluvial clays, liquefiable organic marshes,
 *       dissected colluvial landslide slopes, and crushed tectonic shear cores (or hard-blocks
 *       active tectonic landslide crowns when {@code blockActiveLandslideZones=true}).</li>
 *   <li>Edge assessment: detects exact 2D crossings of tectonic fault/shear zones, landslide scarps,
 *       and liquefaction troughs, charges anchored retaining-wall / seismic piled viaduct / stone-column
 *       stabilization costs scaled by approach skew angle (90 deg optimal minimum traverse), and
 *       penalizes longitudinal side-hill toe-undercutting parallel to fault/landslide scarps.</li>
 * </ul>
 */
@Component
public class GeotechLandslideCostLayer implements CostLayer {

    private static final String SOURCE = "geotech:soil-and-landslide";
    private static final double PARALLEL_COS_THRESHOLD = 0.70710678; // cos(45 deg)

    private final GeotechRasterProvider rasterProvider;
    private final GeotechLineamentVectorIndex lineamentVectorIndex;
    private final CoordinateProjection projection;
    private final GeotechRoutingProperties properties;

    @Autowired
    public GeotechLandslideCostLayer(
            GeotechRasterProvider rasterProvider,
            GeotechLineamentVectorIndex lineamentVectorIndex,
            CoordinateProjection projection,
            GeotechRoutingProperties properties) {
        this.rasterProvider = rasterProvider;
        this.lineamentVectorIndex = lineamentVectorIndex;
        this.projection = projection;
        this.properties = properties;
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        GeotechHazardClass hazardClass = rasterProvider.sampleClass(point.lat(), point.lon());
        GeotechCostPolicy policy = properties.toPolicy();

        if (hazardClass == GeotechHazardClass.NODATA && policy.blockNoData()) {
            return CostAssessment.blocked(SOURCE, "GEOTECH_NODATA");
        }
        if (hazardClass == GeotechHazardClass.ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE
                && policy.blockActiveLandslideZones()) {
            return CostAssessment.blocked(SOURCE, "ACTIVE_LANDSLIDE_SHEAR_CORE_BLOCKED");
        }

        double penalty = policy.nodePenalty(hazardClass);
        return new CostAssessment(false, penalty, SOURCE, hazardClass.name());
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
        GeotechCostPolicy policy = properties.toPolicy();

        List<GeotechLineamentVectorIndex.LineamentSegmentCrossing> crossings =
                lineamentVectorIndex.findStepCrossingsMetric(a, b);

        double totalMitigationCostKmEq = 0.0;
        String primaryMitigationType = null;

        if (!crossings.isEmpty()) {
            for (GeotechLineamentVectorIndex.LineamentSegmentCrossing sc : crossings) {
                totalMitigationCostKmEq +=
                        policy.geotechMitigationCostKmEq(sc.hazardTier(), sc.sinAngle());
                if (primaryMitigationType == null) {
                    primaryMitigationType = sc.hazardTier().mitigationStructureType();
                }
            }
        }

        double parallelUndercutMultiplier = 0.0;
        if (policy.parallelScarpUndercutPenalty() > 0.0) {
            GeotechLineamentVectorIndex.ParallelScarpMatch prox =
                    lineamentVectorIndex.findParallelScarpProximity(
                            a, b, policy.parallelScarpRadiusMeters());
            if (prox != null && prox.cosAlignment() > PARALLEL_COS_THRESHOLD) {
                double acuteRatio =
                        (prox.cosAlignment() - PARALLEL_COS_THRESHOLD)
                                / (1.0 - PARALLEL_COS_THRESHOLD);
                double tierFactor = switch (prox.hazardTier()) {
                    case ACTIVE_FAULT_SHEAR_ZONE -> 1.50;
                    case UNSTABLE_COLLUVIAL_LANDSLIDE_SCARP -> 1.20;
                    case LIQUEFIABLE_ALLUVIAL_FAULT_TROUGH -> 0.80;
                };
                parallelUndercutMultiplier =
                        policy.parallelScarpUndercutPenalty() * acuteRatio * acuteRatio * tierFactor;
            }
        }

        double totalMultiplier =
                (totalMitigationCostKmEq / stepKm) + parallelUndercutMultiplier;
        if (totalMultiplier <= 0.0) {
            return CostAssessment.FREE;
        }
        return new CostAssessment(
                false,
                totalMultiplier,
                SOURCE + ":mitigation",
                primaryMitigationType != null
                        ? primaryMitigationType
                        : "LONGITUDINAL_SCARP_TOE_UNDERCUTTING");
    }
}
