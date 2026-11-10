package in.marg.config;

import in.marg.routing.TransportCostPolicy;
import in.marg.transport.FacilityMode;
import in.marg.transport.TransportCategory;
import in.marg.transport.TransportCorridorClass;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "marg.routing.transport")
public class TransportRoutingProperties {

    private FacilityMode defaultFacilityMode = FacilityMode.NATIONAL_HIGHWAY;
    private boolean blockNoData = false;
    private double skewAnglePenaltyFactor = 1.25;
    private double parallelAcutePenalty = 2.00;
    private double parallelProximityRadiusMeters = 150.0;

    // Mode presets: EXPRESSWAY
    private double expresswayAccessibilityPull = 0.0;
    private double expresswayMinInterchangeSpacingKm = 15.0;
    private int expresswayMinHubPopulation = 40000;
    private double expresswayDesignCapacityPcuHr = 4800.0;
    private double expresswayBaseThroughTrafficPcuHr = 1400.0;

    // Mode presets: NATIONAL_HIGHWAY
    private double nationalHighwayAccessibilityPull = 0.35;
    private double nationalHighwayMinInterchangeSpacingKm = 8.0;
    private int nationalHighwayMinHubPopulation = 22000;
    private double nationalHighwayDesignCapacityPcuHr = 3600.0;
    private double nationalHighwayBaseThroughTrafficPcuHr = 1350.0;

    // Shared collector & traffic carrying capacity settings
    private double maxVcRatio = 0.75;
    private double peakTripGenerationPcuPer1000Pop = 9.5;
    private double collectorSweetSpotMinKm = 1.5;
    private double collectorSweetSpotMaxKm = 5.0;
    private double collectorLongitudinalBandKm = 10.0;

    private Map<TransportCorridorClass, Double> nodePenalties =
            new EnumMap<>(TransportCostPolicy.defaultPolicy().nodePenalties());

    private Map<TransportCategory, Double> crossingBaseCostKmEq =
            new EnumMap<>(TransportCostPolicy.defaultPolicy().crossingBaseCostKmEq());

    public TransportCostPolicy toPolicy() {
        Map<TransportCorridorClass, Double> mergedNodes =
                new EnumMap<>(TransportCostPolicy.defaultPolicy().nodePenalties());
        mergedNodes.putAll(nodePenalties);

        Map<TransportCategory, Double> mergedCrossings =
                new EnumMap<>(TransportCostPolicy.defaultPolicy().crossingBaseCostKmEq());
        mergedCrossings.putAll(crossingBaseCostKmEq);

        return new TransportCostPolicy(
                mergedNodes,
                mergedCrossings,
                skewAnglePenaltyFactor,
                parallelAcutePenalty,
                parallelProximityRadiusMeters,
                blockNoData);
    }

    public double accessibilityPullFor(FacilityMode mode) {
        return mode == FacilityMode.EXPRESSWAY
                ? expresswayAccessibilityPull
                : nationalHighwayAccessibilityPull;
    }

    public double minInterchangeSpacingKmFor(FacilityMode mode) {
        return mode == FacilityMode.EXPRESSWAY
                ? expresswayMinInterchangeSpacingKm
                : nationalHighwayMinInterchangeSpacingKm;
    }

    public int minHubPopulationFor(FacilityMode mode) {
        return mode == FacilityMode.EXPRESSWAY
                ? expresswayMinHubPopulation
                : nationalHighwayMinHubPopulation;
    }

    public double designCapacityPcuHrFor(FacilityMode mode) {
        return mode == FacilityMode.EXPRESSWAY
                ? expresswayDesignCapacityPcuHr
                : nationalHighwayDesignCapacityPcuHr;
    }

    public double baseThroughTrafficPcuHrFor(FacilityMode mode) {
        return mode == FacilityMode.EXPRESSWAY
                ? expresswayBaseThroughTrafficPcuHr
                : nationalHighwayBaseThroughTrafficPcuHr;
    }

    public FacilityMode getDefaultFacilityMode() {
        return defaultFacilityMode;
    }

    public void setDefaultFacilityMode(FacilityMode defaultFacilityMode) {
        this.defaultFacilityMode = defaultFacilityMode;
    }

    public boolean isBlockNoData() {
        return blockNoData;
    }

    public void setBlockNoData(boolean blockNoData) {
        this.blockNoData = blockNoData;
    }

    public double getSkewAnglePenaltyFactor() {
        return skewAnglePenaltyFactor;
    }

    public void setSkewAnglePenaltyFactor(double skewAnglePenaltyFactor) {
        this.skewAnglePenaltyFactor = skewAnglePenaltyFactor;
    }

    public double getParallelAcutePenalty() {
        return parallelAcutePenalty;
    }

    public void setParallelAcutePenalty(double parallelAcutePenalty) {
        this.parallelAcutePenalty = parallelAcutePenalty;
    }

    public double getParallelProximityRadiusMeters() {
        return parallelProximityRadiusMeters;
    }

    public void setParallelProximityRadiusMeters(double parallelProximityRadiusMeters) {
        this.parallelProximityRadiusMeters = parallelProximityRadiusMeters;
    }

    public double getExpresswayAccessibilityPull() {
        return expresswayAccessibilityPull;
    }

    public void setExpresswayAccessibilityPull(double expresswayAccessibilityPull) {
        this.expresswayAccessibilityPull = expresswayAccessibilityPull;
    }

    public double getExpresswayMinInterchangeSpacingKm() {
        return expresswayMinInterchangeSpacingKm;
    }

    public void setExpresswayMinInterchangeSpacingKm(double expresswayMinInterchangeSpacingKm) {
        this.expresswayMinInterchangeSpacingKm = expresswayMinInterchangeSpacingKm;
    }

    public int getExpresswayMinHubPopulation() {
        return expresswayMinHubPopulation;
    }

    public void setExpresswayMinHubPopulation(int expresswayMinHubPopulation) {
        this.expresswayMinHubPopulation = expresswayMinHubPopulation;
    }

    public double getExpresswayDesignCapacityPcuHr() {
        return expresswayDesignCapacityPcuHr;
    }

    public void setExpresswayDesignCapacityPcuHr(double expresswayDesignCapacityPcuHr) {
        this.expresswayDesignCapacityPcuHr = expresswayDesignCapacityPcuHr;
    }

    public double getExpresswayBaseThroughTrafficPcuHr() {
        return expresswayBaseThroughTrafficPcuHr;
    }

    public void setExpresswayBaseThroughTrafficPcuHr(double expresswayBaseThroughTrafficPcuHr) {
        this.expresswayBaseThroughTrafficPcuHr = expresswayBaseThroughTrafficPcuHr;
    }

    public double getNationalHighwayAccessibilityPull() {
        return nationalHighwayAccessibilityPull;
    }

    public void setNationalHighwayAccessibilityPull(double nationalHighwayAccessibilityPull) {
        this.nationalHighwayAccessibilityPull = nationalHighwayAccessibilityPull;
    }

    public double getNationalHighwayMinInterchangeSpacingKm() {
        return nationalHighwayMinInterchangeSpacingKm;
    }

    public void setNationalHighwayMinInterchangeSpacingKm(double nationalHighwayMinInterchangeSpacingKm) {
        this.nationalHighwayMinInterchangeSpacingKm = nationalHighwayMinInterchangeSpacingKm;
    }

    public int getNationalHighwayMinHubPopulation() {
        return nationalHighwayMinHubPopulation;
    }

    public void setNationalHighwayMinHubPopulation(int nationalHighwayMinHubPopulation) {
        this.nationalHighwayMinHubPopulation = nationalHighwayMinHubPopulation;
    }

    public double getNationalHighwayDesignCapacityPcuHr() {
        return nationalHighwayDesignCapacityPcuHr;
    }

    public void setNationalHighwayDesignCapacityPcuHr(double nationalHighwayDesignCapacityPcuHr) {
        this.nationalHighwayDesignCapacityPcuHr = nationalHighwayDesignCapacityPcuHr;
    }

    public double getNationalHighwayBaseThroughTrafficPcuHr() {
        return nationalHighwayBaseThroughTrafficPcuHr;
    }

    public void setNationalHighwayBaseThroughTrafficPcuHr(double nationalHighwayBaseThroughTrafficPcuHr) {
        this.nationalHighwayBaseThroughTrafficPcuHr = nationalHighwayBaseThroughTrafficPcuHr;
    }

    public double getMaxVcRatio() {
        return maxVcRatio;
    }

    public void setMaxVcRatio(double maxVcRatio) {
        this.maxVcRatio = maxVcRatio;
    }

    public double getPeakTripGenerationPcuPer1000Pop() {
        return peakTripGenerationPcuPer1000Pop;
    }

    public void setPeakTripGenerationPcuPer1000Pop(double peakTripGenerationPcuPer1000Pop) {
        this.peakTripGenerationPcuPer1000Pop = peakTripGenerationPcuPer1000Pop;
    }

    public double getCollectorSweetSpotMinKm() {
        return collectorSweetSpotMinKm;
    }

    public void setCollectorSweetSpotMinKm(double collectorSweetSpotMinKm) {
        this.collectorSweetSpotMinKm = collectorSweetSpotMinKm;
    }

    public double getCollectorSweetSpotMaxKm() {
        return collectorSweetSpotMaxKm;
    }

    public void setCollectorSweetSpotMaxKm(double collectorSweetSpotMaxKm) {
        this.collectorSweetSpotMaxKm = collectorSweetSpotMaxKm;
    }

    public double getCollectorLongitudinalBandKm() {
        return collectorLongitudinalBandKm;
    }

    public void setCollectorLongitudinalBandKm(double collectorLongitudinalBandKm) {
        this.collectorLongitudinalBandKm = collectorLongitudinalBandKm;
    }

    public Map<TransportCorridorClass, Double> getNodePenalties() {
        return nodePenalties;
    }

    public void setNodePenalties(Map<TransportCorridorClass, Double> nodePenalties) {
        this.nodePenalties = nodePenalties;
    }

    public Map<TransportCategory, Double> getCrossingBaseCostKmEq() {
        return crossingBaseCostKmEq;
    }

    public void setCrossingBaseCostKmEq(Map<TransportCategory, Double> crossingBaseCostKmEq) {
        this.crossingBaseCostKmEq = crossingBaseCostKmEq;
    }
}
