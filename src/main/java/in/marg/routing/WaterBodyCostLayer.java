package in.marg.routing;

import in.marg.config.WaterBodyRoutingProperties;
import in.marg.hydrology.RiverNetworkVectorIndex;
import in.marg.hydrology.WaterBodyClass;
import in.marg.hydrology.WaterBodyRasterProvider;
import in.marg.model.GeoPoint;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spatial cost layer for perennial rivers, inland wetlands/beels, riparian floodways,
 * and bridge crossing alignment hydraulics:
 * <ul>
 *   <li>Node assessment: penalizes riparian floodway buffers, river channel footprints, and
 *       inland wetlands/beels (or hard-blocks wetlands when {@code blockWetlands = true}).</li>
 *   <li>Edge assessment: detects exact 2D crossings of perennial rivers/streams, charges
 *       tier-specific bridge span costs scaled by hydraulic skew angle (90 deg optimal),
 *       and penalizes longitudinal riverbank encroachment.</li>
 * </ul>
 */
@Component
public class WaterBodyCostLayer implements CostLayer {

    private static final String SOURCE = "hydrology:water-bodies";
    private static final double PARALLEL_COS_THRESHOLD = 0.70710678; // cos(45 deg)

    private final WaterBodyRasterProvider rasterProvider;
    private final RiverNetworkVectorIndex riverVectorIndex;
    private final CoordinateProjection projection;
    private final WaterBodyRoutingProperties properties;

    @Autowired
    public WaterBodyCostLayer(
            WaterBodyRasterProvider rasterProvider,
            RiverNetworkVectorIndex riverVectorIndex,
            CoordinateProjection projection,
            WaterBodyRoutingProperties properties) {
        this.rasterProvider = rasterProvider;
        this.riverVectorIndex = riverVectorIndex;
        this.projection = projection;
        this.properties = properties;
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        WaterBodyClass waterClass = rasterProvider.sampleClass(point.lat(), point.lon());
        WaterBodyCostPolicy policy = properties.toPolicy();

        if (waterClass == WaterBodyClass.NODATA && policy.blockNoData()) {
            return CostAssessment.blocked(SOURCE, "WATER_NODATA");
        }
        if (waterClass == WaterBodyClass.INLAND_WETLAND_WATERBODY && policy.blockWetlands()) {
            return CostAssessment.blocked(SOURCE, "INLAND_WETLAND_PROTECTED");
        }

        double penalty = policy.nodePenalty(waterClass);
        return new CostAssessment(false, penalty, SOURCE, waterClass.name());
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
        WaterBodyCostPolicy policy = properties.toPolicy();

        List<RiverNetworkVectorIndex.RiverSegmentCrossing> crossings =
                riverVectorIndex.findStepCrossingsMetric(a, b);

        double totalBridgeCostKmEq = 0.0;
        String primaryBridgeType = null;

        if (!crossings.isEmpty()) {
            for (RiverNetworkVectorIndex.RiverSegmentCrossing sc : crossings) {
                totalBridgeCostKmEq +=
                        policy.riverBridgeCostKmEq(sc.riverClass(), sc.sinAngle());
                if (primaryBridgeType == null) {
                    primaryBridgeType = sc.riverClass().bridgeStructureType();
                }
            }
        }

        double parallelBankMultiplier = 0.0;
        if (policy.parallelBankPenalty() > 0.0) {
            RiverNetworkVectorIndex.ParallelBankMatch prox =
                    riverVectorIndex.findParallelBankProximity(
                            a, b, policy.parallelBankRadiusMeters());
            if (prox != null && prox.cosAlignment() > PARALLEL_COS_THRESHOLD) {
                double acuteRatio =
                        (prox.cosAlignment() - PARALLEL_COS_THRESHOLD)
                                / (1.0 - PARALLEL_COS_THRESHOLD);
                double riverFactor = switch (prox.riverClass()) {
                    case MAJOR_PERENNIAL_RIVER -> 1.50;
                    case MEDIUM_PERENNIAL_RIVER -> 1.10;
                    case MINOR_PERENNIAL_STREAM -> 0.70;
                };
                parallelBankMultiplier =
                        policy.parallelBankPenalty() * acuteRatio * acuteRatio * riverFactor;
            }
        }

        double totalMultiplier = (totalBridgeCostKmEq / stepKm) + parallelBankMultiplier;
        if (totalMultiplier <= 0.0) {
            return CostAssessment.FREE;
        }
        return new CostAssessment(
                false,
                totalMultiplier,
                SOURCE + ":bridge",
                primaryBridgeType != null ? primaryBridgeType : "RIPARIAN_PARALLEL_ENCROACHMENT");
    }
}
