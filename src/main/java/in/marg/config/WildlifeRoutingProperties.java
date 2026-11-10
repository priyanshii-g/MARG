package in.marg.config;

import in.marg.routing.WildlifeCostPolicy;
import in.marg.wildlife.WildlifeCorridorTier;
import in.marg.wildlife.WildlifeZoneClass;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "marg.routing.wildlife")
public class WildlifeRoutingProperties {

    private boolean blockCriticalCorridors = false;
    private boolean blockNoData = false;
    private double barrierSkewPenaltyFactor = 1.45;
    private double parallelCorridorPenalty = 2.60;
    private double parallelCorridorRadiusMeters = 280.0;

    private Map<WildlifeZoneClass, Double> nodePenalties =
            new EnumMap<>(WildlifeCostPolicy.defaultPolicy().nodePenalties());

    private Map<WildlifeCorridorTier, Double> passageBaseCostKmEq =
            new EnumMap<>(WildlifeCostPolicy.defaultPolicy().passageBaseCostKmEq());

    public WildlifeCostPolicy toPolicy() {
        Map<WildlifeZoneClass, Double> mergedNodes =
                new EnumMap<>(WildlifeCostPolicy.defaultPolicy().nodePenalties());
        mergedNodes.putAll(nodePenalties);

        Map<WildlifeCorridorTier, Double> mergedPassages =
                new EnumMap<>(WildlifeCostPolicy.defaultPolicy().passageBaseCostKmEq());
        mergedPassages.putAll(passageBaseCostKmEq);

        return new WildlifeCostPolicy(
                mergedNodes,
                mergedPassages,
                barrierSkewPenaltyFactor,
                parallelCorridorPenalty,
                parallelCorridorRadiusMeters,
                blockCriticalCorridors,
                blockNoData);
    }

    public boolean isBlockCriticalCorridors() {
        return blockCriticalCorridors;
    }

    public void setBlockCriticalCorridors(boolean blockCriticalCorridors) {
        this.blockCriticalCorridors = blockCriticalCorridors;
    }

    public boolean isBlockNoData() {
        return blockNoData;
    }

    public void setBlockNoData(boolean blockNoData) {
        this.blockNoData = blockNoData;
    }

    public double getBarrierSkewPenaltyFactor() {
        return barrierSkewPenaltyFactor;
    }

    public void setBarrierSkewPenaltyFactor(double barrierSkewPenaltyFactor) {
        this.barrierSkewPenaltyFactor = barrierSkewPenaltyFactor;
    }

    public double getParallelCorridorPenalty() {
        return parallelCorridorPenalty;
    }

    public void setParallelCorridorPenalty(double parallelCorridorPenalty) {
        this.parallelCorridorPenalty = parallelCorridorPenalty;
    }

    public double getParallelCorridorRadiusMeters() {
        return parallelCorridorRadiusMeters;
    }

    public void setParallelCorridorRadiusMeters(double parallelCorridorRadiusMeters) {
        this.parallelCorridorRadiusMeters = parallelCorridorRadiusMeters;
    }

    public Map<WildlifeZoneClass, Double> getNodePenalties() {
        return nodePenalties;
    }

    public void setNodePenalties(Map<WildlifeZoneClass, Double> nodePenalties) {
        this.nodePenalties = nodePenalties;
    }

    public Map<WildlifeCorridorTier, Double> getPassageBaseCostKmEq() {
        return passageBaseCostKmEq;
    }

    public void setPassageBaseCostKmEq(Map<WildlifeCorridorTier, Double> passageBaseCostKmEq) {
        this.passageBaseCostKmEq = passageBaseCostKmEq;
    }
}
