package in.marg.routing;

import in.marg.building.BuildingDensityClass;
import in.marg.building.BuildingDensityRasterProvider;
import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.building.BuildingType;
import in.marg.config.BuildingRoutingProperties;
import in.marg.model.GeoPoint;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spatial cost layer for 2D Settlement Boundaries, Built-Up Density Concentration,
 * and Individual Building Footprints ({@code RESIDENTIAL}, {@code COMMERCIAL},
 * {@code INSTITUTIONAL}, {@code RELIGIOUS}, {@code INDUSTRIAL}, {@code VILLAGE_INFRASTRUCTURE}):
 * <ul>
 *   <li><b>Hard-Blocked Exclusions</b>: Hospitals, Medical Campuses, Schools/Universities,
 *       and Religious/Heritage Precincts (plus a 50 m&ndash;100 m safety buffer) are strictly
 *       hard-blocked on both nodes and edges so no mainline, feeder spur, or terminal connector
 *       can pass through them.</li>
 *   <li><b>Dense Urban / Residential Colonies</b>: Hard-blocked wherever greenfield bypass exists
 *       (when {@code blockHighDensityBuiltUp=true}), or penalized with an extreme last-resort
 *       barrier penalty ({@code 1000.0x}) via {@link #withDenseColonyLastResortMode()} when an
 *       alignment is completely surrounded by urban sprawl.</li>
 *   <li><b>Isolated / Sparse Buildings &amp; Ribbon Homesteads</b>: Carry heavy RFCTLARR 2013
 *       demolition &amp; R&amp;R penalties (50x&ndash;250x per structure hit in RoW) so A*
 *       naturally threads through open agricultural/barren gaps between houses whenever possible.</li>
 * </ul>
 */
@Component
public class BuildingFootprintCostLayer implements CostLayer {

    private static final String SOURCE = "building:footprints-and-settlements";
    private static final double ISOLATED_BUILDING_NODE_PENALTY = 75.0;

    private final BuildingDensityRasterProvider rasterProvider;
    private final BuildingFootprintVectorIndex vectorIndex;
    private final CoordinateProjection projection;
    private final BuildingRoutingProperties properties;
    private final BuildingCostPolicy overridePolicy;

    @Autowired
    public BuildingFootprintCostLayer(
            BuildingDensityRasterProvider rasterProvider,
            BuildingFootprintVectorIndex vectorIndex,
            CoordinateProjection projection,
            BuildingRoutingProperties properties) {
        this(rasterProvider, vectorIndex, projection, properties, null);
    }

    private BuildingFootprintCostLayer(
            BuildingDensityRasterProvider rasterProvider,
            BuildingFootprintVectorIndex vectorIndex,
            CoordinateProjection projection,
            BuildingRoutingProperties properties,
            BuildingCostPolicy overridePolicy) {
        this.rasterProvider = rasterProvider;
        this.vectorIndex = vectorIndex;
        this.projection = projection;
        this.properties = properties;
        this.overridePolicy = overridePolicy;
    }

    /**
     * Returns a last-resort routing copy where dense urban colonies transition from Hard-Block to
     * an extreme 1000.0x barrier penalty (similar to 10 km ESZ buffers), while Hospitals, Medical
     * Campuses, Institutional/Educational Campuses, and Sacred/Religious Precincts remain 100% Hard-Blocked.
     */
    public BuildingFootprintCostLayer withDenseColonyLastResortMode() {
        return new BuildingFootprintCostLayer(
                rasterProvider,
                vectorIndex,
                projection,
                properties,
                effectivePolicy().withDenseColonyLastResortMode());
    }

    private BuildingCostPolicy effectivePolicy() {
        return overridePolicy != null ? overridePolicy : properties.toPolicy();
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        BuildingCostPolicy policy = effectivePolicy();

        // 1. Zero-Tolerance Hard Exclusion: Sacred/Heritage Precincts
        if (vectorIndex.findContainingSacredExclusion(point) != null) {
            return CostAssessment.blocked(SOURCE, "SACRED_RELIGIOUS_PRECINCT_EXCLUSION");
        }

        // 2. Hard Exclusion: Hospitals, Medical Campuses, Schools/Universities, Religious Compounds (+ safety buffer)
        if (policy.blockInstitutionalAndReligiousCompounds()) {
            BuildingFootprintVectorIndex.ExclusionHit instHit =
                    vectorIndex.findContainingInstitutionalOrSacredExclusion(
                            point, policy.institutionalSafetyBufferMeters());
            if (instHit != null) {
                return CostAssessment.blocked(
                        SOURCE, instHit.category() + "_EXCLUSION:" + instHit.name());
            }
        }

        // 3. Dense Urban / Residential Colonies (Hard-Blocked or 1000.0x last-resort barrier)
        BuildingFootprintVectorIndex.SettlementBoundaryPolygon denseColony =
                vectorIndex.findContainingDenseColony(point);
        if (denseColony != null) {
            if (policy.blockHighDensityBuiltUp()) {
                return CostAssessment.blocked(
                        SOURCE, "DENSE_RESIDENTIAL_COLONY_EXCLUSION:" + denseColony.name());
            }
            return new CostAssessment(
                    false,
                    policy.denseColonyLastResortPenalty(),
                    SOURCE + ":colony-barrier",
                    "DENSE_RESIDENTIAL_COLONY_LAST_RESORT:" + denseColony.name());
        }

        BuildingDensityClass densityClass = rasterProvider.sampleClass(point.lat(), point.lon());

        if (densityClass == BuildingDensityClass.NODATA && policy.blockNoData()) {
            return CostAssessment.blocked(SOURCE, "BUILDING_DENSITY_NODATA");
        }
        if (densityClass == BuildingDensityClass.INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT
                && policy.blockInstitutionalAndReligiousCompounds()) {
            return CostAssessment.blocked(
                    SOURCE, "INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT_BLOCKED");
        }
        if (densityClass == BuildingDensityClass.HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP) {
            if (policy.blockHighDensityBuiltUp()) {
                return CostAssessment.blocked(
                        SOURCE, "HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP_BLOCKED");
            }
            return new CostAssessment(
                    false,
                    policy.denseColonyLastResortPenalty(),
                    SOURCE + ":urban-barrier",
                    "HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP_LAST_RESORT");
        }

        double penalty = policy.nodePenalty(densityClass);
        String label = densityClass.name();

        // 4. Isolated / Sparse Building Footprint direct hit penalty
        BuildingFootprintVectorIndex.BuildingFootprint directBld =
                vectorIndex.findContainingOrNearbyBuilding(point, 15.0);
        if (directBld != null) {
            if (policy.blockInstitutionalAndReligiousCompounds()
                    && (directBld.buildingType() == BuildingType.INSTITUTIONAL
                    || directBld.buildingType() == BuildingType.RELIGIOUS)) {
                return CostAssessment.blocked(
                        SOURCE, "INSTITUTIONAL_BUILDING_BLOCKED:" + directBld.name());
            }
            penalty += ISOLATED_BUILDING_NODE_PENALTY;
            label = "ISOLATED_BUILDING_FOOTPRINT_" + directBld.buildingType().name();
        }

        return new CostAssessment(false, penalty, SOURCE, label);
    }

    @Override
    public boolean hasEdgeCost() {
        return true;
    }

    @Override
    public CostAssessment assessEdge(GeoPoint from, GeoPoint to) {
        MetricPoint a = projection.forward(from);
        MetricPoint b = projection.forward(to);
        double stepMeters = Math.hypot(b.x() - a.x(), b.y() - a.y());
        if (stepMeters <= 1e-6) {
            return CostAssessment.FREE;
        }
        double stepKm = Math.max(0.05, stepMeters / 1000.0);
        BuildingCostPolicy policy = effectivePolicy();

        double halfRowW = policy.rowEvaluationHalfWidthMeters();
        double proxBuf = policy.structureProximityBufferMeters();

        // 1. Sacred / Heritage Precinct Edge Check
        if (vectorIndex.findIntersectingSacredExclusion(from, to, halfRowW) != null) {
            return CostAssessment.blocked(
                    SOURCE, "SACRED_RELIGIOUS_PRECINCT_EXCLUSION");
        }

        // 2. Hard Exclusion Edge Check: Hospitals, Medical/Educational/Religious Campuses (+ 50-100m buffer)
        if (policy.blockInstitutionalAndReligiousCompounds()) {
            BuildingFootprintVectorIndex.ExclusionHit instHit =
                    vectorIndex.findIntersectingInstitutionalOrSacredExclusion(
                            from, to, halfRowW + policy.institutionalSafetyBufferMeters());
            if (instHit != null) {
                return CostAssessment.blocked(
                        SOURCE, instHit.category() + "_EDGE_BLOCKED:" + instHit.name());
            }
        }

        // 3. Dense Urban / Residential Colonies Edge Check (Hard-Block or 1000.0x last-resort barrier)
        double colonyBarrierMultiplier = 0.0;
        String primaryConflictLabel = null;
        BuildingFootprintVectorIndex.SettlementBoundaryPolygon denseColony =
                vectorIndex.findIntersectingDenseColony(from, to, halfRowW);
        if (denseColony != null) {
            if (policy.blockHighDensityBuiltUp()) {
                return CostAssessment.blocked(
                        SOURCE, "DENSE_RESIDENTIAL_COLONY_EDGE_BLOCKED:" + denseColony.name());
            }
            colonyBarrierMultiplier = policy.denseColonyLastResortPenalty();
            primaryConflictLabel = "DENSE_RESIDENTIAL_COLONY_LAST_RESORT:" + denseColony.name();
        }

        // 4. Isolated / Sparse Building Footprints & Fringe Settlements (50x - 250x demolition & R&R penalty)
        BuildingFootprintVectorIndex.StepConflictAssessment stepConflict =
                vectorIndex.evaluateStepConflictsMetric(a, b, halfRowW, proxBuf);

        double structureConflictCostKmEq = 0.0;

        if (!stepConflict.buildingHits().isEmpty()) {
            for (BuildingFootprintVectorIndex.BuildingHit hit : stepConflict.buildingHits()) {
                var bld = hit.building();
                if (hit.insideDirectRow()) {
                    if (policy.blockInstitutionalAndReligiousCompounds()
                            && (bld.buildingType() == BuildingType.INSTITUTIONAL
                            || bld.buildingType() == BuildingType.RELIGIOUS)) {
                        return CostAssessment.blocked(
                                SOURCE, "ROW_INSTITUTIONAL_OR_RELIGIOUS_BLOCKED:" + bld.name());
                    }
                    structureConflictCostKmEq +=
                            policy.structureDemolitionCostKmEq(bld.buildingType(), bld.floors());
                    if (primaryConflictLabel == null
                            || bld.buildingType().isHighSocialSensitivity()) {
                        primaryConflictLabel = "ROW_BUILDING_ACQUISITION_" + bld.buildingType().name();
                    }
                } else {
                    double distFromRowEdge = Math.max(0.0, hit.distanceToCenterlineMeters() - halfRowW);
                    structureConflictCostKmEq +=
                            policy.structureProximityCostKmEq(
                                    bld.buildingType(), bld.floors(), distFromRowEdge);
                    if (primaryConflictLabel == null) {
                        primaryConflictLabel = "CONSTRUCTION_BUFFER_STRUCTURE_PROXIMITY";
                    }
                }
            }
        }

        double settlementSeveranceMultiplier = 0.0;
        if (stepConflict.intersectedSettlement() != null && colonyBarrierMultiplier <= 0.0) {
            var stl = stepConflict.intersectedSettlement();
            settlementSeveranceMultiplier =
                    policy.settlementSeveranceMultiplier(
                            stl.settlementClassification(),
                            stl.builtUpDensityBuildingsPerHa());
            if (primaryConflictLabel == null) {
                primaryConflictLabel =
                        "SETTLEMENT_BOUNDARY_SEVERANCE_" + stl.settlementClassification().name();
            }
        }

        double totalMultiplier =
                (structureConflictCostKmEq / stepKm)
                        + settlementSeveranceMultiplier
                        + colonyBarrierMultiplier;
        if (totalMultiplier <= 0.0) {
            return CostAssessment.FREE;
        }
        return new CostAssessment(
                false,
                totalMultiplier,
                SOURCE + ":row-conflict",
                primaryConflictLabel != null ? primaryConflictLabel : "BUILDING_ROW_CONFLICT");
    }
}
