package in.marg.routing;

import in.marg.bhuvan.BhuvanCatalog;
import in.marg.bhuvan.BhuvanFeatureInfoClient;
import in.marg.bhuvan.BhuvanLulcSampler;
import in.marg.building.BuildingDensityRasterProvider;
import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.config.BuildingRoutingProperties;
import in.marg.config.DemographicRoutingProperties;
import in.marg.config.FloodRoutingProperties;
import in.marg.config.GeotechRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.SettlementRoutingProperties;
import in.marg.config.SlopeRoutingProperties;
import in.marg.config.TransportRoutingProperties;
import in.marg.config.UtilityRoutingProperties;
import in.marg.config.WaterBodyRoutingProperties;
import in.marg.config.WildlifeRoutingProperties;
import in.marg.demographic.DemographicVectorIndex;
import in.marg.demographic.DemographicVulnerabilityTier;
import in.marg.demographic.PopulationDensityClass;
import in.marg.demographic.PopulationDensityRasterProvider;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.floodraster.BhuvanFloodRasterCache;
import in.marg.geotech.GeotechLineamentVectorIndex;
import in.marg.geotech.GeotechRasterProvider;
import in.marg.hydrology.RiverNetworkVectorIndex;
import in.marg.hydrology.WaterBodyRasterProvider;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.provider.BhuvanLulcProvider;
import in.marg.provider.LulcProvider;
import in.marg.row.RightOfWayCorridorEngine;
import in.marg.settlement.SettlementRasterProvider;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.spatial.Wgs84UtmProjection;
import in.marg.terrain.SlopeRasterProvider;
import in.marg.transport.FacilityMode;
import in.marg.transport.HighwayNetworkPlannerService;
import in.marg.transport.HubEvaluationService;
import in.marg.transport.TransportNetworkRasterProvider;
import in.marg.transport.TransportNetworkVectorIndex;
import in.marg.utility.UtilityNetworkVectorIndex;
import in.marg.utility.UtilityRasterProvider;
import in.marg.wildlife.WildlifeCorridorVectorIndex;
import in.marg.wildlife.WildlifeRasterProvider;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemographicLayerValidationTest {

    private final CoordinateProjection projection = new Wgs84UtmProjection(46);
    private final GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
    private final RightOfWayCorridorEngine rowEngine = new RightOfWayCorridorEngine(projection);
    private final StudyRegion fullRegion = new StudyRegion(
            "Kaziranga–Karbi Anglong, Assam",
            25.75, 93.10, 26.65, 93.75,
            new GeoPoint(26.58970, 93.40035),
            new GeoPoint(25.84573, 93.43781)
    );

    @Test
    void validatePopulationDensityRasterDemographicTractsAnd200Vs8000SettlementScenario() {
        PopulationDensityRasterProvider demoRaster = new PopulationDensityRasterProvider(
                "data/processed/demographics/assam_population_density_utm46.tif"
        );
        DemographicVectorIndex demoIndex = new DemographicVectorIndex(
                projection,
                "data/processed/demographics/assam_population_demographics.geojson"
        );
        BuildingFootprintVectorIndex buildingIndex = new BuildingFootprintVectorIndex(
                projection,
                "data/processed/buildings/assam_settlement_boundaries.geojson",
                "data/processed/buildings/assam_building_footprints.geojson"
        );
        DemographicRoutingProperties demoProps = new DemographicRoutingProperties();
        DemographicCostLayer demoLayer = new DemographicCostLayer(
                demoRaster, demoIndex, projection, demoProps
        );

        System.out.println("=== 1. POPULATION DENSITY & DEMOGRAPHIC VECTOR/RASTER VALIDATION ===");
        assertEquals(120, demoIndex.allTracts().size());

        int totalPop = demoIndex.allTracts().stream()
                .mapToInt(DemographicVectorIndex.DemographicTractPolygon::totalPopulation)
                .sum();
        int ruralPop = demoIndex.allTracts().stream()
                .mapToInt(DemographicVectorIndex.DemographicTractPolygon::ruralPopulation)
                .sum();
        int urbanPop = demoIndex.allTracts().stream()
                .mapToInt(DemographicVectorIndex.DemographicTractPolygon::urbanPopulation)
                .sum();
        int totalHouseholds = demoIndex.allTracts().stream()
                .mapToInt(DemographicVectorIndex.DemographicTractPolygon::householdsCount)
                .sum();
        long highPopClusters = demoIndex.allTracts().stream()
                .filter(t -> t.totalPopulation() >= 3000)
                .count();
        long lowPopHamlets = demoIndex.allTracts().stream()
                .filter(t -> t.totalPopulation() < 500)
                .count();

        assertEquals(509317, totalPop);
        assertEquals(209094, ruralPop);
        assertEquals(300223, urbanPop);
        assertEquals(totalPop, ruralPop + urbanPop);
        assertEquals(105051, totalHouseholds);
        assertEquals(54, highPopClusters);
        assertEquals(12, lowPopHamlets);

        // Verify all 4 demographic vulnerability tiers are represented
        for (DemographicVulnerabilityTier tier : DemographicVulnerabilityTier.values()) {
            long count = demoIndex.allTracts().stream()
                    .filter(t -> t.vulnerabilityTier() == tier)
                    .count();
            assertTrue(count > 0, "Expected tracts in vulnerability tier: " + tier);
        }

        // Verify raster sampling and interchange exclusion rules
        assertEquals(PopulationDensityClass.UNINHABITED_OR_BACKGROUND,
                demoRaster.sampleClass(26.58970, 93.40035));
        assertTrue(PopulationDensityClass.VERY_HIGH_URBAN_DENSITY_CORE.isInterchangeExcludedZone());
        assertTrue(PopulationDensityClass.HIGH_DENSITY_POPULATION_CLUSTER.isInterchangeExcludedZone());
        assertFalse(PopulationDensityClass.MODERATE_DENSITY_SETTLEMENT.isInterchangeExcludedZone());
        assertTrue(PopulationDensityClass.MODERATE_DENSITY_SETTLEMENT.isHighPopulationExposureZone());
        assertFalse(PopulationDensityClass.LOW_DENSITY_VILLAGE.isInterchangeExcludedZone());
        assertFalse(PopulationDensityClass.SPARSE_RURAL_DISPERSED.isInterchangeExcludedZone());
        assertFalse(PopulationDensityClass.UNINHABITED_OR_BACKGROUND.isInterchangeExcludedZone());

        // Compare Route A passing near a ~200-resident settlement (STL-CAT-024, 210 residents)
        // vs. Route B passing at the exact same lateral clearance near an ~8,000-resident settlement (STL-CAT-053 Manja Town, 8,420 residents)
        var smallSettlement = demoIndex.allTracts().stream()
                .filter(t -> "STL-CAT-024".equals(t.settlementId()))
                .findFirst().orElseThrow();
        var largeSettlement = demoIndex.allTracts().stream()
                .filter(t -> "STL-CAT-053".equals(t.settlementId()))
                .findFirst().orElseThrow();

        assertEquals(210, smallSettlement.totalPopulation());
        assertEquals(8420, largeSettlement.totalPopulation());

        // Verify raster inside Manja Town core vs soft/hard node assessment
        GeoPoint manjaCenter = largeSettlement.centroid();
        assertEquals(PopulationDensityClass.VERY_HIGH_URBAN_DENSITY_CORE,
                demoRaster.sampleClass(manjaCenter.lat(), manjaCenter.lon()));
        CostAssessment softManja = demoLayer.assess(manjaCenter);
        assertFalse(softManja.blocked());
        assertEquals(4.20, softManja.penaltyMultiplier(), 1e-6);

        DemographicRoutingProperties strictProps = new DemographicRoutingProperties();
        strictProps.setBlockVeryHighUrbanDensityCore(true);
        DemographicCostLayer strictLayer = new DemographicCostLayer(
                demoRaster, demoIndex, projection, strictProps
        );
        assertTrue(strictLayer.assess(manjaCenter).blocked(),
                "VERY_HIGH_URBAN_DENSITY_CORE must be blocked when blockVeryHighUrbanDensityCore=true");

        // Construct two 500 m candidate segments passing at identical 140 m offset from centroid (~30-45 m outside boundary)
        MetricPoint mSmall = smallSettlement.centroidMetric();
        List<GeoPoint> routeNear200 = List.of(
                projection.inverse(new MetricPoint(mSmall.x() - 250.0, mSmall.y() + 140.0)),
                projection.inverse(new MetricPoint(mSmall.x() + 250.0, mSmall.y() + 140.0))
        );

        MetricPoint mLarge = largeSettlement.centroidMetric();
        List<GeoPoint> routeNear8000 = List.of(
                projection.inverse(new MetricPoint(mLarge.x() - 250.0, mLarge.y() + 140.0)),
                projection.inverse(new MetricPoint(mLarge.x() + 250.0, mLarge.y() + 140.0))
        );

        CostAssessment edgeNear200 = demoLayer.assessEdge(routeNear200.get(0), routeNear200.get(1));
        CostAssessment edgeNear8000 = demoLayer.assessEdge(routeNear8000.get(0), routeNear8000.get(1));

        System.out.println("Route A (near 210-resident village STL-CAT-024) edge penalty multiplier = "
                + edgeNear200.penaltyMultiplier());
        System.out.println("Route B (near 8,420-resident Manja Town STL-CAT-053) edge penalty multiplier = "
                + edgeNear8000.penaltyMultiplier());
        assertTrue(edgeNear8000.penaltyMultiplier() > edgeNear200.penaltyMultiplier() * 8.0,
                "Passing near an 8,420-resident settlement must incur a dramatically higher demographic penalty than passing near a 210-resident hamlet at identical distance");

        DemographicVectorIndex.RouteDemographicImpact impact200 =
                demoIndex.evaluateRouteDemographicImpact(
                        routeNear200, rowEngine, FacilityMode.EXPRESSWAY, buildingIndex);
        DemographicVectorIndex.RouteDemographicImpact impact8000 =
                demoIndex.evaluateRouteDemographicImpact(
                        routeNear8000, rowEngine, FacilityMode.EXPRESSWAY, buildingIndex);
        DemographicVectorIndex.CandidateDemographicComparison comp200Vs8000 =
                demoIndex.compareCandidateAlignments(
                        "ROUTE_B_NEAR_8420_RESIDENTS", impact8000,
                        "ROUTE_A_NEAR_210_RESIDENTS", impact200);

        System.out.println("200-vs-8000 Comparison Summary: " + comp200Vs8000.toMap());
        assertTrue(impact8000.populationWithin250mEnvironmentalBuffer()
                > impact200.populationWithin250mEnvironmentalBuffer() * 10);
        assertTrue(impact8000.weightedPopulationExposureIndex()
                > impact200.weightedPopulationExposureIndex() * 10.0);
        assertTrue(comp200Vs8000.preferredLowerExposureCandidate()
                .startsWith("ROUTE_A_NEAR_210_RESIDENTS"));

        // Verify explicit distinction: buffer-exposed population != displaced population
        assertEquals(0, impact200.estimatedDirectlyDisplacedPopulation(),
                "Passing outside building footprints has 0 displaced population even though residents are inside the 250 m buffer");
        assertTrue(impact200.nonDisplacedBufferExposedPopulationWithin250m() > 0,
                "Buffer-exposed population must be tracked separately from displaced population");
        assertEquals(
                impact8000.populationWithin250mEnvironmentalBuffer() - impact8000.estimatedDirectlyDisplacedPopulation(),
                impact8000.nonDisplacedBufferExposedPopulationWithin250m());
    }

    @Test
    void validateIsolatedDemographicRoutingAndFullComposite11Vs12LayerAcrossModes() throws Exception {
        PopulationDensityRasterProvider demoRaster = new PopulationDensityRasterProvider(
                "data/processed/demographics/assam_population_density_utm46.tif"
        );
        DemographicVectorIndex demoIndex = new DemographicVectorIndex(
                projection,
                "data/processed/demographics/assam_population_demographics.geojson"
        );
        DemographicRoutingProperties demoProps = new DemographicRoutingProperties();
        DemographicCostLayer demoLayer = new DemographicCostLayer(
                demoRaster, demoIndex, projection, demoProps
        );

        BuildingDensityRasterProvider buildingRaster = new BuildingDensityRasterProvider(
                "data/processed/buildings/assam_building_density_utm46.tif"
        );
        BuildingFootprintVectorIndex buildingIndex = new BuildingFootprintVectorIndex(
                projection,
                "data/processed/buildings/assam_settlement_boundaries.geojson",
                "data/processed/buildings/assam_building_footprints.geojson"
        );
        BuildingRoutingProperties buildingProps = new BuildingRoutingProperties();
        BuildingFootprintCostLayer buildingLayer = new BuildingFootprintCostLayer(
                buildingRaster, buildingIndex, projection, buildingProps
        );

        UtilityRasterProvider utilityRaster = new UtilityRasterProvider(
                "data/processed/utilities/assam_utility_corridors_utm46.tif"
        );
        UtilityNetworkVectorIndex utilityIndex = new UtilityNetworkVectorIndex(
                projection,
                "data/processed/utilities/assam_power_transmission_lines.geojson",
                "data/processed/utilities/assam_utility_assets.geojson"
        );
        UtilityRoutingProperties utilityProps = new UtilityRoutingProperties();
        UtilityCorridorCostLayer utilityLayer = new UtilityCorridorCostLayer(
                utilityRaster, utilityIndex, projection, utilityProps
        );

        GeotechRasterProvider geotechRaster = new GeotechRasterProvider(
                "data/processed/geotech/assam_geotech_landslide_utm46.tif"
        );
        GeotechLineamentVectorIndex geotechIndex = new GeotechLineamentVectorIndex(
                projection,
                "data/processed/geotech/assam_fault_landslide_zones.geojson"
        );
        GeotechRoutingProperties geotechProps = new GeotechRoutingProperties();
        GeotechLandslideCostLayer geotechLayer = new GeotechLandslideCostLayer(
                geotechRaster, geotechIndex, projection, geotechProps
        );

        WildlifeRasterProvider wildlifeRaster = new WildlifeRasterProvider(
                "data/processed/wildlife/assam_wildlife_esz_corridors_utm46.tif"
        );
        WildlifeCorridorVectorIndex wildlifeIndex = new WildlifeCorridorVectorIndex(
                projection,
                "data/processed/wildlife/assam_wildlife_corridors.geojson"
        );
        WildlifeRoutingProperties wildlifeProps = new WildlifeRoutingProperties();
        WildlifeCorridorCostLayer wildlifeLayer = new WildlifeCorridorCostLayer(
                wildlifeRaster, wildlifeIndex, projection, wildlifeProps
        );

        WaterBodyRasterProvider waterRaster = new WaterBodyRasterProvider(
                "data/processed/hydrology/assam_water_bodies_utm46.tif"
        );
        RiverNetworkVectorIndex riverIndex = new RiverNetworkVectorIndex(
                projection,
                "data/processed/hydrology/assam_perennial_rivers.geojson"
        );
        WaterBodyRoutingProperties waterProps = new WaterBodyRoutingProperties();
        WaterBodyCostLayer waterLayer = new WaterBodyCostLayer(
                waterRaster, riverIndex, projection, waterProps
        );

        TransportNetworkRasterProvider transportRaster = new TransportNetworkRasterProvider(
                "data/processed/transport/assam_transport_network_utm46.tif"
        );
        TransportNetworkVectorIndex transportIndex = new TransportNetworkVectorIndex(
                projection,
                "data/processed/transport/assam_transport_network.geojson",
                "data/processed/transport/assam_regional_hubs.geojson"
        );
        TransportRoutingProperties transportProps = new TransportRoutingProperties();

        ObjectMapper mapper = new ObjectMapper();
        BhuvanCatalog catalog = new BhuvanCatalog();
        BhuvanFeatureInfoClient dummyLulcClient = new BhuvanFeatureInfoClient(null, null);
        BhuvanLulcSampler sampler = new BhuvanLulcSampler(
                catalog, dummyLulcClient, 0.01, 0.004, 1, "data/cache/bhuvan-lulc-samples.json", mapper
        );
        Method loadLulc = BhuvanLulcSampler.class.getDeclaredMethod("loadPersistentCache");
        loadLulc.setAccessible(true);
        loadLulc.invoke(sampler);
        LulcProvider lulcProvider = new BhuvanLulcProvider(sampler);

        LulcRoutingProperties lulcProps = new LulcRoutingProperties();
        Map<String, Double> lulcPen = new LinkedHashMap<>();
        lulcPen.put("BUILT_UP", 1.5);
        lulcPen.put("FOREST", 0.8);
        lulcPen.put("WETLAND_WATER", 2.0);
        lulcPen.put("AGRICULTURE", 0.25);
        lulcPen.put("GRASS_GRAZING", 0.10);
        lulcPen.put("BARREN_WASTELAND", 0.0);
        lulcPen.put("SNOW", 2.5);
        lulcPen.put("UNKNOWN", 1.0);
        lulcProps.setPenalties(lulcPen);
        LulcCostLayer lulcLayer = new LulcCostLayer(lulcProvider, lulcProps.toPolicy());

        BhuvanFloodRasterCache floodCache = new BhuvanFloodRasterCache(
                null, "data/cache/bhuvan-flood-wms", 512, 1000, 28, 1, mapper
        );
        Method loadFlood = BhuvanFloodRasterCache.class.getDeclaredMethod("load");
        loadFlood.setAccessible(true);
        loadFlood.invoke(floodCache);

        FloodRoutingProperties floodProps = new FloodRoutingProperties();
        Map<String, Double> floodPen = new LinkedHashMap<>();
        floodPen.put("VERY_LOW", 0.0);
        floodPen.put("LOW", 0.20);
        floodPen.put("MODERATE", 0.60);
        floodPen.put("HIGH", 1.50);
        floodPen.put("VERY_HIGH", 3.00);
        floodPen.put("NOT_IN_HAZARD_LAYER", 0.0);
        floodPen.put("UNKNOWN", 2.00);
        floodProps.setPenalties(floodPen);
        FloodRasterCostLayer floodLayer = new FloodRasterCostLayer(floodCache, floodProps.toPolicy());

        SlopeRasterProvider slopeProvider = new SlopeRasterProvider("data/processed/dem/assam_slope_utm46.tif");
        SlopeRoutingProperties slopeProps = new SlopeRoutingProperties();
        SlopeCostLayer slopeLayer = new SlopeCostLayer(slopeProvider, slopeProps);

        ProtectedAreaRasterProvider paProvider = new ProtectedAreaRasterProvider(
                "data/processed/protected-areas/assam_protected_area_mask_utm46.tif"
        );
        ProtectedAreaCostLayer paLayer = new ProtectedAreaCostLayer(paProvider);

        SettlementRasterProvider settlementProvider = new SettlementRasterProvider(
                "data/processed/settlements/assam_settlement_exposure_utm46.tif"
        );
        SettlementRoutingProperties settlementProps = new SettlementRoutingProperties();
        SettlementCostLayer settlementLayer = new SettlementCostLayer(settlementProvider, settlementProps);

        GeoPoint start = fullRegion.origin();
        GeoPoint goal = fullRegion.destination();
        StudyRegion corridorWindow = new StudyRegion(
                "Composite Corridor Window",
                Math.max(fullRegion.minLat(), Math.min(start.lat(), goal.lat()) - 0.15),
                Math.max(fullRegion.minLon(), Math.min(start.lon(), goal.lon()) - 0.15),
                Math.min(fullRegion.maxLat(), Math.max(start.lat(), goal.lat()) + 0.15),
                Math.min(fullRegion.maxLon(), Math.max(start.lon(), goal.lon()) + 0.15),
                start,
                goal
        );

        // 1. Isolated Routing: DISTANCE_BASELINE vs DEMOGRAPHIC_ONLY
        List<GeoPoint> distanceBaseline = router.route(
                fullRegion, start, goal, new DistanceOnlyCostSurface(), 250.0, corridorWindow
        );
        List<GeoPoint> demographicOnlyRoute = router.route(
                fullRegion, start, goal, new CompositeCostSurface(List.of(demoLayer)), 250.0, corridorWindow
        );

        DemographicVectorIndex.RouteDemographicImpact baselineDemoImpact =
                demoIndex.evaluateRouteDemographicImpact(
                        distanceBaseline, rowEngine, FacilityMode.EXPRESSWAY, buildingIndex);
        DemographicVectorIndex.RouteDemographicImpact demoOnlyImpact =
                demoIndex.evaluateRouteDemographicImpact(
                        demographicOnlyRoute, rowEngine, FacilityMode.EXPRESSWAY, buildingIndex);
        DemographicVectorIndex.CandidateDemographicComparison isolatedComparison =
                demoIndex.compareCandidateAlignments(
                        "DISTANCE_BASELINE", baselineDemoImpact,
                        "DEMOGRAPHIC_ONLY", demoOnlyImpact);

        System.out.println("\n=== 2. ISOLATED ROUTING: DISTANCE_BASELINE vs. DEMOGRAPHIC_ONLY ===");
        System.out.println("DISTANCE_BASELINE : lengthKm=" + round(router.routeLengthKm(distanceBaseline))
                + " | popDirectRow=" + baselineDemoImpact.populationWithinDirectRow()
                + " | pop100m=" + baselineDemoImpact.populationWithin100mConstructionBuffer()
                + " | pop250m=" + baselineDemoImpact.populationWithin250mEnvironmentalBuffer()
                + " | pop500m=" + baselineDemoImpact.populationWithin500mSecondaryBuffer()
                + " | displacedPop=" + baselineDemoImpact.estimatedDirectlyDisplacedPopulation()
                + " | exposureIndex=" + baselineDemoImpact.weightedPopulationExposureIndex());
        System.out.println("DEMOGRAPHIC_ONLY  : lengthKm=" + round(router.routeLengthKm(demographicOnlyRoute))
                + " | popDirectRow=" + demoOnlyImpact.populationWithinDirectRow()
                + " | pop100m=" + demoOnlyImpact.populationWithin100mConstructionBuffer()
                + " | pop250m=" + demoOnlyImpact.populationWithin250mEnvironmentalBuffer()
                + " | pop500m=" + demoOnlyImpact.populationWithin500mSecondaryBuffer()
                + " | displacedPop=" + demoOnlyImpact.estimatedDirectlyDisplacedPopulation()
                + " | exposureIndex=" + demoOnlyImpact.weightedPopulationExposureIndex());
        System.out.println("Isolated Comparison: " + isolatedComparison.toMap());

        assertTrue(demoOnlyImpact.populationWithinDirectRow() < baselineDemoImpact.populationWithinDirectRow(),
                "DEMOGRAPHIC_ONLY must reduce Direct RoW population exposure relative to DISTANCE_BASELINE");
        assertTrue(demoOnlyImpact.populationWithin250mEnvironmentalBuffer()
                        < baselineDemoImpact.populationWithin250mEnvironmentalBuffer(),
                "DEMOGRAPHIC_ONLY must reduce 250 m buffer population exposure relative to DISTANCE_BASELINE");
        assertTrue(demoOnlyImpact.weightedPopulationExposureIndex()
                        < baselineDemoImpact.weightedPopulationExposureIndex(),
                "DEMOGRAPHIC_ONLY must reduce weightedPopulationExposureIndex relative to DISTANCE_BASELINE");

        // 2. Full Composite Routing: 11-LAYER (BEFORE) vs. 12-LAYER (AFTER) across EXPRESSWAY & NATIONAL_HIGHWAY
        HubEvaluationService hubEvalService = new HubEvaluationService(
                transportIndex, projection, transportProps
        );
        HighwayNetworkPlannerService plannerService = new HighwayNetworkPlannerService(
                transportIndex, transportRaster, slopeProvider, paProvider, router, projection,
                transportProps, waterRaster, riverIndex, waterProps,
                wildlifeRaster, wildlifeIndex, wildlifeProps,
                geotechRaster, geotechIndex, geotechProps,
                utilityRaster, utilityIndex, utilityProps,
                buildingRaster, buildingIndex, buildingProps,
                demoRaster, demoIndex, demoProps,
                rowEngine
        );
        CompositeRouteMetricsService metricsService = new CompositeRouteMetricsService(
                projection, lulcProvider, lulcProps, floodCache, floodProps,
                slopeProvider, slopeProps, paProvider, settlementProvider, settlementProps,
                transportRaster, transportIndex, transportProps,
                waterRaster, riverIndex, waterProps,
                wildlifeRaster, wildlifeIndex, wildlifeProps,
                geotechRaster, geotechIndex, geotechProps,
                utilityRaster, utilityIndex, utilityProps,
                buildingRaster, buildingIndex, buildingProps,
                demoRaster, demoIndex, demoProps,
                rowEngine
        );

        System.out.println("\n=== 3. FULL COMPOSITE: 11-LAYER (BEFORE) vs. 12-LAYER (AFTER) ===");
        for (FacilityMode mode : List.of(FacilityMode.EXPRESSWAY, FacilityMode.NATIONAL_HIGHWAY)) {
            HubEvaluationService.EvaluationResult hubEval = hubEvalService.evaluate(
                    start, goal, corridorWindow, mode, List.of(), null, null
            );
            double pull = transportProps.accessibilityPullFor(mode);
            TransportNetworkCostLayer transportLayer = new TransportNetworkCostLayer(
                    transportRaster, transportIndex, projection, transportProps.toPolicy(),
                    pull, hubEval.approvedHubs(), start, goal,
                    transportProps.getCollectorSweetSpotMaxKm(),
                    transportProps.getCollectorLongitudinalBandKm()
            );

            CompositeCostSurface surface11 = new CompositeCostSurface(List.of(
                    lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer,
                    transportLayer, waterLayer, wildlifeLayer, geotechLayer, utilityLayer, buildingLayer
            )).withDenseColonyLastResortMode();
            CompositeCostSurface surface12 = new CompositeCostSurface(List.of(
                    lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer,
                    transportLayer, waterLayer, wildlifeLayer, geotechLayer, utilityLayer, buildingLayer, demoLayer
            )).withDenseColonyLastResortMode();

            List<GeoPoint> route11 = router.route(fullRegion, start, goal, surface11, 250.0, corridorWindow);
            List<GeoPoint> route12 = router.route(fullRegion, start, goal, surface12, 250.0, corridorWindow);

            DemographicVectorIndex.RouteDemographicImpact demo11 =
                    demoIndex.evaluateRouteDemographicImpact(route11, rowEngine, mode, buildingIndex);
            DemographicVectorIndex.RouteDemographicImpact demo12 =
                    demoIndex.evaluateRouteDemographicImpact(route12, rowEngine, mode, buildingIndex);
            DemographicVectorIndex.CandidateDemographicComparison comp11Vs12 =
                    demoIndex.compareCandidateAlignments("COMPOSITE_11_LAYER", demo11, "COMPOSITE_12_LAYER", demo12);

            BuildingFootprintVectorIndex.RouteBuildingAndSettlementImpact bld11 =
                    buildingIndex.evaluateRouteRowImpact(route11, rowEngine, mode);
            BuildingFootprintVectorIndex.RouteBuildingAndSettlementImpact bld12 =
                    buildingIndex.evaluateRouteRowImpact(route12, rowEngine, mode);

            List<CostLayer> spurLayers12 = List.of(
                    lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer,
                    waterLayer, wildlifeLayer, geotechLayer, utilityLayer, buildingLayer, demoLayer
            );
            HighwayNetworkPlannerService.NetworkPlanResult plan12 =
                    plannerService.planNetwork(fullRegion, route12, hubEval, spurLayers12);
            Map<String, Object> diag11 = metricsService.summarize(route11, mode);
            Map<String, Object> diag12 = metricsService.summarize(route12, mode);

            System.out.println("\n--- Mode: " + mode + " (RoW Width: " + plan12.rowCorridorFootprint().mainlineRowWidthMeters() + " m) ---");
            System.out.println("11-LAYER (BEFORE): lengthKm=" + diag11.get("routeLengthKm")
                    + " | popDirectRow=" + demo11.populationWithinDirectRow()
                    + " (rural=" + demo11.ruralPopulationWithinDirectRow()
                    + ", urban=" + demo11.urbanPopulationWithinDirectRow() + ")"
                    + " | pop100m=" + demo11.populationWithin100mConstructionBuffer()
                    + " | pop250m=" + demo11.populationWithin250mEnvironmentalBuffer()
                    + " | pop500m=" + demo11.populationWithin500mSecondaryBuffer()
                    + " | pop1000m=" + demo11.populationWithin1000mCatchmentBuffer()
                    + " | displacedPop=" + demo11.estimatedDirectlyDisplacedPopulation()
                    + " | nonDisplaced250m=" + demo11.nonDisplacedBufferExposedPopulationWithin250m()
                    + " | vulnerableAge250m=" + demo11.vulnerableAgePopulationExposedWithin250m()
                    + " | stSc250m=" + demo11.scheduledTribeAndCastesExposedWithin250m()
                    + " | exposureIdx=" + demo11.weightedPopulationExposureIndex()
                    + " | bld250m=" + bld11.buildingsInEnvironmentalBufferRingCount()
                    + " | minBldClearanceM=" + bld11.minCenterlineToBuildingMeters()
                    + " | demoPenaltyKmEq=" + ((Map<?, ?>) diag11.get("approximatePenaltyContributionKm")).get("POPULATION_DEMOGRAPHICS"));
            System.out.println("12-LAYER (AFTER) : lengthKm=" + diag12.get("routeLengthKm")
                    + " | popDirectRow=" + demo12.populationWithinDirectRow()
                    + " (rural=" + demo12.ruralPopulationWithinDirectRow()
                    + ", urban=" + demo12.urbanPopulationWithinDirectRow() + ")"
                    + " | pop100m=" + demo12.populationWithin100mConstructionBuffer()
                    + " | pop250m=" + demo12.populationWithin250mEnvironmentalBuffer()
                    + " | pop500m=" + demo12.populationWithin500mSecondaryBuffer()
                    + " | pop1000m=" + demo12.populationWithin1000mCatchmentBuffer()
                    + " | displacedPop=" + demo12.estimatedDirectlyDisplacedPopulation()
                    + " | nonDisplaced250m=" + demo12.nonDisplacedBufferExposedPopulationWithin250m()
                    + " | vulnerableAge250m=" + demo12.vulnerableAgePopulationExposedWithin250m()
                    + " | stSc250m=" + demo12.scheduledTribeAndCastesExposedWithin250m()
                    + " | exposureIdx=" + demo12.weightedPopulationExposureIndex()
                    + " | bld250m=" + bld12.buildingsInEnvironmentalBufferRingCount()
                    + " | minBldClearanceM=" + bld12.minCenterlineToBuildingMeters()
                    + " | demoPenaltyKmEq=" + ((Map<?, ?>) diag12.get("approximatePenaltyContributionKm")).get("POPULATION_DEMOGRAPHICS"));
            System.out.println("11-vs-12 Candidate Comparison: " + comp11Vs12.toMap());
            System.out.println("12-LAYER populationDensityExposureKm: " + diag12.get("populationDensityExposureKm"));
            System.out.println("11-LAYER hardConstraintValidation: " + diag11.get("hardConstraintValidation"));
            System.out.println("12-LAYER hardConstraintValidation: " + diag12.get("hardConstraintValidation"));

            // Assertions on 12-Layer vs 11-Layer
            assertTrue(bld12.buildingsInDirectRowCount() <= bld11.buildingsInDirectRowCount(),
                    "12-LAYER must reduce or match 11-LAYER buildings in Direct RoW: "
                            + bld12.buildingsInDirectRowCount() + " vs " + bld11.buildingsInDirectRowCount());
            assertTrue(bld12.buildingsInConstructionZoneRingCount() <= bld11.buildingsInConstructionZoneRingCount(),
                    "12-LAYER must reduce or match 11-LAYER buildings in Construction Clearance Zone: "
                            + bld12.buildingsInConstructionZoneRingCount() + " vs " + bld11.buildingsInConstructionZoneRingCount());
            assertTrue(demo12.populationWithin250mEnvironmentalBuffer() < demo11.populationWithin250mEnvironmentalBuffer(),
                    "12-LAYER must significantly reduce 250 m Environmental Buffer population exposure");
            assertTrue(demo12.populationWithin500mSecondaryBuffer() < demo11.populationWithin500mSecondaryBuffer(),
                    "12-LAYER must significantly reduce 500 m Secondary Buffer population exposure");
            assertTrue(demo12.estimatedDirectlyDisplacedPopulation() <= demo11.estimatedDirectlyDisplacedPopulation(),
                    "12-LAYER must reduce or match estimated directly displaced population");
            assertTrue(demo12.vulnerableAgePopulationExposedWithin250m() < demo11.vulnerableAgePopulationExposedWithin250m(),
                    "12-LAYER must reduce vulnerable-age (children + elderly) exposure within 250 m");
            assertTrue(demo12.scheduledTribeAndCastesExposedWithin250m() < demo11.scheduledTribeAndCastesExposedWithin250m(),
                    "12-LAYER must reduce Scheduled Tribe/Caste population exposure within 250 m");
            assertTrue(demo12.weightedPopulationExposureIndex() < demo11.weightedPopulationExposureIndex(),
                    "12-LAYER must reduce weightedPopulationExposureIndex");
            assertTrue(bld12.minCenterlineToBuildingMeters() >= bld11.minCenterlineToBuildingMeters(),
                    "12-LAYER must maintain or increase minimum clearance to the nearest building footprint");

            // Verify hard constraints pass 100% on all 250 m routing vertices
            Map<?, ?> hc11 = (Map<?, ?>) diag11.get("hardConstraintValidation");
            Map<?, ?> hc12 = (Map<?, ?>) diag12.get("hardConstraintValidation");
            assertTrue(((Number) hc12.get("protectedAreaViolationSamples")).intValue() <= 2);
            assertEquals(0, hc12.get("protectedAreaNoDataSamples"));
            assertTrue(((Number) hc12.get("slopeOver30ViolationSamples")).intValue() <= 12,
                    "12-LAYER sub-grid 125 m slope > 30 deg samples must remain within tolerance");
            assertEquals(0, hc12.get("slopeNoDataViolationSamples"));

            for (GeoPoint v : route12) {
                Double pa = paProvider.sampleOrNull(v.lat(), v.lon());
                Double slp = slopeProvider.sampleOrNull(v.lat(), v.lon());
                assertTrue(pa != null && Math.round(pa) == 0L,
                        "Mainline vertex must not enter Protected Area: " + v);
                assertTrue(slp != null && slp <= 30.0,
                        "Mainline vertex must satisfy Slope <= 30 deg: " + v + " (slope=" + slp + ")");
            }

            // Verify no interchange is placed inside a high-density demographic exclusion zone
            for (HighwayNetworkPlannerService.PlannedInterchange ic : plan12.interchanges()) {
                PopulationDensityClass pdc = demoRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(pdc.isInterchangeExcludedZone(),
                        "Interchange " + ic.interchangeId() + " must not be placed in " + pdc);
            }

            // Verify rightOfWayCorridorSummary and GeoJSON features include Layer 12 demographic outputs
            Map<?, ?> rowSummary = (Map<?, ?>) diag12.get("rightOfWayCorridorSummary");
            assertNotNull(rowSummary.get("populationDensityClassHectares"));
            assertNotNull(rowSummary.get("populationAndDemographicOverlay"));

            List<Map<String, Object>> features = plannerService.buildGeoJsonFeatures(
                    route12, Map.of("profile", "COMPOSITE_12_LAYER"), plan12
            );
            long dezFeatures = features.stream()
                    .filter(f -> "DEMOGRAPHIC_EXPOSURE_MITIGATION_ZONE".equals(
                            ((Map<?, ?>) f.get("properties")).get("featureRole")))
                    .count();
            assertEquals(plan12.demographicExposureZones().size(), dezFeatures);
            assertTrue(dezFeatures > 0, "Expected DEMOGRAPHIC_EXPOSURE_MITIGATION_ZONE features in GeoJSON output");
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
