package in.marg.routing;

import in.marg.building.BuildingDensityClass;
import in.marg.building.BuildingType;
import in.marg.building.SettlementClassification;

import java.util.EnumMap;
import java.util.Map;

/**
 * Spatial cost policy for 2D Settlement Boundaries, Built-Up Density Classes, and
 * Individual Building Footprints within the proposed highway Right-of-Way (RoW) and
 * Construction Clearance Buffer.
 */
public class BuildingCostPolicy {

    private final Map<BuildingDensityClass, Double> nodePenalties;
    private final Map<BuildingType, Double> structureDemolitionCostKmEq;
    private final Map<SettlementClassification, Double> settlementSeverancePenalty;
    private final double rowEvaluationHalfWidthMeters;
    private final double structureProximityBufferMeters;
    private final double proximitySurchargeFactor;
    private final boolean blockInstitutionalAndReligiousCompounds;
    private final boolean blockHighDensityBuiltUp;
    private final boolean blockNoData;

    public BuildingCostPolicy(
            Map<BuildingDensityClass, Double> nodePenalties,
            Map<BuildingType, Double> structureDemolitionCostKmEq,
            Map<SettlementClassification, Double> settlementSeverancePenalty,
            double rowEvaluationHalfWidthMeters,
            double structureProximityBufferMeters,
            double proximitySurchargeFactor,
            boolean blockInstitutionalAndReligiousCompounds,
            boolean blockHighDensityBuiltUp,
            boolean blockNoData) {
        this.nodePenalties = new EnumMap<>(BuildingDensityClass.class);
        this.nodePenalties.putAll(nodePenalties);
        this.structureDemolitionCostKmEq = new EnumMap<>(BuildingType.class);
        this.structureDemolitionCostKmEq.putAll(structureDemolitionCostKmEq);
        this.settlementSeverancePenalty = new EnumMap<>(SettlementClassification.class);
        this.settlementSeverancePenalty.putAll(settlementSeverancePenalty);
        this.rowEvaluationHalfWidthMeters = Math.max(10.0, rowEvaluationHalfWidthMeters);
        this.structureProximityBufferMeters = Math.max(10.0, structureProximityBufferMeters);
        this.proximitySurchargeFactor = Math.max(0.0, proximitySurchargeFactor);
        this.blockInstitutionalAndReligiousCompounds = blockInstitutionalAndReligiousCompounds;
        this.blockHighDensityBuiltUp = blockHighDensityBuiltUp;
        this.blockNoData = blockNoData;
    }

    public static BuildingCostPolicy defaultPolicy() {
        Map<BuildingDensityClass, Double> nodeDefaults = new EnumMap<>(BuildingDensityClass.class);
        nodeDefaults.put(BuildingDensityClass.NONE, 0.0);
        nodeDefaults.put(BuildingDensityClass.SPARSE_HOMESTEAD_FRINGE, 0.25);
        nodeDefaults.put(BuildingDensityClass.LOW_DENSITY_RURAL_RIBBON, 0.70);
        nodeDefaults.put(BuildingDensityClass.MEDIUM_DENSITY_VILLAGE_CLUSTER, 1.55);
        nodeDefaults.put(BuildingDensityClass.HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP, 2.85);
        nodeDefaults.put(BuildingDensityClass.INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT, 4.40);
        nodeDefaults.put(BuildingDensityClass.NODATA, 0.0);

        Map<BuildingType, Double> structureDefaults = new EnumMap<>(BuildingType.class);
        for (BuildingType bt : BuildingType.values()) {
            structureDefaults.put(bt, bt.defaultDemolitionConflictKmEq());
        }

        Map<SettlementClassification, Double> severanceDefaults =
                new EnumMap<>(SettlementClassification.class);
        for (SettlementClassification sc : SettlementClassification.values()) {
            severanceDefaults.put(sc, sc.defaultSeverancePenaltyFactor());
        }

        return new BuildingCostPolicy(
                nodeDefaults,
                structureDefaults,
                severanceDefaults,
                30.0,
                60.0,
                0.35,
                false,
                false,
                false);
    }

    public double nodePenalty(BuildingDensityClass densityClass) {
        return nodePenalties.getOrDefault(densityClass, 0.0);
    }

    public double structureDemolitionCostKmEq(BuildingType type, int floors) {
        double base = structureDemolitionCostKmEq.getOrDefault(
                type, type.defaultDemolitionConflictKmEq());
        double floorFactor = 1.0 + 0.18 * Math.max(0, floors - 1);
        return base * floorFactor;
    }

    public double structureProximityCostKmEq(
            BuildingType type,
            int floors,
            double distanceFromRowEdgeMeters) {
        if (distanceFromRowEdgeMeters >= structureProximityBufferMeters) {
            return 0.0;
        }
        double ratio = 1.0 - (Math.max(0.0, distanceFromRowEdgeMeters) / structureProximityBufferMeters);
        return structureDemolitionCostKmEq(type, floors) * proximitySurchargeFactor * ratio * ratio;
    }

    public double settlementSeveranceMultiplier(
            SettlementClassification classification,
            double densityBuildingsPerHa) {
        double base = settlementSeverancePenalty.getOrDefault(
                classification, classification.defaultSeverancePenaltyFactor());
        double densityFactor = Math.min(2.2, 0.65 + (densityBuildingsPerHa / 22.0));
        return base * densityFactor;
    }

    public double rowEvaluationHalfWidthMeters() {
        return rowEvaluationHalfWidthMeters;
    }

    public double structureProximityBufferMeters() {
        return structureProximityBufferMeters;
    }

    public double proximitySurchargeFactor() {
        return proximitySurchargeFactor;
    }

    public boolean blockInstitutionalAndReligiousCompounds() {
        return blockInstitutionalAndReligiousCompounds;
    }

    public boolean blockHighDensityBuiltUp() {
        return blockHighDensityBuiltUp;
    }

    public boolean blockNoData() {
        return blockNoData;
    }

    public Map<BuildingDensityClass, Double> nodePenalties() {
        return Map.copyOf(nodePenalties);
    }

    public Map<BuildingType, Double> structureDemolitionCostKmEq() {
        return Map.copyOf(structureDemolitionCostKmEq);
    }

    public Map<SettlementClassification, Double> settlementSeverancePenalty() {
        return Map.copyOf(settlementSeverancePenalty);
    }
}
