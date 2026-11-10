package in.marg.config;

import in.marg.routing.UtilityCostPolicy;
import in.marg.utility.UtilityCorridorClass;
import in.marg.utility.UtilityCorridorTier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "marg.routing.utility")
public class UtilityRoutingProperties {

    private boolean blockSubstationsAndTowers = false;
    private boolean blockNoData = false;
    private double crossingSkewPenaltyFactor = 1.40;
    private double parallelUtilityRowPenalty = 2.50;
    private double parallelUtilityRadiusMeters = 100.0;
    private double towerProximityThresholdMeters = 50.0;
    private double towerProximitySurchargeFactor = 0.50;

    private Map<UtilityCorridorClass, Double> nodePenalties =
            new EnumMap<>(UtilityCostPolicy.defaultPolicy().nodePenalties());

    private Map<UtilityCorridorTier, Double> crossingProtectionBaseCostKmEq =
            new EnumMap<>(UtilityCostPolicy.defaultPolicy().crossingProtectionBaseCostKmEq());

    public UtilityCostPolicy toPolicy() {
        Map<UtilityCorridorClass, Double> mergedNodes =
                new EnumMap<>(UtilityCostPolicy.defaultPolicy().nodePenalties());
        mergedNodes.putAll(nodePenalties);

        Map<UtilityCorridorTier, Double> mergedCrossings =
                new EnumMap<>(UtilityCostPolicy.defaultPolicy().crossingProtectionBaseCostKmEq());
        mergedCrossings.putAll(crossingProtectionBaseCostKmEq);

        return new UtilityCostPolicy(
                mergedNodes,
                mergedCrossings,
                crossingSkewPenaltyFactor,
                parallelUtilityRowPenalty,
                parallelUtilityRadiusMeters,
                towerProximityThresholdMeters,
                towerProximitySurchargeFactor,
                blockSubstationsAndTowers,
                blockNoData);
    }

    public boolean isBlockSubstationsAndTowers() {
        return blockSubstationsAndTowers;
    }

    public void setBlockSubstationsAndTowers(boolean blockSubstationsAndTowers) {
        this.blockSubstationsAndTowers = blockSubstationsAndTowers;
    }

    public boolean isBlockNoData() {
        return blockNoData;
    }

    public void setBlockNoData(boolean blockNoData) {
        this.blockNoData = blockNoData;
    }

    public double getCrossingSkewPenaltyFactor() {
        return crossingSkewPenaltyFactor;
    }

    public void setCrossingSkewPenaltyFactor(double crossingSkewPenaltyFactor) {
        this.crossingSkewPenaltyFactor = crossingSkewPenaltyFactor;
    }

    public double getParallelUtilityRowPenalty() {
        return parallelUtilityRowPenalty;
    }

    public void setParallelUtilityRowPenalty(double parallelUtilityRowPenalty) {
        this.parallelUtilityRowPenalty = parallelUtilityRowPenalty;
    }

    public double getParallelUtilityRadiusMeters() {
        return parallelUtilityRadiusMeters;
    }

    public void setParallelUtilityRadiusMeters(double parallelUtilityRadiusMeters) {
        this.parallelUtilityRadiusMeters = parallelUtilityRadiusMeters;
    }

    public double getTowerProximityThresholdMeters() {
        return towerProximityThresholdMeters;
    }

    public void setTowerProximityThresholdMeters(double towerProximityThresholdMeters) {
        this.towerProximityThresholdMeters = towerProximityThresholdMeters;
    }

    public double getTowerProximitySurchargeFactor() {
        return towerProximitySurchargeFactor;
    }

    public void setTowerProximitySurchargeFactor(double towerProximitySurchargeFactor) {
        this.towerProximitySurchargeFactor = towerProximitySurchargeFactor;
    }

    public Map<UtilityCorridorClass, Double> getNodePenalties() {
        return nodePenalties;
    }

    public void setNodePenalties(Map<UtilityCorridorClass, Double> nodePenalties) {
        this.nodePenalties = nodePenalties;
    }

    public Map<UtilityCorridorTier, Double> getCrossingProtectionBaseCostKmEq() {
        return crossingProtectionBaseCostKmEq;
    }

    public void setCrossingProtectionBaseCostKmEq(
            Map<UtilityCorridorTier, Double> crossingProtectionBaseCostKmEq) {
        this.crossingProtectionBaseCostKmEq = crossingProtectionBaseCostKmEq;
    }
}
