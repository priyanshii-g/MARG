package in.marg.routing;

import in.marg.config.DrainageRoutingProperties;
import in.marg.drainage.DrainageWetlandClass;
import in.marg.drainage.DrainageWetlandRasterProvider;
import in.marg.drainage.DrainageWetlandVectorIndex;
import in.marg.model.GeoPoint;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spatial cost layer for MARG Layer 13: Wetlands, Seasonal Streams &amp; Drainage Network.
 * <ul>
 *   <li><b>Node assessment:</b> samples {@code assam_drainage_wetland_utm46.tif} (30 m resolution)
 *       and penalizes wetland cores, seasonal marsh buffers, irrigation canal command RoWs,
 *       seasonal hill torrent corridors, and local sheet-flow swales.</li>
 *   <li><b>Edge assessment:</b> evaluates each step {@code from -> to} against the 5 vector
 *       sublayers in {@link DrainageWetlandVectorIndex}, penalizing 2D wetland polygon
 *       encroachment (Direct RoW, 100 m, and 250 m buffers), sub-parallel thalweg/canal
 *       embankment damming risk, linear water crossings (scaled by approach angle), and
 *       watershed divide crossings.</li>
 * </ul>
 */
@Component
public class DrainageWetlandCostLayer implements CostLayer {

    private static final String SOURCE = "drainage:wetlands-streams-and-watersheds";

    private final DrainageWetlandRasterProvider rasterProvider;
    private final DrainageWetlandVectorIndex vectorIndex;
    private final CoordinateProjection projection;
    private final DrainageRoutingProperties properties;

    @Autowired
    public DrainageWetlandCostLayer(
            DrainageWetlandRasterProvider rasterProvider,
            DrainageWetlandVectorIndex vectorIndex,
            CoordinateProjection projection,
            DrainageRoutingProperties properties) {
        this.rasterProvider = rasterProvider;
        this.vectorIndex = vectorIndex;
        this.projection = projection;
        this.properties = properties;
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        DrainageWetlandClass dwClass = rasterProvider.sampleClass(point.lat(), point.lon());
        DrainageWetlandCostPolicy policy = properties.toPolicy();

        if (dwClass == DrainageWetlandClass.NODATA && policy.blockNoData()) {
            return CostAssessment.blocked(SOURCE, "DRAINAGE_WETLAND_NODATA");
        }
        if (dwClass == DrainageWetlandClass.NATURAL_OR_DESIGNATED_WETLAND_CORE
                && policy.blockAllWetlandCore()) {
            return CostAssessment.blocked(SOURCE, "NATURAL_OR_DESIGNATED_WETLAND_CORE_BLOCKED");
        }

        double penalty = policy.nodePenalty(dwClass);
        return new CostAssessment(false, penalty, SOURCE, dwClass.name());
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
        DrainageWetlandCostPolicy policy = properties.toPolicy();

        DrainageWetlandVectorIndex.StepDrainageAssessment step =
                vectorIndex.assessStepDrainageAndWetlandImpact(
                        from, to, policy.rowEvaluationHalfWidthMeters());

        if (step.intersectsProtectedBeel() && policy.blockProtectedWetlandCore()) {
            return CostAssessment.blocked(SOURCE + ":wetland-polygon", "PROTECTED_BEEL_WETLAND_BLOCKED");
        }
        if (step.intersectsWetlandDirectRow() && policy.blockAllWetlandCore()) {
            return CostAssessment.blocked(SOURCE + ":wetland-polygon", "WETLAND_DIRECT_ROW_BLOCKED");
        }

        double stepEdgePenalty = 0.0;
        String dominantReason = null;

        // 1. 2D Wetland Polygon Encroachment
        if (step.intersectsWetlandDirectRow()) {
            stepEdgePenalty += policy.wetlandDirectRowStepPenaltyKmEq()
                    * (step.intersectsProtectedBeel() ? 1.35 : 1.0);
            dominantReason = "WETLAND_DIRECT_ROW_ENCROACHMENT";
        } else if (step.intersectsWetland100mBuffer()) {
            stepEdgePenalty += policy.wetland100mBufferStepPenaltyKmEq();
            dominantReason = "WETLAND_100M_CONSTRUCTION_BUFFER_EXPOSURE";
        } else if (step.intersectsWetland250mBuffer()) {
            stepEdgePenalty += policy.wetland250mBufferStepPenaltyKmEq();
            dominantReason = "WETLAND_250M_HYDROLOGIC_RECHARGE_BUFFER";
        }

        // 2. Parallel / Sub-Parallel Thalweg or Canal Embankment Damming Risk
        if (step.parallelThalwegOrCanalEncroachment()) {
            stepEdgePenalty += policy.parallelThalwegStepPenaltyKmEq();
            if (dominantReason == null) {
                dominantReason = "PARALLEL_THALWEG_EMBANKMENT_OBSTRUCTION_RISK";
            }
        }

        // 3. Linear Water Crossings (Seasonal Streams, Natural Drainage Channels, Canals, Watercourses)
        if (!step.crossings().isEmpty()) {
            double crossingCostKmEq = 0.0;
            for (DrainageWetlandVectorIndex.DrainageLineCrossing c : step.crossings()) {
                crossingCostKmEq += policy.computeCrossingPenaltyKmEq(
                        c.sublayerType(), c.sinApproachAngle());
                if (dominantReason == null) {
                    dominantReason = c.sublayerType().name() + "_CROSSING_" + c.recommendedStructureType();
                }
            }
            stepEdgePenalty += crossingCostKmEq / stepKm;
        }

        // 4. Watershed Divide Crossing
        if (step.crossesWatershedBoundary()) {
            stepEdgePenalty += policy.watershedDivideCrossingPenaltyKmEq() / stepKm;
            if (dominantReason == null) {
                dominantReason = "WATERSHED_CATCHMENT_DIVIDE_CROSSING";
            }
        }

        if (stepEdgePenalty <= 0.0) {
            return CostAssessment.FREE;
        }

        return new CostAssessment(
                false,
                stepEdgePenalty,
                SOURCE + ":hydrological-vector-edge",
                dominantReason != null ? dominantReason : "DRAINAGE_EDGE_EXPOSURE");
    }
}
