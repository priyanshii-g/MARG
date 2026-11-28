package in.marg.routing;

import in.marg.config.SlopeRoutingProperties;
import in.marg.model.GeoPoint;
import in.marg.terrain.SlopeRasterProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SlopeCostLayer implements CostLayer {

    /**
     * Last-resort barrier penalty multiplier applied to sub-grid {@code > 30°} edge crossings
     * only when strict sub-grid edge blocking seals off every pass across rugged mountain terrain.
     * Routing vertices {@code > 30°} remain 100% hard-blocked in all modes.
     */
    public static final double OVER_LIMIT_EDGE_BARRIER_PENALTY = 500.0;

    private final SlopeRasterProvider slopeRasterProvider;
    private final SlopeRoutingProperties properties;
    private final boolean edgeBarrierLastResortMode;

    @Autowired
    public SlopeCostLayer(
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties properties) {
        this(slopeRasterProvider, properties, false);
    }

    private SlopeCostLayer(
            SlopeRasterProvider slopeRasterProvider,
            SlopeRoutingProperties properties,
            boolean edgeBarrierLastResortMode) {
        this.slopeRasterProvider = slopeRasterProvider;
        this.properties = properties;
        this.edgeBarrierLastResortMode = edgeBarrierLastResortMode;
    }

    /**
     * Returns a last-resort copy where sub-grid interior {@code > 30°} edge crossings drop from
     * hard-block to an extreme {@value #OVER_LIMIT_EDGE_BARRIER_PENALTY}x barrier penalty so
     * narrow mountain/ghat passes remain routable while still strongly minimizing steep cuts.
     * All 250 m routing vertices {@code > 30°} remain strictly hard-blocked.
     */
    public SlopeCostLayer withEdgeBarrierLastResortMode() {
        if (edgeBarrierLastResortMode) {
            return this;
        }
        return new SlopeCostLayer(slopeRasterProvider, properties, true);
    }

    @Override
    public boolean hasEdgeCost() {
        return properties.isBlockAboveVerySteep() || properties.isBlockNoData();
    }

    @Override
    public CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
        if (!hasEdgeCost() || from == null || to == null) {
            return CostAssessment.FREE;
        }

        double maxAllowedDegrees = properties.getVerySteepMaxDegrees();
        SlopeRasterProvider.SegmentSlopeSummary seg =
                slopeRasterProvider.inspectSegmentInterior(
                        from.lat(), from.lon(),
                        to.lat(), to.lon(),
                        maxAllowedDegrees);

        if (seg.noDataSamples() > 0 && properties.isBlockNoData()) {
            return CostAssessment.blocked("terrain-slope", "SLOPE_EDGE_NODATA");
        }

        if (seg.overLimitSamples() > 0 && properties.isBlockAboveVerySteep()) {
            if (!edgeBarrierLastResortMode) {
                return CostAssessment.blocked("terrain-slope", "SLOPE_EDGE_OVER_LIMIT");
            }
            double exceedanceFraction = seg.totalSamples() > 0
                    ? (double) seg.overLimitSamples() / seg.totalSamples()
                    : 1.0;
            double excessDegrees = Math.max(0.0, seg.maxSlopeDegrees() - maxAllowedDegrees);
            double barrierPenalty = OVER_LIMIT_EDGE_BARRIER_PENALTY * (0.5 + exceedanceFraction)
                    + (excessDegrees * 10.0);
            return new CostAssessment(
                    false,
                    barrierPenalty,
                    "terrain-slope",
                    "SLOPE_EDGE_OVER_LIMIT_BARRIER");
        }

        return CostAssessment.FREE;
    }

    @Override
    public CostAssessment assess(GeoPoint point) {

        Double slopeDegrees =
                slopeRasterProvider.sampleOrNull(
                        point.lat(),
                        point.lon());

        if (slopeDegrees == null) {

            if (properties.isBlockNoData()) {
                return new CostAssessment(
                        true,
                        0.0,
                        "terrain-slope",
                        "SLOPE_NODATA");
            }

            return new CostAssessment(
                    false,
                    0.0,
                    "terrain-slope",
                    "SLOPE_NODATA");
        }

        if (slopeDegrees <=
                properties.getFreeMaxDegrees()) {

            return new CostAssessment(
                    false,
                    0.0,
                    "terrain-slope",
                    "SLOPE_FREE");
        }

        if (slopeDegrees <=
                properties.getLowMaxDegrees()) {

            return new CostAssessment(
                    false,
                    properties.getLowPenalty(),
                    "terrain-slope",
                    "SLOPE_LOW");
        }

        if (slopeDegrees <=
                properties.getModerateMaxDegrees()) {

            return new CostAssessment(
                    false,
                    properties.getModeratePenalty(),
                    "terrain-slope",
                    "SLOPE_MODERATE");
        }

        if (slopeDegrees <=
                properties.getSteepMaxDegrees()) {

            return new CostAssessment(
                    false,
                    properties.getSteepPenalty(),
                    "terrain-slope",
                    "SLOPE_STEEP");
        }

        if (slopeDegrees <=
                properties.getVerySteepMaxDegrees()) {

            return new CostAssessment(
                    false,
                    properties.getVerySteepPenalty(),
                    "terrain-slope",
                    "SLOPE_VERY_STEEP");
        }

        if (properties.isBlockAboveVerySteep()) {

            return new CostAssessment(
                    true,
                    0.0,
                    "terrain-slope",
                    "SLOPE_OVER_LIMIT");
        }

        return new CostAssessment(
                false,
                properties.getVerySteepPenalty(),
                "terrain-slope",
                "SLOPE_OVER_LIMIT");
    }
}