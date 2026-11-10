package in.marg.routing;

import in.marg.config.DemographicRoutingProperties;
import in.marg.demographic.DemographicVectorIndex;
import in.marg.demographic.PopulationDensityClass;
import in.marg.demographic.PopulationDensityRasterProvider;
import in.marg.model.GeoPoint;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Spatial cost layer for Layer 12: Population Density, Village/Urban Census Population,
 * and Demographic Vulnerability Exposure:
 * <ul>
 *   <li>Node assessment: penalizes 30 m population density classes
 *       ({@code SPARSE_RURAL_DISPERSED} through {@code VERY_HIGH_URBAN_DENSITY_CORE})
 *       and supports hard-blocking of dense urban/population cores.</li>
 *   <li>Edge assessment: evaluates each step segment {@code from -> to} against spatially
 *       indexed demographic tracts across the Direct RoW, 250 m Environmental Buffer, and
 *       500 m Secondary Influence Buffer, scaling penalties by total resident population
 *       and social vulnerability (distinguishing an 8,000-resident settlement from a
 *       200-resident hamlet at identical boundary distance).</li>
 * </ul>
 */
@Component
public class DemographicCostLayer implements CostLayer {

    private static final String SOURCE = "demographic:population-and-vulnerability";

    private final PopulationDensityRasterProvider rasterProvider;
    private final DemographicVectorIndex vectorIndex;
    private final CoordinateProjection projection;
    private final DemographicRoutingProperties properties;

    @Autowired
    public DemographicCostLayer(
            PopulationDensityRasterProvider rasterProvider,
            DemographicVectorIndex vectorIndex,
            CoordinateProjection projection,
            DemographicRoutingProperties properties) {
        this.rasterProvider = rasterProvider;
        this.vectorIndex = vectorIndex;
        this.projection = projection;
        this.properties = properties;
    }

    @Override
    public CostAssessment assess(GeoPoint point) {
        PopulationDensityClass densityClass = rasterProvider.sampleClass(point.lat(), point.lon());
        DemographicCostPolicy policy = properties.toPolicy();

        if (densityClass == PopulationDensityClass.NODATA && policy.blockNoData()) {
            return CostAssessment.blocked(SOURCE, "POPULATION_DENSITY_NODATA");
        }
        if (densityClass == PopulationDensityClass.VERY_HIGH_URBAN_DENSITY_CORE
                && policy.blockVeryHighUrbanDensityCore()) {
            return CostAssessment.blocked(SOURCE, "VERY_HIGH_URBAN_DENSITY_CORE_BLOCKED");
        }
        if (densityClass == PopulationDensityClass.HIGH_DENSITY_POPULATION_CLUSTER
                && policy.blockHighDensityPopulationClusters()) {
            return CostAssessment.blocked(SOURCE, "HIGH_DENSITY_POPULATION_CLUSTER_BLOCKED");
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
        DemographicCostPolicy policy = properties.toPolicy();

        DemographicVectorIndex.StepDemographicExposure exposure =
                vectorIndex.evaluateStepDemographicExposureMetric(
                        a,
                        b,
                        policy.rowEvaluationHalfWidthMeters(),
                        policy.primaryDemographicBufferMeters(),
                        policy.secondaryDemographicBufferMeters());

        if (!exposure.hasExposure()) {
            return CostAssessment.FREE;
        }

        double exposureCostKmEq = policy.computeStepExposureCostKmEq(
                exposure.directRowWeightedPopUnits(),
                exposure.primaryBufferWeightedPopUnits(),
                exposure.secondaryBufferWeightedPopUnits());
        if (exposureCostKmEq <= 0.0) {
            return CostAssessment.FREE;
        }

        double multiplier = exposureCostKmEq;
        String label;
        if (exposure.directRowWeightedPopUnits() > 0.0) {
            label = "DIRECT_ROW_POPULATION_EXPOSURE_"
                    + exposure.dominantTract().settlementClassification().name();
        } else if (exposure.primaryBufferWeightedPopUnits() > 0.0) {
            label = "ENVIRONMENTAL_BUFFER_250M_POPULATION_EXPOSURE_"
                    + exposure.dominantTract().vulnerabilityTier().name();
        } else {
            label = "SECONDARY_BUFFER_500M_POPULATION_EXPOSURE";
        }
        return new CostAssessment(false, multiplier, SOURCE + ":buffer-exposure", label);
    }
}
