package in.marg.routing;

import in.marg.demographic.DemographicVulnerabilityTier;
import in.marg.demographic.PopulationDensityClass;

import java.util.EnumMap;
import java.util.Map;

/**
 * Spatial cost policy for Layer 12: Population Density, Village/Urban Census Population,
 * and Demographic Vulnerability Exposure across the Direct RoW and Concentric Influence Buffers.
 */
public class DemographicCostPolicy {

    private final Map<PopulationDensityClass, Double> nodePenalties;
    private final Map<DemographicVulnerabilityTier, Double> vulnerabilityMultipliers;
    private final double rowEvaluationHalfWidthMeters;
    private final double primaryDemographicBufferMeters;
    private final double secondaryDemographicBufferMeters;
    private final double directRowPenaltyPer1000PopKmEq;
    private final double primaryBufferPenaltyPer1000PopKmEq;
    private final double secondaryBufferPenaltyPer1000PopKmEq;
    private final boolean blockVeryHighUrbanDensityCore;
    private final boolean blockHighDensityPopulationClusters;
    private final boolean blockNoData;

    public DemographicCostPolicy(
            Map<PopulationDensityClass, Double> nodePenalties,
            Map<DemographicVulnerabilityTier, Double> vulnerabilityMultipliers,
            double rowEvaluationHalfWidthMeters,
            double primaryDemographicBufferMeters,
            double secondaryDemographicBufferMeters,
            double directRowPenaltyPer1000PopKmEq,
            double primaryBufferPenaltyPer1000PopKmEq,
            double secondaryBufferPenaltyPer1000PopKmEq,
            boolean blockVeryHighUrbanDensityCore,
            boolean blockHighDensityPopulationClusters,
            boolean blockNoData) {
        this.nodePenalties = new EnumMap<>(PopulationDensityClass.class);
        this.nodePenalties.putAll(nodePenalties);
        this.vulnerabilityMultipliers = new EnumMap<>(DemographicVulnerabilityTier.class);
        this.vulnerabilityMultipliers.putAll(vulnerabilityMultipliers);
        this.rowEvaluationHalfWidthMeters = Math.max(10.0, rowEvaluationHalfWidthMeters);
        this.primaryDemographicBufferMeters = Math.max(50.0, primaryDemographicBufferMeters);
        this.secondaryDemographicBufferMeters =
                Math.max(this.primaryDemographicBufferMeters, secondaryDemographicBufferMeters);
        this.directRowPenaltyPer1000PopKmEq = Math.max(0.0, directRowPenaltyPer1000PopKmEq);
        this.primaryBufferPenaltyPer1000PopKmEq = Math.max(0.0, primaryBufferPenaltyPer1000PopKmEq);
        this.secondaryBufferPenaltyPer1000PopKmEq = Math.max(0.0, secondaryBufferPenaltyPer1000PopKmEq);
        this.blockVeryHighUrbanDensityCore = blockVeryHighUrbanDensityCore;
        this.blockHighDensityPopulationClusters = blockHighDensityPopulationClusters;
        this.blockNoData = blockNoData;
    }

    public static DemographicCostPolicy defaultPolicy() {
        Map<PopulationDensityClass, Double> nodeDefaults = new EnumMap<>(PopulationDensityClass.class);
        nodeDefaults.put(PopulationDensityClass.UNINHABITED_OR_BACKGROUND, 0.0);
        nodeDefaults.put(PopulationDensityClass.SPARSE_RURAL_DISPERSED, 0.15);
        nodeDefaults.put(PopulationDensityClass.LOW_DENSITY_VILLAGE, 0.45);
        nodeDefaults.put(PopulationDensityClass.MODERATE_DENSITY_SETTLEMENT, 1.20);
        nodeDefaults.put(PopulationDensityClass.HIGH_DENSITY_POPULATION_CLUSTER, 2.65);
        nodeDefaults.put(PopulationDensityClass.VERY_HIGH_URBAN_DENSITY_CORE, 4.20);
        nodeDefaults.put(PopulationDensityClass.NODATA, 0.0);

        Map<DemographicVulnerabilityTier, Double> vulnDefaults =
                new EnumMap<>(DemographicVulnerabilityTier.class);
        for (DemographicVulnerabilityTier tier : DemographicVulnerabilityTier.values()) {
            vulnDefaults.put(tier, tier.socialSensitivityMultiplier());
        }

        return new DemographicCostPolicy(
                nodeDefaults,
                vulnDefaults,
                30.0,
                250.0,
                500.0,
                0.65,
                0.28,
                0.10,
                false,
                false,
                false);
    }

    public double nodePenalty(PopulationDensityClass densityClass) {
        return nodePenalties.getOrDefault(densityClass, 0.0);
    }

    public double vulnerabilityMultiplier(DemographicVulnerabilityTier tier) {
        return vulnerabilityMultipliers.getOrDefault(tier, tier.socialSensitivityMultiplier());
    }

    public double computeStepExposureCostKmEq(
            double directRowWeightedPopUnits,
            double primaryBufferWeightedPopUnits,
            double secondaryBufferWeightedPopUnits) {
        return (directRowWeightedPopUnits * directRowPenaltyPer1000PopKmEq)
                + (primaryBufferWeightedPopUnits * primaryBufferPenaltyPer1000PopKmEq)
                + (secondaryBufferWeightedPopUnits * secondaryBufferPenaltyPer1000PopKmEq);
    }

    public double rowEvaluationHalfWidthMeters() {
        return rowEvaluationHalfWidthMeters;
    }

    public double primaryDemographicBufferMeters() {
        return primaryDemographicBufferMeters;
    }

    public double secondaryDemographicBufferMeters() {
        return secondaryDemographicBufferMeters;
    }

    public double directRowPenaltyPer1000PopKmEq() {
        return directRowPenaltyPer1000PopKmEq;
    }

    public double primaryBufferPenaltyPer1000PopKmEq() {
        return primaryBufferPenaltyPer1000PopKmEq;
    }

    public double secondaryBufferPenaltyPer1000PopKmEq() {
        return secondaryBufferPenaltyPer1000PopKmEq;
    }

    public boolean blockVeryHighUrbanDensityCore() {
        return blockVeryHighUrbanDensityCore;
    }

    public boolean blockHighDensityPopulationClusters() {
        return blockHighDensityPopulationClusters;
    }

    public boolean blockNoData() {
        return blockNoData;
    }

    public Map<PopulationDensityClass, Double> nodePenalties() {
        return Map.copyOf(nodePenalties);
    }

    public Map<DemographicVulnerabilityTier, Double> vulnerabilityMultipliers() {
        return Map.copyOf(vulnerabilityMultipliers);
    }
}
