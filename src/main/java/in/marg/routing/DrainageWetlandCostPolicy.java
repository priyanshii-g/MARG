package in.marg.routing;

import in.marg.drainage.DrainageSublayerType;
import in.marg.drainage.DrainageWetlandClass;

import java.util.EnumMap;
import java.util.Map;

/**
 * Spatial cost policy for MARG Layer 13: Wetlands, Seasonal Streams &amp; Drainage Network.
 */
public class DrainageWetlandCostPolicy {

    private final Map<DrainageWetlandClass, Double> nodePenalties;
    private final Map<DrainageSublayerType, Double> crossingBaseCostsKmEq;
    private final double rowEvaluationHalfWidthMeters;
    private final double wetlandDirectRowStepPenaltyKmEq;
    private final double wetland100mBufferStepPenaltyKmEq;
    private final double wetland250mBufferStepPenaltyKmEq;
    private final double parallelThalwegStepPenaltyKmEq;
    private final double watershedDivideCrossingPenaltyKmEq;
    private final double skewCrossingPenaltyFactor;
    private final boolean blockProtectedWetlandCore;
    private final boolean blockAllWetlandCore;
    private final boolean blockNoData;

    public DrainageWetlandCostPolicy(
            Map<DrainageWetlandClass, Double> nodePenalties,
            Map<DrainageSublayerType, Double> crossingBaseCostsKmEq,
            double rowEvaluationHalfWidthMeters,
            double wetlandDirectRowStepPenaltyKmEq,
            double wetland100mBufferStepPenaltyKmEq,
            double wetland250mBufferStepPenaltyKmEq,
            double parallelThalwegStepPenaltyKmEq,
            double watershedDivideCrossingPenaltyKmEq,
            double skewCrossingPenaltyFactor,
            boolean blockProtectedWetlandCore,
            boolean blockAllWetlandCore,
            boolean blockNoData) {
        this.nodePenalties = new EnumMap<>(DrainageWetlandClass.class);
        this.nodePenalties.putAll(nodePenalties);
        this.crossingBaseCostsKmEq = new EnumMap<>(DrainageSublayerType.class);
        this.crossingBaseCostsKmEq.putAll(crossingBaseCostsKmEq);
        this.rowEvaluationHalfWidthMeters = Math.max(10.0, rowEvaluationHalfWidthMeters);
        this.wetlandDirectRowStepPenaltyKmEq = Math.max(0.0, wetlandDirectRowStepPenaltyKmEq);
        this.wetland100mBufferStepPenaltyKmEq = Math.max(0.0, wetland100mBufferStepPenaltyKmEq);
        this.wetland250mBufferStepPenaltyKmEq = Math.max(0.0, wetland250mBufferStepPenaltyKmEq);
        this.parallelThalwegStepPenaltyKmEq = Math.max(0.0, parallelThalwegStepPenaltyKmEq);
        this.watershedDivideCrossingPenaltyKmEq = Math.max(0.0, watershedDivideCrossingPenaltyKmEq);
        this.skewCrossingPenaltyFactor = Math.max(0.0, skewCrossingPenaltyFactor);
        this.blockProtectedWetlandCore = blockProtectedWetlandCore;
        this.blockAllWetlandCore = blockAllWetlandCore;
        this.blockNoData = blockNoData;
    }

    public static DrainageWetlandCostPolicy defaultPolicy() {
        Map<DrainageWetlandClass, Double> nodeDefaults = new EnumMap<>(DrainageWetlandClass.class);
        nodeDefaults.put(DrainageWetlandClass.WELL_DRAINED_UPLAND_OR_NONE, 0.0);
        nodeDefaults.put(DrainageWetlandClass.WATERSHED_VALLEY_DRAINAGE_SWALE, 0.18);
        nodeDefaults.put(DrainageWetlandClass.LOCAL_WATERCOURSE_AND_SHEET_FLOW_ZONE, 0.60);
        nodeDefaults.put(DrainageWetlandClass.SEASONAL_STREAM_AND_TORRENT_CORRIDOR, 1.15);
        nodeDefaults.put(DrainageWetlandClass.IRRIGATION_CANAL_COMMAND_ROW, 1.55);
        nodeDefaults.put(DrainageWetlandClass.SEASONAL_MARSH_AND_WETLAND_BUFFER, 1.65);
        nodeDefaults.put(DrainageWetlandClass.NATURAL_OR_DESIGNATED_WETLAND_CORE, 3.85);
        nodeDefaults.put(DrainageWetlandClass.NODATA, 0.0);

        Map<DrainageSublayerType, Double> crossingDefaults = new EnumMap<>(DrainageSublayerType.class);
        for (DrainageSublayerType sublayer : DrainageSublayerType.values()) {
            crossingDefaults.put(sublayer, sublayer.baseCrossingCostKmEq());
        }

        return new DrainageWetlandCostPolicy(
                nodeDefaults,
                crossingDefaults,
                30.0,
                1.95,
                0.75,
                0.25,
                1.65,
                0.18,
                0.45,
                false,
                false,
                false);
    }

    public double nodePenalty(DrainageWetlandClass wetlandClass) {
        return nodePenalties.getOrDefault(wetlandClass, 0.0);
    }

    public double crossingBaseCostKmEq(DrainageSublayerType sublayerType) {
        return crossingBaseCostsKmEq.getOrDefault(sublayerType, sublayerType.baseCrossingCostKmEq());
    }

    public double computeCrossingPenaltyKmEq(DrainageSublayerType sublayerType, double sinApproachAngle) {
        double base = crossingBaseCostKmEq(sublayerType);
        double clampedSin = Math.max(0.35, Math.min(1.0, sinApproachAngle));
        double skewFactor = 1.0 + skewCrossingPenaltyFactor * ((1.0 / clampedSin) - 1.0);
        return base * skewFactor;
    }

    public double rowEvaluationHalfWidthMeters() {
        return rowEvaluationHalfWidthMeters;
    }

    public double wetlandDirectRowStepPenaltyKmEq() {
        return wetlandDirectRowStepPenaltyKmEq;
    }

    public double wetland100mBufferStepPenaltyKmEq() {
        return wetland100mBufferStepPenaltyKmEq;
    }

    public double wetland250mBufferStepPenaltyKmEq() {
        return wetland250mBufferStepPenaltyKmEq;
    }

    public double parallelThalwegStepPenaltyKmEq() {
        return parallelThalwegStepPenaltyKmEq;
    }

    public double watershedDivideCrossingPenaltyKmEq() {
        return watershedDivideCrossingPenaltyKmEq;
    }

    public double skewCrossingPenaltyFactor() {
        return skewCrossingPenaltyFactor;
    }

    public boolean blockProtectedWetlandCore() {
        return blockProtectedWetlandCore;
    }

    public boolean blockAllWetlandCore() {
        return blockAllWetlandCore;
    }

    public boolean blockNoData() {
        return blockNoData;
    }

    public Map<DrainageWetlandClass, Double> nodePenalties() {
        return Map.copyOf(nodePenalties);
    }

    public Map<DrainageSublayerType, Double> crossingBaseCostsKmEq() {
        return Map.copyOf(crossingBaseCostsKmEq);
    }
}
