package in.marg.config;

import in.marg.building.BuildingDensityClass;
import in.marg.building.BuildingType;
import in.marg.building.SettlementClassification;
import in.marg.routing.BuildingCostPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "marg.routing.building")
public class BuildingRoutingProperties {

    private boolean blockInstitutionalAndReligiousCompounds = false;
    private boolean blockHighDensityBuiltUp = false;
    private boolean blockNoData = false;
    private double rowEvaluationHalfWidthMeters = 30.0;
    private double structureProximityBufferMeters = 60.0;
    private double proximitySurchargeFactor = 0.35;

    private Map<BuildingDensityClass, Double> nodePenalties =
            new EnumMap<>(BuildingCostPolicy.defaultPolicy().nodePenalties());

    private Map<BuildingType, Double> structureDemolitionCostKmEq =
            new EnumMap<>(BuildingCostPolicy.defaultPolicy().structureDemolitionCostKmEq());

    private Map<SettlementClassification, Double> settlementSeverancePenalty =
            new EnumMap<>(BuildingCostPolicy.defaultPolicy().settlementSeverancePenalty());

    public BuildingCostPolicy toPolicy() {
        Map<BuildingDensityClass, Double> mergedNodes =
                new EnumMap<>(BuildingCostPolicy.defaultPolicy().nodePenalties());
        mergedNodes.putAll(nodePenalties);

        Map<BuildingType, Double> mergedStructures =
                new EnumMap<>(BuildingCostPolicy.defaultPolicy().structureDemolitionCostKmEq());
        mergedStructures.putAll(structureDemolitionCostKmEq);

        Map<SettlementClassification, Double> mergedSeverance =
                new EnumMap<>(BuildingCostPolicy.defaultPolicy().settlementSeverancePenalty());
        mergedSeverance.putAll(settlementSeverancePenalty);

        return new BuildingCostPolicy(
                mergedNodes,
                mergedStructures,
                mergedSeverance,
                rowEvaluationHalfWidthMeters,
                structureProximityBufferMeters,
                proximitySurchargeFactor,
                blockInstitutionalAndReligiousCompounds,
                blockHighDensityBuiltUp,
                blockNoData);
    }

    public boolean isBlockInstitutionalAndReligiousCompounds() {
        return blockInstitutionalAndReligiousCompounds;
    }

    public void setBlockInstitutionalAndReligiousCompounds(
            boolean blockInstitutionalAndReligiousCompounds) {
        this.blockInstitutionalAndReligiousCompounds = blockInstitutionalAndReligiousCompounds;
    }

    public boolean isBlockHighDensityBuiltUp() {
        return blockHighDensityBuiltUp;
    }

    public void setBlockHighDensityBuiltUp(boolean blockHighDensityBuiltUp) {
        this.blockHighDensityBuiltUp = blockHighDensityBuiltUp;
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

    public double getStructureProximityBufferMeters() {
        return structureProximityBufferMeters;
    }

    public void setStructureProximityBufferMeters(double structureProximityBufferMeters) {
        this.structureProximityBufferMeters = structureProximityBufferMeters;
    }

    public double getProximitySurchargeFactor() {
        return proximitySurchargeFactor;
    }

    public void setProximitySurchargeFactor(double proximitySurchargeFactor) {
        this.proximitySurchargeFactor = proximitySurchargeFactor;
    }

    public Map<BuildingDensityClass, Double> getNodePenalties() {
        return nodePenalties;
    }

    public void setNodePenalties(Map<BuildingDensityClass, Double> nodePenalties) {
        this.nodePenalties = nodePenalties;
    }

    public Map<BuildingType, Double> getStructureDemolitionCostKmEq() {
        return structureDemolitionCostKmEq;
    }

    public void setStructureDemolitionCostKmEq(
            Map<BuildingType, Double> structureDemolitionCostKmEq) {
        this.structureDemolitionCostKmEq = structureDemolitionCostKmEq;
    }

    public Map<SettlementClassification, Double> getSettlementSeverancePenalty() {
        return settlementSeverancePenalty;
    }

    public void setSettlementSeverancePenalty(
            Map<SettlementClassification, Double> settlementSeverancePenalty) {
        this.settlementSeverancePenalty = settlementSeverancePenalty;
    }
}
