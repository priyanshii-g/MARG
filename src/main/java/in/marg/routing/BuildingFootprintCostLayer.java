package in.marg.routing;

import in.marg.building.BuildingDensityClass;
import in.marg.building.BuildingDensityRasterProvider;
import in.marg.building.BuildingFootprintVectorIndex;
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
 *   <li>Node assessment: penalizes built-up structure density classes and institutional/religious
 *       compounds (or hard-blocks them when {@code blockInstitutionalAndReligiousCompounds=true}).</li>
 *   <li>Edge assessment: evaluates the 2D Right-of-Way (RoW) strip and construction setback buffer
 *       along each step segment {@code from -> to} against spatially indexed building footprints
 *       and settlement polygons, charging RFCTLARR 2013 R&R structure demolition costs and
 *       settlement boundary severance penalties.</li>
 * </ul>
 */
@Component
public class BuildingFootprintCostLayer implements CostLayer {

    private static final String SOURCE = "building:footprints-and-settlements";

    private final BuildingDensityRasterProvider rasterProvider;
    private final BuildingFootprintVectorIndex vectorIndex;
    private final CoordinateProjection projection;
    private final BuildingRoutingProperties properties;

    @Autowired
    public BuildingFootprintCostLayer(
            BuildingDensityRasterProvider rasterProvider,
            BuildingFootprintVectorIndex vectorIndex,
            CoordinateProjection projection,
            BuildingRoutingProperties properties) {
        this.rasterProvider = rasterProvider;
        this.vectorIndex = vectorIndex;
        this.projection = projection;
        this.properties = properties;
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        BuildingDensityClass densityClass = rasterProvider.sampleClass(point.lat(), point.lon());
        BuildingCostPolicy policy = properties.toPolicy();

        if (densityClass == BuildingDensityClass.NODATA && policy.blockNoData()) {
            return CostAssessment.blocked(SOURCE, "BUILDING_DENSITY_NODATA");
        }
        if (densityClass == BuildingDensityClass.INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT
                && policy.blockInstitutionalAndReligiousCompounds()) {
            return CostAssessment.blocked(
                    SOURCE, "INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT_BLOCKED");
        }
        if (densityClass == BuildingDensityClass.HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP
                && policy.blockHighDensityBuiltUp()) {
            return CostAssessment.blocked(
                    SOURCE, "HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP_BLOCKED");
        }

        double penalty = policy.nodePenalty(densityClass);
        return new CostAssessment(false, penalty, SOURCE, densityClass.name());
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
        BuildingCostPolicy policy = properties.toPolicy();

        double halfRowW = policy.rowEvaluationHalfWidthMeters();
        double proxBuf = policy.structureProximityBufferMeters();

        BuildingFootprintVectorIndex.StepConflictAssessment stepConflict =
                vectorIndex.evaluateStepConflictsMetric(a, b, halfRowW, proxBuf);

        double structureConflictCostKmEq = 0.0;
        String primaryConflictLabel = null;

        if (!stepConflict.buildingHits().isEmpty()) {
            for (BuildingFootprintVectorIndex.BuildingHit hit : stepConflict.buildingHits()) {
                var bld = hit.building();
                if (hit.insideDirectRow()) {
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
        if (stepConflict.intersectedSettlement() != null) {
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
                (structureConflictCostKmEq / stepKm) + settlementSeveranceMultiplier;
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
