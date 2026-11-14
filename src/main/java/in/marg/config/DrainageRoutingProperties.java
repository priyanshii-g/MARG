package in.marg.config;

import in.marg.drainage.DrainageSublayerType;
import in.marg.drainage.DrainageWetlandClass;
import in.marg.routing.DrainageWetlandCostPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "marg.routing.drainage")
public class DrainageRoutingProperties {

    private boolean blockProtectedWetlandCore = false;
    private boolean blockAllWetlandCore = false;
    private boolean blockNoData = false;
    private double rowEvaluationHalfWidthMeters = 30.0;
    private double wetlandDirectRowStepPenaltyKmEq = 1.95;
    private double wetland100mBufferStepPenaltyKmEq = 0.75;
    private double wetland250mBufferStepPenaltyKmEq = 0.25;
    private double parallelThalwegStepPenaltyKmEq = 1.65;
    private double watershedDivideCrossingPenaltyKmEq = 0.18;
    private double skewCrossingPenaltyFactor = 0.45;

    private Map<DrainageWetlandClass, Double> nodePenalties =
            new EnumMap<>(DrainageWetlandCostPolicy.defaultPolicy().nodePenalties());

    private Map<DrainageSublayerType, Double> crossingBaseCostsKmEq =
            new EnumMap<>(DrainageWetlandCostPolicy.defaultPolicy().crossingBaseCostsKmEq());

    public DrainageWetlandCostPolicy toPolicy() {
        Map<DrainageWetlandClass, Double> mergedNodes =
                new EnumMap<>(DrainageWetlandCostPolicy.defaultPolicy().nodePenalties());
        mergedNodes.putAll(nodePenalties);

        Map<DrainageSublayerType, Double> mergedCrossings =
                new EnumMap<>(DrainageWetlandCostPolicy.defaultPolicy().crossingBaseCostsKmEq());
        mergedCrossings.putAll(crossingBaseCostsKmEq);

        return new DrainageWetlandCostPolicy(
                mergedNodes,
                mergedCrossings,
                rowEvaluationHalfWidthMeters,
                wetlandDirectRowStepPenaltyKmEq,
                wetland100mBufferStepPenaltyKmEq,
                wetland250mBufferStepPenaltyKmEq,
                parallelThalwegStepPenaltyKmEq,
                watershedDivideCrossingPenaltyKmEq,
                skewCrossingPenaltyFactor,
                blockProtectedWetlandCore,
                blockAllWetlandCore,
                blockNoData);
    }

    public boolean isBlockProtectedWetlandCore() {
        return blockProtectedWetlandCore;
    }

    public void setBlockProtectedWetlandCore(boolean blockProtectedWetlandCore) {
        this.blockProtectedWetlandCore = blockProtectedWetlandCore;
    }

    public boolean isBlockAllWetlandCore() {
        return blockAllWetlandCore;
    }

    public void setBlockAllWetlandCore(boolean blockAllWetlandCore) {
        this.blockAllWetlandCore = blockAllWetlandCore;
    }

    public boolean isBlockNoData() {
        return blockNoData;
    }

    public void setBlockNoData(boolean blockNoData) {
        this.blockNoData = blockNoData;
    }

    public double getRowEvaluationHalfWidthMeters() {
        return rowEvaluationHalfWidthMeters;
    }

    public void setRowEvaluationHalfWidthMeters(double rowEvaluationHalfWidthMeters) {
        this.rowEvaluationHalfWidthMeters = rowEvaluationHalfWidthMeters;
    }

    public double getWetlandDirectRowStepPenaltyKmEq() {
        return wetlandDirectRowStepPenaltyKmEq;
    }

    public void setWetlandDirectRowStepPenaltyKmEq(double wetlandDirectRowStepPenaltyKmEq) {
        this.wetlandDirectRowStepPenaltyKmEq = wetlandDirectRowStepPenaltyKmEq;
    }

    public double getWetland100mBufferStepPenaltyKmEq() {
        return wetland100mBufferStepPenaltyKmEq;
    }

    public void setWetland100mBufferStepPenaltyKmEq(double wetland100mBufferStepPenaltyKmEq) {
        this.wetland100mBufferStepPenaltyKmEq = wetland100mBufferStepPenaltyKmEq;
    }

    public double getWetland250mBufferStepPenaltyKmEq() {
        return wetland250mBufferStepPenaltyKmEq;
    }

    public void setWetland250mBufferStepPenaltyKmEq(double wetland250mBufferStepPenaltyKmEq) {
        this.wetland250mBufferStepPenaltyKmEq = wetland250mBufferStepPenaltyKmEq;
    }

    public double getParallelThalwegStepPenaltyKmEq() {
        return parallelThalwegStepPenaltyKmEq;
    }

    public void setParallelThalwegStepPenaltyKmEq(double parallelThalwegStepPenaltyKmEq) {
        this.parallelThalwegStepPenaltyKmEq = parallelThalwegStepPenaltyKmEq;
    }

    public double getWatershedDivideCrossingPenaltyKmEq() {
        return watershedDivideCrossingPenaltyKmEq;
    }

    public void setWatershedDivideCrossingPenaltyKmEq(double watershedDivideCrossingPenaltyKmEq) {
        this.watershedDivideCrossingPenaltyKmEq = watershedDivideCrossingPenaltyKmEq;
    }

    public double getSkewCrossingPenaltyFactor() {
        return skewCrossingPenaltyFactor;
    }

    public void setSkewCrossingPenaltyFactor(double skewCrossingPenaltyFactor) {
        this.skewCrossingPenaltyFactor = skewCrossingPenaltyFactor;
    }

    public Map<DrainageWetlandClass, Double> getNodePenalties() {
        return nodePenalties;
    }

    public void setNodePenalties(Map<DrainageWetlandClass, Double> nodePenalties) {
        this.nodePenalties = nodePenalties;
    }

    public Map<DrainageSublayerType, Double> getCrossingBaseCostsKmEq() {
        return crossingBaseCostsKmEq;
    }

    public void setCrossingBaseCostsKmEq(Map<DrainageSublayerType, Double> crossingBaseCostsKmEq) {
        this.crossingBaseCostsKmEq = crossingBaseCostsKmEq;
    }
}
