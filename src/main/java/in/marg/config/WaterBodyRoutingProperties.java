package in.marg.config;

import in.marg.hydrology.RiverHierarchyClass;
import in.marg.hydrology.WaterBodyClass;
import in.marg.routing.WaterBodyCostPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "marg.routing.hydrology")
public class WaterBodyRoutingProperties {

    private boolean blockWetlands = false;
    private boolean blockNoData = false;
    private double hydraulicSkewPenaltyFactor = 1.40;
    private double parallelBankPenalty = 2.40;
    private double parallelBankRadiusMeters = 180.0;

    private Map<WaterBodyClass, Double> nodePenalties =
            new EnumMap<>(WaterBodyCostPolicy.defaultPolicy().nodePenalties());

    private Map<RiverHierarchyClass, Double> bridgeBaseCostKmEq =
            new EnumMap<>(WaterBodyCostPolicy.defaultPolicy().bridgeBaseCostKmEq());

    public WaterBodyCostPolicy toPolicy() {
        Map<WaterBodyClass, Double> mergedNodes =
                new EnumMap<>(WaterBodyCostPolicy.defaultPolicy().nodePenalties());
        mergedNodes.putAll(nodePenalties);

        Map<RiverHierarchyClass, Double> mergedBridges =
                new EnumMap<>(WaterBodyCostPolicy.defaultPolicy().bridgeBaseCostKmEq());
        mergedBridges.putAll(bridgeBaseCostKmEq);

        return new WaterBodyCostPolicy(
                mergedNodes,
                mergedBridges,
                hydraulicSkewPenaltyFactor,
                parallelBankPenalty,
                parallelBankRadiusMeters,
                blockWetlands,
                blockNoData);
    }

    public boolean isBlockWetlands() {
        return blockWetlands;
    }

    public void setBlockWetlands(boolean blockWetlands) {
        this.blockWetlands = blockWetlands;
    }

    public boolean isBlockNoData() {
        return blockNoData;
    }

    public void setBlockNoData(boolean blockNoData) {
        this.blockNoData = blockNoData;
    }

    public double getHydraulicSkewPenaltyFactor() {
        return hydraulicSkewPenaltyFactor;
    }

    public void setHydraulicSkewPenaltyFactor(double hydraulicSkewPenaltyFactor) {
        this.hydraulicSkewPenaltyFactor = hydraulicSkewPenaltyFactor;
    }

    public double getParallelBankPenalty() {
        return parallelBankPenalty;
    }

    public void setParallelBankPenalty(double parallelBankPenalty) {
        this.parallelBankPenalty = parallelBankPenalty;
    }

    public double getParallelBankRadiusMeters() {
        return parallelBankRadiusMeters;
    }

    public void setParallelBankRadiusMeters(double parallelBankRadiusMeters) {
        this.parallelBankRadiusMeters = parallelBankRadiusMeters;
    }

    public Map<WaterBodyClass, Double> getNodePenalties() {
        return nodePenalties;
    }

    public void setNodePenalties(Map<WaterBodyClass, Double> nodePenalties) {
        this.nodePenalties = nodePenalties;
    }

    public Map<RiverHierarchyClass, Double> getBridgeBaseCostKmEq() {
        return bridgeBaseCostKmEq;
    }

    public void setBridgeBaseCostKmEq(Map<RiverHierarchyClass, Double> bridgeBaseCostKmEq) {
        this.bridgeBaseCostKmEq = bridgeBaseCostKmEq;
    }
}
