package in.marg.routing;

import in.marg.flood.FloodHazardClass;
import in.marg.floodraster.BhuvanFloodRasterCache;
import in.marg.floodraster.FloodRasterSample;
import in.marg.model.GeoPoint;

/**
 * Deterministic flood cost lookup from the local Bhuvan WMS raster cache.
 *
 * <p>When configured with high-priority flood avoidance ({@code HIGH} penalty &ge; 15.0 or
 * {@code VERY_HIGH} penalty &ge; 20.0), both {@link FloodHazardClass#HIGH} and
 * {@link FloodHazardClass#VERY_HIGH} (Severe) flood zones are hard-blocked on vertices and
 * sub-grid interior edge samples (falling back to heavy barrier penalties only in explicit
 * last-resort mode when a floodplain cannot be bypassed). {@link FloodHazardClass#MODERATE}
 * zones are penalized on both nodes and interior edge samples.
 */
public class FloodRasterCostLayer implements CostLayer {
    private static final String SOURCE = "bhuvan:flood-hazard-ras2-wms:local-raster-cache";
    private static final int EDGE_INTERIOR_SAMPLES = 3;
    public static final double HIGH_FLOOD_LAST_RESORT_BARRIER_PENALTY = 35.0;
    public static final double SEVERE_FLOOD_LAST_RESORT_BARRIER_PENALTY = 150.0;

    private final BhuvanFloodRasterCache cache;
    private final FloodCostPolicy policy;
    private final boolean blockHighAndSevereFlood;
    private final boolean floodLastResortMode;

    public FloodRasterCostLayer(BhuvanFloodRasterCache cache, FloodCostPolicy policy) {
        this(
                cache,
                policy,
                policy != null && (policy.penalty(FloodHazardClass.VERY_HIGH) >= 20.0
                        || policy.penalty(FloodHazardClass.HIGH) >= 15.0),
                false);
    }

    private FloodRasterCostLayer(
            BhuvanFloodRasterCache cache,
            FloodCostPolicy policy,
            boolean blockHighAndSevereFlood,
            boolean floodLastResortMode) {
        this.cache = cache;
        this.policy = policy;
        this.blockHighAndSevereFlood = blockHighAndSevereFlood;
        this.floodLastResortMode = floodLastResortMode;
    }

    public FloodRasterCostLayer withSevereFloodLastResortMode() {
        if (floodLastResortMode || !blockHighAndSevereFlood) {
            return this;
        }
        return new FloodRasterCostLayer(cache, policy, blockHighAndSevereFlood, true);
    }

    private FloodHazardClass sampleHazard(GeoPoint point) {
        FloodRasterSample sample = cache.sample(point);
        return (sample != null && sample.cacheAvailable())
                ? sample.hazardClass()
                : FloodHazardClass.VERY_LOW;
    }

    private boolean isHighOrSevere(FloodHazardClass h) {
        return h == FloodHazardClass.HIGH || h == FloodHazardClass.VERY_HIGH;
    }

    private double lastResortBarrierPenalty(FloodHazardClass h) {
        if (h == FloodHazardClass.VERY_HIGH) {
            return Math.max(SEVERE_FLOOD_LAST_RESORT_BARRIER_PENALTY, policy.penalty(h));
        }
        return Math.max(HIGH_FLOOD_LAST_RESORT_BARRIER_PENALTY, policy.penalty(h));
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        FloodHazardClass hazard = sampleHazard(point);
        if (blockHighAndSevereFlood && isHighOrSevere(hazard)) {
            if (!floodLastResortMode) {
                return CostAssessment.blocked(SOURCE, "FLOOD_" + hazard.name() + "_BLOCKED");
            }
            return new CostAssessment(
                    false,
                    lastResortBarrierPenalty(hazard),
                    SOURCE,
                    "FLOOD_" + hazard.name() + "_BARRIER");
        }
        return new CostAssessment(false, policy.penalty(hazard), SOURCE, "FLOOD_" + hazard.name());
    }

    @Override
    public boolean hasEdgeCost() {
        return blockHighAndSevereFlood || (policy != null && policy.penalty(FloodHazardClass.HIGH) >= 10.0);
    }

    @Override
    public CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
        double maxPen = 0.0;
        double sumPen = 0.0;
        FloodHazardClass worstHazard = FloodHazardClass.VERY_LOW;
        int elevatedCount = 0;

        for (int i = 1; i <= EDGE_INTERIOR_SAMPLES; i++) {
            double t = (double) i / (EDGE_INTERIOR_SAMPLES + 1);
            GeoPoint pt = new GeoPoint(
                    from.lat() + t * (to.lat() - from.lat()),
                    from.lon() + t * (to.lon() - from.lon()));
            FloodHazardClass h = sampleHazard(pt);
            if (blockHighAndSevereFlood && isHighOrSevere(h)) {
                if (!floodLastResortMode) {
                    return CostAssessment.blocked(SOURCE + ":edge", "FLOOD_" + h.name() + "_EDGE_BLOCKED");
                }
                double p = lastResortBarrierPenalty(h);
                if (p > maxPen) {
                    maxPen = p;
                    worstHazard = h;
                }
                sumPen += p;
                elevatedCount++;
            } else if (h == FloodHazardClass.HIGH || h == FloodHazardClass.VERY_HIGH || h == FloodHazardClass.MODERATE) {
                double p = policy.penalty(h);
                if (p > maxPen) {
                    maxPen = p;
                    worstHazard = h;
                }
                sumPen += p;
                elevatedCount++;
            }
        }

        if (elevatedCount == 0 || maxPen <= 0.0) {
            return CostAssessment.FREE;
        }
        double avgPen = sumPen / EDGE_INTERIOR_SAMPLES;
        double edgeMultiplier = isHighOrSevere(worstHazard)
                ? (0.30 * maxPen + 0.25 * avgPen)
                : (0.30 * avgPen);
        return new CostAssessment(false, edgeMultiplier, SOURCE + ":edge", "FLOOD_EDGE_" + worstHazard.name());
    }

    public FloodCostPolicy policy() {
        return policy;
    }
}
