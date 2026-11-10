package in.marg.api;

import in.marg.config.BuildingRoutingProperties;
import in.marg.config.DemographicRoutingProperties;
import in.marg.config.FloodRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.SettlementRoutingProperties;
import in.marg.config.TransportRoutingProperties;
import in.marg.config.WaterBodyRoutingProperties;
import in.marg.config.WildlifeRoutingProperties;
import in.marg.config.GeotechRoutingProperties;
import in.marg.config.UtilityRoutingProperties;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.routing.CompositeCostSurfaceFactory;
import in.marg.routing.CompositeRouteMetricsService;
import in.marg.routing.DistanceOnlyCostSurface;
import in.marg.routing.GreenfieldAStarRouter;
import in.marg.routing.RouteConstraints;
import in.marg.spatial.CoordinateProjection;
import in.marg.transport.FacilityMode;
import in.marg.transport.HighwayNetworkPlannerService;
import in.marg.transport.HubEvaluationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/routes/composite")
public class CompositeRoutingController {

    private static final double ROUTING_RESOLUTION_METERS = 250.0;

    private final StudyRegion region;
    private final GreenfieldAStarRouter router;
    private final CoordinateProjection projection;
    private final CompositeCostSurfaceFactory factory;
    private final CompositeRouteMetricsService routeMetrics;
    private final HubEvaluationService hubEvaluationService;
    private final HighwayNetworkPlannerService networkPlannerService;

    private final LulcRoutingProperties lulcProperties;
    private final FloodRoutingProperties floodProperties;
    private final SettlementRoutingProperties settlementProperties;
    private final TransportRoutingProperties transportProperties;
    private final WaterBodyRoutingProperties waterBodyProperties;
    private final WildlifeRoutingProperties wildlifeProperties;
    private final GeotechRoutingProperties geotechProperties;
    private final UtilityRoutingProperties utilityProperties;
    private final BuildingRoutingProperties buildingProperties;
    private final DemographicRoutingProperties demographicProperties;

    private final double defaultCorridorBufferDeg;
    private final int defaultMaxLulcSamples;

    public CompositeRoutingController(
            StudyRegion region,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            CompositeCostSurfaceFactory factory,
            CompositeRouteMetricsService routeMetrics,
            HubEvaluationService hubEvaluationService,
            HighwayNetworkPlannerService networkPlannerService,
            LulcRoutingProperties lulcProperties,
            FloodRoutingProperties floodProperties,
            SettlementRoutingProperties settlementProperties,
            TransportRoutingProperties transportProperties,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRoutingProperties wildlifeProperties,
            GeotechRoutingProperties geotechProperties,
            @Value("${marg.routing.corridor-buffer-deg:0.15}")
            double defaultCorridorBufferDeg,
            @Value("${marg.bhuvan.lulc.max-remote-samples:4000}")
            int defaultMaxLulcSamples) {
        this(
                region,
                router,
                projection,
                factory,
                routeMetrics,
                hubEvaluationService,
                networkPlannerService,
                lulcProperties,
                floodProperties,
                settlementProperties,
                transportProperties,
                waterBodyProperties,
                wildlifeProperties,
                geotechProperties,
                new UtilityRoutingProperties(),
                new BuildingRoutingProperties(),
                new DemographicRoutingProperties(),
                defaultCorridorBufferDeg,
                defaultMaxLulcSamples);
    }

    public CompositeRoutingController(
            StudyRegion region,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            CompositeCostSurfaceFactory factory,
            CompositeRouteMetricsService routeMetrics,
            HubEvaluationService hubEvaluationService,
            HighwayNetworkPlannerService networkPlannerService,
            LulcRoutingProperties lulcProperties,
            FloodRoutingProperties floodProperties,
            SettlementRoutingProperties settlementProperties,
            TransportRoutingProperties transportProperties,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRoutingProperties wildlifeProperties,
            GeotechRoutingProperties geotechProperties,
            UtilityRoutingProperties utilityProperties,
            @Value("${marg.routing.corridor-buffer-deg:0.15}")
            double defaultCorridorBufferDeg,
            @Value("${marg.bhuvan.lulc.max-remote-samples:4000}")
            int defaultMaxLulcSamples) {
        this(
                region,
                router,
                projection,
                factory,
                routeMetrics,
                hubEvaluationService,
                networkPlannerService,
                lulcProperties,
                floodProperties,
                settlementProperties,
                transportProperties,
                waterBodyProperties,
                wildlifeProperties,
                geotechProperties,
                utilityProperties,
                new BuildingRoutingProperties(),
                new DemographicRoutingProperties(),
                defaultCorridorBufferDeg,
                defaultMaxLulcSamples);
    }

    public CompositeRoutingController(
            StudyRegion region,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            CompositeCostSurfaceFactory factory,
            CompositeRouteMetricsService routeMetrics,
            HubEvaluationService hubEvaluationService,
            HighwayNetworkPlannerService networkPlannerService,
            LulcRoutingProperties lulcProperties,
            FloodRoutingProperties floodProperties,
            SettlementRoutingProperties settlementProperties,
            TransportRoutingProperties transportProperties,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRoutingProperties wildlifeProperties,
            GeotechRoutingProperties geotechProperties,
            UtilityRoutingProperties utilityProperties,
            BuildingRoutingProperties buildingProperties,
            @Value("${marg.routing.corridor-buffer-deg:0.15}")
            double defaultCorridorBufferDeg,
            @Value("${marg.bhuvan.lulc.max-remote-samples:4000}")
            int defaultMaxLulcSamples) {
        this(
                region,
                router,
                projection,
                factory,
                routeMetrics,
                hubEvaluationService,
                networkPlannerService,
                lulcProperties,
                floodProperties,
                settlementProperties,
                transportProperties,
                waterBodyProperties,
                wildlifeProperties,
                geotechProperties,
                utilityProperties,
                buildingProperties,
                new DemographicRoutingProperties(),
                defaultCorridorBufferDeg,
                defaultMaxLulcSamples);
    }

    @Autowired
    public CompositeRoutingController(
            StudyRegion region,
            GreenfieldAStarRouter router,
            CoordinateProjection projection,
            CompositeCostSurfaceFactory factory,
            CompositeRouteMetricsService routeMetrics,
            HubEvaluationService hubEvaluationService,
            HighwayNetworkPlannerService networkPlannerService,
            LulcRoutingProperties lulcProperties,
            FloodRoutingProperties floodProperties,
            SettlementRoutingProperties settlementProperties,
            TransportRoutingProperties transportProperties,
            WaterBodyRoutingProperties waterBodyProperties,
            WildlifeRoutingProperties wildlifeProperties,
            GeotechRoutingProperties geotechProperties,
            UtilityRoutingProperties utilityProperties,
            BuildingRoutingProperties buildingProperties,
            DemographicRoutingProperties demographicProperties,
            @Value("${marg.routing.corridor-buffer-deg:0.15}")
            double defaultCorridorBufferDeg,
            @Value("${marg.bhuvan.lulc.max-remote-samples:4000}")
            int defaultMaxLulcSamples) {

        this.region = region;
        this.router = router;
        this.projection = projection;
        this.factory = factory;
        this.routeMetrics = routeMetrics;
        this.hubEvaluationService = hubEvaluationService;
        this.networkPlannerService = networkPlannerService;
        this.lulcProperties = lulcProperties;
        this.floodProperties = floodProperties;
        this.settlementProperties = settlementProperties;
        this.transportProperties = transportProperties;
        this.waterBodyProperties = waterBodyProperties;
        this.wildlifeProperties = wildlifeProperties;
        this.geotechProperties = geotechProperties;
        this.utilityProperties = utilityProperties;
        this.buildingProperties = buildingProperties != null
                ? buildingProperties
                : new BuildingRoutingProperties();
        this.demographicProperties = demographicProperties != null
                ? demographicProperties
                : new DemographicRoutingProperties();
        this.defaultCorridorBufferDeg = defaultCorridorBufferDeg;
        this.defaultMaxLulcSamples = defaultMaxLulcSamples;
    }

    @GetMapping
    public Map<String, Object> route(
            @RequestParam(required = false) Double startLat,
            @RequestParam(required = false) Double startLon,
            @RequestParam(required = false) Double endLat,
            @RequestParam(required = false) Double endLon,
            @RequestParam(required = false) Double resolutionMeters,
            @RequestParam(required = false) Double corridorBufferDeg,
            @RequestParam(required = false) Integer maxLulcSamples,
            @RequestParam(required = false) Double maxDeviationPercent,
            @RequestParam(required = false) String facilityMode,
            @RequestParam(required = false) String targetHubs,
            @RequestParam(required = false) Integer minHubPopulation,
            @RequestParam(required = false) Double maxVcRatio) {

        GeoPoint start =
                startLat == null
                        ? region.origin()
                        : new GeoPoint(
                                startLat,
                                require("startLon", startLon));

        GeoPoint goal =
                endLat == null
                        ? region.destination()
                        : new GeoPoint(
                                endLat,
                                require("endLon", endLon));

        double resolution =
                resolutionMeters == null
                        ? ROUTING_RESOLUTION_METERS
                        : resolutionMeters;

        if (Math.abs(resolution - ROUTING_RESOLUTION_METERS) > 1e-9) {
            throw new IllegalArgumentException(
                    "Composite routing is fixed at the canonical 250 m grid.");
        }

        validateEndpoints(start, goal);

        FacilityMode mode =
                FacilityMode.fromParam(
                        facilityMode,
                        transportProperties.getDefaultFacilityMode());

        if (maxVcRatio != null
                && (!Double.isFinite(maxVcRatio) || maxVcRatio <= 0.1 || maxVcRatio > 2.0)) {
            throw new IllegalArgumentException("maxVcRatio must be between 0.1 and 2.0");
        }

        List<String> targetHubList =
                targetHubs == null || targetHubs.isBlank()
                        ? List.of()
                        : Arrays.stream(targetHubs.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList();

        double buffer =
                corridorBufferDeg == null
                        ? defaultCorridorBufferDeg
                        : corridorBufferDeg;

        StudyRegion searchWindow =
                corridorWindow(region, start, goal, buffer);

        int maxSamples =
                maxLulcSamples == null
                        ? defaultMaxLulcSamples
                        : maxLulcSamples;

        if (maxSamples < 1 || maxSamples > 10000) {
            throw new IllegalArgumentException(
                    "maxLulcSamples must be between 1 and 10000");
        }

        // Stage 1: Three-Tier Hub Evaluation Pipeline (Immutable Overrides -> HVI -> Spacing & V/C Gates)
        HubEvaluationService.EvaluationResult hubEvaluation =
                hubEvaluationService.evaluate(
                        start,
                        goal,
                        searchWindow,
                        mode,
                        targetHubList,
                        minHubPopulation,
                        maxVcRatio);

        // Stage 2: Build 6-Layer Mode-Aware Cost Surface
        CompositeCostSurfaceFactory.BuildResult built =
                factory.build(
                        searchWindow,
                        maxSamples,
                        mode,
                        hubEvaluation.approvedHubs(),
                        start,
                        goal);

        // Baseline is computed with the exact same search window and 250 m grid.
        List<GeoPoint> baseline =
                router.route(
                        region,
                        start,
                        goal,
                        new DistanceOnlyCostSurface(),
                        ROUTING_RESOLUTION_METERS,
                        searchWindow);

        double baselineLengthKm = router.routeLengthKm(baseline);

        RouteConstraints constraints = RouteConstraints.none();
        if (maxDeviationPercent != null) {
            if (!Double.isFinite(maxDeviationPercent)
                    || maxDeviationPercent < 0.0
                    || maxDeviationPercent > 200.0) {
                throw new IllegalArgumentException(
                        "maxDeviationPercent must be between 0 and 200");
            }
            double maxLengthKm = baselineLengthKm * (1.0 + maxDeviationPercent / 100.0);
            constraints = RouteConstraints.maxLengthKm(maxLengthKm);
        }

        // Stage 3: Route Greenfield Mainline
        List<GeoPoint> mainline =
                router.route(
                        region,
                        start,
                        goal,
                        built.surface(),
                        ROUTING_RESOLUTION_METERS,
                        searchWindow,
                        constraints);

        double routeLengthKm = router.routeLengthKm(mainline);
        double deviationPercent =
                baselineLengthKm > 0.0
                        ? ((routeLengthKm / baselineLengthKm) - 1.0) * 100.0
                        : Double.NaN;

        // Stage 4: Interchange Placement, Dedicated A* Feeder/Spur Roads & ROB/Flyover Structures
        HighwayNetworkPlannerService.NetworkPlanResult networkPlan =
                networkPlannerService.planNetwork(
                        region,
                        mainline,
                        hubEvaluation,
                        built.terrainAndSocialLayers());

        Map<String, Object> routeDiagnostics = routeMetrics.summarize(mainline, mode);

        var flood = built.floodPreload().mosaic();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("routeDiagnostics", routeDiagnostics);
        metadata.put("facilityMode", mode.name());
        metadata.put("accessibilityPullApplied", built.accessibilityPullApplied());
        metadata.put("routingResolutionMeters", ROUTING_RESOLUTION_METERS);
        metadata.put("coordinateReferenceSystem", projection.crs());
        metadata.put("routingDistanceUnit", "metres internally; kilometres in metrics");
        metadata.put("searchWindow", searchWindow);
        metadata.put("greenfield", true);
        metadata.put(
                "layers",
                List.of(
                        "LULC",
                        "FLOOD_HAZARD",
                        "SLOPE",
                        "PROTECTED_AREA",
                        "SETTLEMENT_EXPOSURE",
                        "EXISTING_TRANSPORT_NETWORK",
                        "HYDROLOGY_WATER_BODIES",
                        "WILDLIFE_CORRIDORS_AND_ESZ",
                        "GEOTECH_SOIL_AND_LANDSLIDE",
                        "HIGH_VOLTAGE_UTILITY_CORRIDORS",
                        "SETTLEMENT_AND_BUILDING_FOOTPRINTS",
                        "POPULATION_DENSITY_AND_DEMOGRAPHICS"));

        metadata.put(
                "compositeCost",
                "stepKm * (1 + LULC_penalty + Flood_penalty + Slope_penalty "
                        + "+ Settlement_penalty + TransportNode_penalty + HydrologyNode_penalty "
                        + "+ WildlifeEszNode_penalty + GeotechSubgradeLandslide_penalty "
                        + "+ UtilityRoWNode_penalty + BuildingDensityNode_penalty + PopulationDensityNode_penalty) "
                        + "+ GradeSeparatedCrossingSkewCostKmEq + RiverBridgeHydraulicSkewCostKmEq "
                        + "+ WildlifeElevatedPassageSkewCostKmEq + GeotechFaultScarpMitigationSkewCostKmEq "
                        + "+ UtilityOverheadCrossingProtectionSkewCostKmEq "
                        + "+ RowBuildingAcquisitionAndSettlementSeveranceCostKmEq "
                        + "+ DemographicExposureAndSocialVulnerabilityCostKmEq");

        metadata.put(
                "protectedAreaRule",
                "PROTECTED_AREA and PROTECTED_AREA_NODATA are hard constraints");

        metadata.put(
                "hubEvaluationPipeline",
                Map.of(
                        "facilityMode", mode.name(),
                        "minHubPopulationApplied", hubEvaluation.minHubPopulationApplied(),
                        "minInterchangeSpacingKmApplied", hubEvaluation.minInterchangeSpacingKmApplied(),
                        "maxVcRatioCapApplied", hubEvaluation.maxVcRatioCapApplied(),
                        "approvedHubIds", hubEvaluation.approvedHubs().stream()
                                .map(h -> h.hubId() + " (" + h.name() + ")")
                                .toList(),
                        "hubDecisions", hubEvaluation.decisions().stream()
                                .map(HubEvaluationService.HubDecision::toMap)
                                .toList()));

        Map<String, Object> networkAndCapacitySummary = new LinkedHashMap<>();
        networkAndCapacitySummary.put("mainlineLengthKm", networkPlan.mainlineLengthKm());
        networkAndCapacitySummary.put("totalDedicatedFeederSpurLengthKm", networkPlan.totalFeederSpurLengthKm());
        networkAndCapacitySummary.put("totalConstructedHighwayAndSpurKm", networkPlan.totalConstructedHighwayAndSpurKm());
        networkAndCapacitySummary.put("plannedInterchangesCount", networkPlan.interchanges().size());
        networkAndCapacitySummary.put("dedicatedFeederSpursCount", networkPlan.feederSpurs().size());
        networkAndCapacitySummary.put("railwayRobCount", networkPlan.railwayRobCount());
        networkAndCapacitySummary.put("highwayFlyoverUnderpassCount", networkPlan.roadFlyoverCount());
        networkAndCapacitySummary.put("averageCrossingAngleDegrees", networkPlan.averageCrossingAngleDegrees());
        networkAndCapacitySummary.put("riverBridgesCount", networkPlan.riverBridges().size());
        networkAndCapacitySummary.put(
                "totalEstimatedRiverBridgeSpanMeters",
                networkPlan.totalEstimatedRiverBridgeSpanMeters());
        networkAndCapacitySummary.put(
                "averageRiverCrossingAngleDegrees",
                networkPlan.averageRiverCrossingAngleDegrees());
        networkAndCapacitySummary.put("wildlifePassagesCount", networkPlan.wildlifePassages().size());
        networkAndCapacitySummary.put(
                "totalRecommendedWildlifePassageSpanMeters",
                networkPlan.totalRecommendedWildlifePassageSpanMeters());
        networkAndCapacitySummary.put(
                "averageWildlifeCrossingAngleDegrees",
                networkPlan.averageWildlifeCrossingAngleDegrees());
        networkAndCapacitySummary.put(
                "geotechMitigationZonesCount",
                networkPlan.geotechMitigations().size());
        networkAndCapacitySummary.put(
                "totalRecommendedGeotechStabilizationMeters",
                networkPlan.totalRecommendedGeotechStabilizationMeters());
        networkAndCapacitySummary.put(
                "averageGeotechCrossingAngleDegrees",
                networkPlan.averageGeotechCrossingAngleDegrees());
        networkAndCapacitySummary.put(
                "utilityCrossingsCount",
                networkPlan.utilityCrossings().size());
        networkAndCapacitySummary.put(
                "totalRecommendedUtilityProtectiveSpanMeters",
                networkPlan.totalRecommendedUtilityProtectiveSpanMeters());
        networkAndCapacitySummary.put(
                "averageUtilityCrossingAngleDegrees",
                networkPlan.averageUtilityCrossingAngleDegrees());
        networkAndCapacitySummary.put(
                "settlementConflictZonesCount",
                networkPlan.settlementConflictZones().size());
        networkAndCapacitySummary.put(
                "buildingsInDirectRowCount",
                networkPlan.buildingsInDirectRowCount());
        networkAndCapacitySummary.put(
                "settlementsIntersectingDirectRowCount",
                networkPlan.settlementsIntersectingDirectRowCount());
        networkAndCapacitySummary.put(
                "demographicExposureZonesCount",
                networkPlan.demographicExposureZones().size());
        networkAndCapacitySummary.put(
                "populationWithinDirectRow",
                networkPlan.populationWithinDirectRow());
        networkAndCapacitySummary.put(
                "estimatedDirectlyDisplacedPopulation",
                networkPlan.estimatedDirectlyDisplacedPopulation());
        networkAndCapacitySummary.put(
                "populationWithin250mEnvironmentalBuffer",
                networkPlan.populationWithin250mEnvironmentalBuffer());
        networkAndCapacitySummary.put(
                "populationWithin500mSecondaryBuffer",
                networkPlan.populationWithin500mSecondaryBuffer());
        if (networkPlan.rowCorridorFootprint() != null) {
            var rowFp = networkPlan.rowCorridorFootprint();
            networkAndCapacitySummary.put(
                    "rightOfWayAcquisitionFootprint",
                    Map.of(
                            "ircStandard", rowFp.ircStandard(),
                            "mainlineRowWidthMeters", rowFp.mainlineRowWidthMeters(),
                            "feederSpurRowWidthMeters", rowFp.feederSpurRowWidthMeters(),
                            "interchangeFootprintRadiusMeters", rowFp.interchangeRadiusMeters(),
                            "mainlineRowAreaHectares", rowFp.mainlineRowAreaHectares(),
                            "feederSpursRowAreaHectares", rowFp.feederSpursRowAreaHectares(),
                            "interchangeLoopsAdditionalHectares", rowFp.interchangeLoopsAdditionalHectares(),
                            "totalDirectRowAcquisitionHectares", rowFp.totalDirectRowAcquisitionHectares(),
                            "constructionZoneGrossHectares", rowFp.constructionZoneGrossHectares(),
                            "environmentalBufferGrossHectares", rowFp.environmentalBufferGrossHectares()));
        }
        networkAndCapacitySummary.put("totalCatchmentPopulationServed", networkPlan.totalCatchmentPopulationServed());
        networkAndCapacitySummary.put(
                "trafficCapacityAnalysis",
                Map.of(
                        "baseThroughTrafficPcuHr", hubEvaluation.baseThroughTrafficPcuHr(),
                        "projectedTotalPeakTrafficPcuHr", networkPlan.projectedTrafficVolumePcuHr(),
                        "designCapacityPcuHr", networkPlan.designCapacityPcuHr(),
                        "projectedVcRatio", networkPlan.projectedVcRatio(),
                        "levelOfService", networkPlan.levelOfService(),
                        "overCapacityWarning", networkPlan.overCapacityWarning()));
        metadata.put("transportNetworkAndCapacitySummary", networkAndCapacitySummary);

        metadata.put(
                "transportPolicy",
                Map.of(
                        "nodePenalties", transportProperties.toPolicy().nodePenalties(),
                        "crossingBaseCostKmEqAt90Deg", transportProperties.toPolicy().crossingBaseCostKmEq(),
                        "skewAnglePenaltyFactor", transportProperties.getSkewAnglePenaltyFactor(),
                        "parallelAcutePenalty", transportProperties.getParallelAcutePenalty(),
                        "rasterPath", "data/processed/transport/assam_transport_network_utm46.tif",
                        "networkGeoJsonPath", "data/processed/transport/assam_transport_network.geojson",
                        "regionalHubsGeoJsonPath", "data/processed/transport/assam_regional_hubs.geojson"));

        metadata.put(
                "hydrologyPolicy",
                Map.of(
                        "nodePenalties", waterBodyProperties.toPolicy().nodePenalties(),
                        "bridgeBaseCostKmEqAt90Deg", waterBodyProperties.toPolicy().bridgeBaseCostKmEq(),
                        "hydraulicSkewPenaltyFactor", waterBodyProperties.getHydraulicSkewPenaltyFactor(),
                        "parallelBankPenalty", waterBodyProperties.getParallelBankPenalty(),
                        "blockWetlands", waterBodyProperties.isBlockWetlands(),
                        "rasterPath", "data/processed/hydrology/assam_water_bodies_utm46.tif",
                        "perennialRiversGeoJsonPath", "data/processed/hydrology/assam_perennial_rivers.geojson",
                        "wetlandsGeoJsonPath", "data/processed/hydrology/assam_wetlands_catalog.geojson"));

        metadata.put(
                "wildlifePolicy",
                Map.of(
                        "nodePenalties", wildlifeProperties.toPolicy().nodePenalties(),
                        "passageBaseCostKmEqAt90Deg", wildlifeProperties.toPolicy().passageBaseCostKmEq(),
                        "barrierSkewPenaltyFactor", wildlifeProperties.getBarrierSkewPenaltyFactor(),
                        "parallelCorridorPenalty", wildlifeProperties.getParallelCorridorPenalty(),
                        "blockCriticalCorridors", wildlifeProperties.isBlockCriticalCorridors(),
                        "rasterPath", "data/processed/wildlife/assam_wildlife_esz_corridors_utm46.tif",
                        "wildlifeCorridorsGeoJsonPath", "data/processed/wildlife/assam_wildlife_corridors.geojson"));

        metadata.put(
                "geotechPolicy",
                Map.of(
                        "nodePenalties", geotechProperties.toPolicy().nodePenalties(),
                        "mitigationBaseCostKmEqAt90Deg", geotechProperties.toPolicy().mitigationBaseCostKmEq(),
                        "scarpSkewPenaltyFactor", geotechProperties.getScarpSkewPenaltyFactor(),
                        "parallelScarpUndercutPenalty", geotechProperties.getParallelScarpUndercutPenalty(),
                        "blockActiveLandslideZones", geotechProperties.isBlockActiveLandslideZones(),
                        "rasterPath", "data/processed/geotech/assam_geotech_landslide_utm46.tif",
                        "faultLandslideZonesGeoJsonPath", "data/processed/geotech/assam_fault_landslide_zones.geojson"));

        metadata.put(
                "utilityPolicy",
                Map.of(
                        "nodePenalties", utilityProperties.toPolicy().nodePenalties(),
                        "crossingProtectionBaseCostKmEqAt90Deg", utilityProperties.toPolicy().crossingProtectionBaseCostKmEq(),
                        "crossingSkewPenaltyFactor", utilityProperties.getCrossingSkewPenaltyFactor(),
                        "parallelUtilityRowPenalty", utilityProperties.getParallelUtilityRowPenalty(),
                        "blockSubstationsAndTowers", utilityProperties.isBlockSubstationsAndTowers(),
                        "rasterPath", "data/processed/utilities/assam_utility_corridors_utm46.tif",
                        "powerTransmissionLinesGeoJsonPath", "data/processed/utilities/assam_power_transmission_lines.geojson",
                        "utilityAssetsGeoJsonPath", "data/processed/utilities/assam_utility_assets.geojson"));

        metadata.put(
                "buildingFootprintPolicy",
                Map.of(
                        "nodePenalties", buildingProperties.toPolicy().nodePenalties(),
                        "structureDemolitionCostKmEq", buildingProperties.toPolicy().structureDemolitionCostKmEq(),
                        "settlementSeverancePenalty", buildingProperties.toPolicy().settlementSeverancePenalty(),
                        "rowEvaluationHalfWidthMeters", buildingProperties.getRowEvaluationHalfWidthMeters(),
                        "structureProximityBufferMeters", buildingProperties.getStructureProximityBufferMeters(),
                        "blockInstitutionalAndReligiousCompounds", buildingProperties.isBlockInstitutionalAndReligiousCompounds(),
                        "rasterPath", "data/processed/buildings/assam_building_density_utm46.tif",
                        "settlementBoundariesGeoJsonPath", "data/processed/buildings/assam_settlement_boundaries.geojson",
                        "buildingFootprintsGeoJsonPath", "data/processed/buildings/assam_building_footprints.geojson"));

        metadata.put(
                "demographicPolicy",
                Map.of(
                        "nodePenalties", demographicProperties.toPolicy().nodePenalties(),
                        "vulnerabilityMultipliers", demographicProperties.toPolicy().vulnerabilityMultipliers(),
                        "directRowPenaltyPer1000PopKmEq", demographicProperties.getDirectRowPenaltyPer1000PopKmEq(),
                        "primaryBufferPenaltyPer1000PopKmEq", demographicProperties.getPrimaryBufferPenaltyPer1000PopKmEq(),
                        "secondaryBufferPenaltyPer1000PopKmEq", demographicProperties.getSecondaryBufferPenaltyPer1000PopKmEq(),
                        "primaryDemographicBufferMeters", demographicProperties.getPrimaryDemographicBufferMeters(),
                        "secondaryDemographicBufferMeters", demographicProperties.getSecondaryDemographicBufferMeters(),
                        "blockVeryHighUrbanDensityCore", demographicProperties.isBlockVeryHighUrbanDensityCore(),
                        "rasterPath", "data/processed/demographics/assam_population_density_utm46.tif",
                        "demographicsGeoJsonPath", "data/processed/demographics/assam_population_demographics.geojson"));

        metadata.put(
                "lulc",
                Map.of(
                        "samplesLoaded", built.lulcSamplesLoaded(),
                        "penalties", lulcProperties.toPolicy().penalties()));

        metadata.put(
                "floodRaster",
                Map.of(
                        "rasterId", flood.id(),
                        "source", flood.sourceUrl(),
                        "layer", flood.layerName(),
                        "sourceResolution", flood.sourceResolution(),
                        "width", flood.width(),
                        "height", flood.height(),
                        "remoteRequestsDuringPreload", built.floodPreload().remoteRequests(),
                        "reusedExistingCache", built.floodPreload().reused()));

        metadata.put("floodPenalties", floodProperties.toPolicy().penalties());

        Map<String, Object> slopePolicy = new LinkedHashMap<>();
        slopePolicy.put("freeMaxDegrees", 5.0);
        slopePolicy.put("lowMaxDegrees", 10.0);
        slopePolicy.put("moderateMaxDegrees", 15.0);
        slopePolicy.put("steepMaxDegrees", 25.0);
        slopePolicy.put("verySteepMaxDegrees", 30.0);
        slopePolicy.put("lowPenalty", 0.15);
        slopePolicy.put("moderatePenalty", 0.40);
        slopePolicy.put("steepPenalty", 1.00);
        slopePolicy.put("verySteepPenalty", 2.00);
        slopePolicy.put("blockNoData", true);
        slopePolicy.put("blockAboveVerySteep", true);
        metadata.put("slopePolicy", slopePolicy);

        metadata.put(
                "settlementPolicy",
                Map.of(
                        "penalties", settlementProperties.toPolicy().penalties(),
                        "blockUrbanCore", settlementProperties.isBlockUrbanCore(),
                        "blockNoData", settlementProperties.isBlockNoData(),
                        "rasterPath", "data/processed/settlements/assam_settlement_exposure_utm46.tif",
                        "catalogPath", "data/processed/settlements/assam_settlements_catalog.geojson"));

        metadata.put("distanceBaselineLengthKm", round(baselineLengthKm));
        metadata.put("routeLengthKm", round(routeLengthKm));
        metadata.put("deviationPercent", round(deviationPercent));
        metadata.put(
                "constraintMethod",
                maxDeviationPercent == null
                        ? "none"
                        : "unconstrained-A*-if-feasible-otherwise-Lagrangian-relaxation");
        metadata.put("constraintOptimizationApproximate", maxDeviationPercent != null);

        Map<String, Object> mainlineProperties = new LinkedHashMap<>();
        mainlineProperties.put("profile", "COMPOSITE_12_LAYER");
        mainlineProperties.put("routeLengthKm", round(routeLengthKm));
        mainlineProperties.put("distanceBaselineKm", round(baselineLengthKm));
        mainlineProperties.put("deviationPercent", round(deviationPercent));
        mainlineProperties.put("pointCount", mainline.size());
        mainlineProperties.put("greenfield", true);
        mainlineProperties.put("dataDriven", true);
        mainlineProperties.put(
                "explanation",
                "A* over the common projected 250 m routing grid using "
                        + "LULC + flood + slope + protected-area + settlement-exposure "
                        + "+ existing-roads-and-railways (" + mode.name() + ") "
                        + "+ perennial-rivers-and-water-body-crossings "
                        + "+ wildlife-migration-corridors-and-esz "
                        + "+ geotechnical-soil-bearing-and-landslide-susceptibility "
                        + "+ high-voltage-transmission-lines-and-utility-corridors "
                        + "+ 2D-settlement-boundaries-and-building-footprints "
                        + "+ population-density-and-demographic-vulnerability "
                        + "with dedicated A* feeder/spur tie-in roads, river bridge spans, "
                        + "WII elevated wildlife viaducts, fault/landslide retaining-wall & liquefaction mitigations, "
                        + "CEA-compliant high-voltage transmission crossing protection spans, "
                        + "2D Right-of-Way (RoW) building footprint & settlement conflict minimization, "
                        + "and population-scaled & demographic-vulnerability-weighted corridor exposure minimization.");

        List<Map<String, Object>> features =
                networkPlannerService.buildGeoJsonFeatures(
                        mainline,
                        mainlineProperties,
                        networkPlan);

        return Map.of(
                "type", "FeatureCollection",
                "studyRegion", region.name(),
                "start", start,
                "goal", goal,
                "mode", "COMPOSITE_12_LAYER",
                "facilityMode", mode.name(),
                "metadata", metadata,
                "warning", "Preliminary greenfield planning corridor only; not a legal "
                        + "land-acquisition or clearance determination.",
                "features", features);
    }

    private void validateEndpoints(GeoPoint start, GeoPoint goal) {
        if (!region.contains(start) || !region.contains(goal)) {
            throw new IllegalArgumentException(
                    "Both endpoints must be inside the configured study region");
        }
    }

    private static StudyRegion corridorWindow(
            StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        if (bufferDeg < 0.02 || bufferDeg > 0.30) {
            throw new IllegalArgumentException(
                    "corridorBufferDeg must be between 0.02 and 0.30");
        }
        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg);

        return new StudyRegion(
                full.name() + " / composite A-B corridor",
                minLat,
                minLon,
                maxLat,
                maxLon,
                a,
                b);
    }

    private static double require(String name, Double value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    name + " is required when the paired coordinate is provided");
        }
        return value;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
