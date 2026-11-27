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

    /**
     * Last-resort barrier penalty multiplier applied to active tectonic landslide shear cores
     * only when strict hard-blocking seals off every pass across rugged mountain terrain
     * (IRC:SP:48 / IRC:SP:106 / NDMA last-resort viaduct/tunnel crossing protocol).
     */
    public static final double ACTIVE_LANDSLIDE_LAST_RESORT_BARRIER_PENALTY = 2500.0;

    private final GeotechRasterProvider rasterProvider;
    private final GeotechLineamentVectorIndex lineamentVectorIndex;
    private final CoordinateProjection projection;
    private final GeotechRoutingProperties properties;
    private final boolean activeLandslideLastResortMode;

    @Autowired
    public GeotechLandslideCostLayer(
            GeotechRasterProvider rasterProvider,
            GeotechLineamentVectorIndex lineamentVectorIndex,
            CoordinateProjection projection,
            GeotechRoutingProperties properties) {
        this(rasterProvider, lineamentVectorIndex, projection, properties, false);
    }

    private GeotechLandslideCostLayer(
            GeotechRasterProvider rasterProvider,
            GeotechLineamentVectorIndex lineamentVectorIndex,
            CoordinateProjection projection,
            GeotechRoutingProperties properties,
            boolean activeLandslideLastResortMode) {
        this.rasterProvider = rasterProvider;
        this.lineamentVectorIndex = lineamentVectorIndex;
        this.projection = projection;
        this.properties = properties;
        this.activeLandslideLastResortMode = activeLandslideLastResortMode;
    }

    /**
     * Returns a last-resort routing copy where active tectonic landslide shear cores transition
     * from hard-block to an extreme {@value #ACTIVE_LANDSLIDE_LAST_RESORT_BARRIER_PENALTY}x
     * barrier penalty so narrow mountain bottlenecks remain routable while strictly minimizing
     * crossing length and triggering mandatory piled-viaduct / tunnel review.
     */
    public GeotechLandslideCostLayer withActiveLandslideLastResortMode() {
        if (activeLandslideLastResortMode) {
            return this;
        }
        return new GeotechLandslideCostLayer(
                rasterProvider,
                lineamentVectorIndex,
                projection,
                properties,
                true);
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
            if (!activeLandslideLastResortMode) {
                return CostAssessment.blocked(SOURCE, "ACTIVE_LANDSLIDE_SHEAR_CORE_BLOCKED");
            }
            return new CostAssessment(
                    false,
                    ACTIVE_LANDSLIDE_LAST_RESORT_BARRIER_PENALTY,
                    SOURCE,
                    "ACTIVE_LANDSLIDE_SHEAR_CORE_BARRIER");
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

        // Sub-grid (~25 m) full-segment check so A* cannot hop across narrow active landslide shear cores
        // or high landslide susceptibility scarps between 250 m routing vertices.
        double activeEdgeBarrierPenalty = 0.0;
        double highScarpEdgePenalty = 0.0;
        String edgeOverrideClass = null;
        GeotechRasterProvider.SegmentGeotechSummary seg =
                rasterProvider.inspectSegmentInterior(from.lat(), from.lon(), to.lat(), to.lon());
        if (seg.noDataSamples() > 0 && policy.blockNoData()) {
            return CostAssessment.blocked(SOURCE, "GEOTECH_EDGE_NODATA");
        }
        if (seg.activeShearCoreSamples() > 0 && policy.blockActiveLandslideZones()) {
            if (!activeLandslideLastResortMode) {
                return CostAssessment.blocked(SOURCE + ":shear-core-edge", "ACTIVE_LANDSLIDE_SHEAR_CORE_EDGE_BLOCKED");
            }
            double ratio = seg.totalSamples() > 0
                    ? (double) seg.activeShearCoreSamples() / seg.totalSamples()
                    : 1.0;
            activeEdgeBarrierPenalty = ACTIVE_LANDSLIDE_LAST_RESORT_BARRIER_PENALTY * (0.5 + ratio);
            edgeOverrideClass = "ACTIVE_LANDSLIDE_SHEAR_CORE_EDGE_BARRIER";
        }
        if (seg.highRiskScarpSamples() > 0 && seg.totalSamples() > 0) {
            double highRatio = (double) seg.highRiskScarpSamples() / seg.totalSamples();
            highScarpEdgePenalty = policy.nodePenalty(GeotechHazardClass.HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP)
                    * 0.35 * highRatio;
            if (edgeOverrideClass == null) {
                edgeOverrideClass = "HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP_EDGE";
            }
        }

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
                activeEdgeBarrierPenalty + highScarpEdgePenalty + (totalMitigationCostKmEq / stepKm) + parallelUndercutMultiplier;
        if (totalMultiplier <= 0.0) {
            return CostAssessment.FREE;
        }
        return new CostAssessment(
                false,
                totalMultiplier,
                SOURCE + ":mitigation",
                edgeOverrideClass != null
                        ? edgeOverrideClass
                        : (primaryMitigationType != null
                                ? primaryMitigationType
                                : "LONGITUDINAL_SCARP_TOE_UNDERCUTTING"));
    }
}
