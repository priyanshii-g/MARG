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

    public static final double DEFAULT_INSTITUTIONAL_SAFETY_BUFFER_METERS = 75.0;
    public static final double DEFAULT_DENSE_COLONY_LAST_RESORT_PENALTY = 1000.0;

    private final Map<BuildingDensityClass, Double> nodePenalties;
    private final Map<BuildingType, Double> structureDemolitionCostKmEq;
    private final Map<SettlementClassification, Double> settlementSeverancePenalty;
    private final double rowEvaluationHalfWidthMeters;
    private final double structureProximityBufferMeters;
    private final double proximitySurchargeFactor;
    private final boolean blockInstitutionalAndReligiousCompounds;
    private final boolean blockHighDensityBuiltUp;
    private final boolean blockNoData;
    private final double institutionalSafetyBufferMeters;
    private final double denseColonyLastResortPenalty;

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
        this(
                nodePenalties,
                structureDemolitionCostKmEq,
                settlementSeverancePenalty,
                rowEvaluationHalfWidthMeters,
                structureProximityBufferMeters,
                proximitySurchargeFactor,
                blockInstitutionalAndReligiousCompounds,
                blockHighDensityBuiltUp,
                blockNoData,
                DEFAULT_INSTITUTIONAL_SAFETY_BUFFER_METERS,
                DEFAULT_DENSE_COLONY_LAST_RESORT_PENALTY);
    }

    public BuildingCostPolicy(
            Map<BuildingDensityClass, Double> nodePenalties,
            Map<BuildingType, Double> structureDemolitionCostKmEq,
            Map<SettlementClassification, Double> settlementSeverancePenalty,
            double rowEvaluationHalfWidthMeters,
            double structureProximityBufferMeters,
            double proximitySurchargeFactor,
            boolean blockInstitutionalAndReligiousCompounds,
            boolean blockHighDensityBuiltUp,
            boolean blockNoData,
            double institutionalSafetyBufferMeters,
            double denseColonyLastResortPenalty) {
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
        this.institutionalSafetyBufferMeters = Math.max(50.0, institutionalSafetyBufferMeters);
        this.denseColonyLastResortPenalty = Math.max(100.0, denseColonyLastResortPenalty);
    }

    public static BuildingCostPolicy defaultPolicy() {
        Map<BuildingDensityClass, Double> nodeDefaults = new EnumMap<>(BuildingDensityClass.class);
        nodeDefaults.put(BuildingDensityClass.NONE, 0.0);
        nodeDefaults.put(BuildingDensityClass.SPARSE_HOMESTEAD_FRINGE, 5.0);
        nodeDefaults.put(BuildingDensityClass.LOW_DENSITY_RURAL_RIBBON, 18.0);
        nodeDefaults.put(BuildingDensityClass.MEDIUM_DENSITY_VILLAGE_CLUSTER, 65.0);
        nodeDefaults.put(BuildingDensityClass.HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP, DEFAULT_DENSE_COLONY_LAST_RESORT_PENALTY);
        nodeDefaults.put(BuildingDensityClass.INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT, 2500.0);
        nodeDefaults.put(BuildingDensityClass.NODATA, 0.0);

        Map<BuildingType, Double> structureDefaults = new EnumMap<>(BuildingType.class);
        for (BuildingType bt : BuildingType.values()) {
            structureDefaults.put(bt, bt.defaultDemolitionConflictKmEq());
        }

        Map<SettlementClassification, Double> severanceDefaults =
                new EnumMap<>(SettlementClassification.class);
        severanceDefaults.put(SettlementClassification.RURAL, 25.0);
        severanceDefaults.put(SettlementClassification.URBAN, DEFAULT_DENSE_COLONY_LAST_RESORT_PENALTY);
        severanceDefaults.put(SettlementClassification.INDUSTRIAL, 150.0);

        return new BuildingCostPolicy(
                nodeDefaults,
                structureDefaults,
                severanceDefaults,
                30.0,
                60.0,
                0.35,
                true,
                true,
                false,
                DEFAULT_INSTITUTIONAL_SAFETY_BUFFER_METERS,
                DEFAULT_DENSE_COLONY_LAST_RESORT_PENALTY);
    }

    public BuildingCostPolicy withDenseColonyLastResortMode() {
        return new BuildingCostPolicy(
                nodePenalties,
                structureDemolitionCostKmEq,
                settlementSeverancePenalty,
                rowEvaluationHalfWidthMeters,
                structureProximityBufferMeters,
                proximitySurchargeFactor,
                true,
                false,
                blockNoData,
                institutionalSafetyBufferMeters,
                denseColonyLastResortPenalty);
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

    public double institutionalSafetyBufferMeters() {
        return institutionalSafetyBufferMeters;
    }

    public double denseColonyLastResortPenalty() {
        return denseColonyLastResortPenalty;
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
