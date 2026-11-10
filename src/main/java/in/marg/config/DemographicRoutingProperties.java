package in.marg.config;

import in.marg.demographic.DemographicVulnerabilityTier;
import in.marg.demographic.PopulationDensityClass;
import in.marg.routing.DemographicCostPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "marg.routing.demographic")
public class DemographicRoutingProperties {

    private boolean blockVeryHighUrbanDensityCore = false;
    private boolean blockHighDensityPopulationClusters = false;
    private boolean blockNoData = false;
    private double rowEvaluationHalfWidthMeters = 30.0;
    private double primaryDemographicBufferMeters = 250.0;
    private double secondaryDemographicBufferMeters = 500.0;
    private double directRowPenaltyPer1000PopKmEq = 0.65;
    private double primaryBufferPenaltyPer1000PopKmEq = 0.28;
    private double secondaryBufferPenaltyPer1000PopKmEq = 0.10;

    private Map<PopulationDensityClass, Double> nodePenalties =
            new EnumMap<>(DemographicCostPolicy.defaultPolicy().nodePenalties());

    private Map<DemographicVulnerabilityTier, Double> vulnerabilityMultipliers =
            new EnumMap<>(DemographicCostPolicy.defaultPolicy().vulnerabilityMultipliers());

    public DemographicCostPolicy toPolicy() {
        Map<PopulationDensityClass, Double> mergedNodes =
                new EnumMap<>(DemographicCostPolicy.defaultPolicy().nodePenalties());
        mergedNodes.putAll(nodePenalties);

        Map<DemographicVulnerabilityTier, Double> mergedVuln =
                new EnumMap<>(DemographicCostPolicy.defaultPolicy().vulnerabilityMultipliers());
        mergedVuln.putAll(vulnerabilityMultipliers);

        return new DemographicCostPolicy(
                mergedNodes,
                mergedVuln,
                rowEvaluationHalfWidthMeters,
                primaryDemographicBufferMeters,
                secondaryDemographicBufferMeters,
                directRowPenaltyPer1000PopKmEq,
                primaryBufferPenaltyPer1000PopKmEq,
                secondaryBufferPenaltyPer1000PopKmEq,
                blockVeryHighUrbanDensityCore,
                blockHighDensityPopulationClusters,
                blockNoData);
    }

    public boolean isBlockVeryHighUrbanDensityCore() {
        return blockVeryHighUrbanDensityCore;
    }

    public void setBlockVeryHighUrbanDensityCore(boolean blockVeryHighUrbanDensityCore) {
        this.blockVeryHighUrbanDensityCore = blockVeryHighUrbanDensityCore;
    }

    public boolean isBlockHighDensityPopulationClusters() {
        return blockHighDensityPopulationClusters;
    }

    public void setBlockHighDensityPopulationClusters(boolean blockHighDensityPopulationClusters) {
        this.blockHighDensityPopulationClusters = blockHighDensityPopulationClusters;
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

    public double getPrimaryDemographicBufferMeters() {
        return primaryDemographicBufferMeters;
    }

    public void setPrimaryDemographicBufferMeters(double primaryDemographicBufferMeters) {
        this.primaryDemographicBufferMeters = primaryDemographicBufferMeters;
    }

    public double getSecondaryDemographicBufferMeters() {
        return secondaryDemographicBufferMeters;
    }

    public void setSecondaryDemographicBufferMeters(double secondaryDemographicBufferMeters) {
        this.secondaryDemographicBufferMeters = secondaryDemographicBufferMeters;
    }

    public double getDirectRowPenaltyPer1000PopKmEq() {
        return directRowPenaltyPer1000PopKmEq;
    }

    public void setDirectRowPenaltyPer1000PopKmEq(double directRowPenaltyPer1000PopKmEq) {
        this.directRowPenaltyPer1000PopKmEq = directRowPenaltyPer1000PopKmEq;
    }

    public double getPrimaryBufferPenaltyPer1000PopKmEq() {
        return primaryBufferPenaltyPer1000PopKmEq;
    }

    public void setPrimaryBufferPenaltyPer1000PopKmEq(double primaryBufferPenaltyPer1000PopKmEq) {
        this.primaryBufferPenaltyPer1000PopKmEq = primaryBufferPenaltyPer1000PopKmEq;
    }

    public double getSecondaryBufferPenaltyPer1000PopKmEq() {
        return secondaryBufferPenaltyPer1000PopKmEq;
    }

    public void setSecondaryBufferPenaltyPer1000PopKmEq(double secondaryBufferPenaltyPer1000PopKmEq) {
        this.secondaryBufferPenaltyPer1000PopKmEq = secondaryBufferPenaltyPer1000PopKmEq;
    }

    public Map<PopulationDensityClass, Double> getNodePenalties() {
        return nodePenalties;
    }

    public void setNodePenalties(Map<PopulationDensityClass, Double> nodePenalties) {
        this.nodePenalties = nodePenalties;
    }

    public Map<DemographicVulnerabilityTier, Double> getVulnerabilityMultipliers() {
        return vulnerabilityMultipliers;
    }

    public void setVulnerabilityMultipliers(
            Map<DemographicVulnerabilityTier, Double> vulnerabilityMultipliers) {
        this.vulnerabilityMultipliers = vulnerabilityMultipliers;
    }
}
