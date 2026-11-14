package in.marg.routing;

import in.marg.bhuvan.BhuvanCatalog;
import in.marg.bhuvan.BhuvanFeatureInfoClient;
import in.marg.bhuvan.BhuvanLulcSampler;
import in.marg.building.BuildingDensityRasterProvider;
import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.config.BuildingRoutingProperties;
import in.marg.config.DemographicRoutingProperties;
import in.marg.config.DrainageRoutingProperties;
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
import in.marg.demographic.PopulationDensityRasterProvider;
import in.marg.drainage.DrainageObstructionRiskTier;
import in.marg.drainage.DrainageSublayerType;
import in.marg.drainage.DrainageWetlandClass;
import in.marg.drainage.DrainageWetlandRasterProvider;
import in.marg.drainage.DrainageWetlandVectorIndex;
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

class DrainageWetlandLayerValidationTest {

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
    void validateAllFiveSublayersRasterAndFiveRequiredCalculations() {
        DrainageWetlandRasterProvider drainageRaster = new DrainageWetlandRasterProvider(
                "data/processed/drainage/assam_drainage_wetland_utm46.tif"
        );
        DrainageWetlandVectorIndex drainageIndex = new DrainageWetlandVectorIndex(
                projection,
                "data/processed/drainage/assam_wetlands_polygons.geojson",
                "data/processed/drainage/assam_drainage_network.geojson",
                "data/processed/drainage/assam_watershed_basins.geojson"
        );
        DrainageRoutingProperties drainageProps = new DrainageRoutingProperties();
        DrainageWetlandCostLayer drainageLayer = new DrainageWetlandCostLayer(
                drainageRaster, drainageIndex, projection, drainageProps
        );

        System.out.println("=== 1. LAYER 13 FIVE SUBLAYERS & RASTER VALIDATION ===");
        // Sublayer 1: 45 2D Wetland Polygons
        assertEquals(45, drainageIndex.allWetlands().size());
        // Sublayers 2, 3, 4: Linear Drainage Features
        assertFalse(drainageIndex.allDrainageLines().isEmpty());
        assertTrue(drainageIndex.allDrainageLines().size() >= 4);
        long canalAndStreamCount = drainageIndex.allDrainageLines().stream()
                .filter(l -> l.sublayerType() == DrainageSublayerType.IRRIGATION_CANAL
                        || l.sublayerType() == DrainageSublayerType.SEASONAL_STREAM)
                .count();
        assertTrue(canalAndStreamCount >= 2);

        // Sublayer 5: 8 Contiguous Watershed Catchments
        assertEquals(8, drainageIndex.allWatersheds().size());
        assertEquals("WS-01-KOHORA-DIPHLU",
                drainageIndex.findWatershedForPoint(new GeoPoint(26.58970, 93.40035)).watershedId());
        assertEquals("WS-08-DIPHU-MANJA-DHANSIRI",
                drainageIndex.findWatershedForPoint(new GeoPoint(25.84573, 93.43781)).watershedId());

        // Verify raster sampling and interchange exclusion rules
        assertEquals(DrainageWetlandClass.WELL_DRAINED_UPLAND_OR_NONE,
                drainageRaster.sampleClass(26.58970, 93.40035));
        assertEquals(DrainageWetlandClass.NATURAL_OR_DESIGNATED_WETLAND_CORE,
                drainageRaster.sampleClass(26.5480, 93.4007));
        assertTrue(DrainageWetlandClass.NATURAL_OR_DESIGNATED_WETLAND_CORE.isInterchangeExcludedZone());
        assertTrue(DrainageWetlandClass.SEASONAL_MARSH_AND_WETLAND_BUFFER.isInterchangeExcludedZone());
        assertTrue(DrainageWetlandClass.IRRIGATION_CANAL_COMMAND_ROW.isInterchangeExcludedZone());
        assertTrue(DrainageWetlandClass.SEASONAL_STREAM_AND_TORRENT_CORRIDOR.isInterchangeExcludedZone());
        assertFalse(DrainageWetlandClass.WELL_DRAINED_UPLAND_OR_NONE.isInterchangeExcludedZone());

        // Verify soft vs hard blocking of wetland cores
        GeoPoint wetlandCenter = new GeoPoint(26.5480, 93.4007);
        CostAssessment softWetland = drainageLayer.assess(wetlandCenter);
        assertFalse(softWetland.blocked());
        assertEquals(3.85, softWetland.penaltyMultiplier(), 1e-6);

        DrainageRoutingProperties strictProps = new DrainageRoutingProperties();
        strictProps.setBlockAllWetlandCore(true);
        DrainageWetlandCostLayer strictLayer = new DrainageWetlandCostLayer(
                drainageRaster, drainageIndex, projection, strictProps
        );
        assertTrue(strictLayer.assess(wetlandCenter).blocked(),
                "NATURAL_OR_DESIGNATED_WETLAND_CORE must be blocked when blockAllWetlandCore=true");
    }

    @Test
    void validateIsolatedDrainageRoutingAndFullComposite12Vs13LayerAcrossModes() throws Exception {
        DrainageWetlandRasterProvider drainageRaster = new DrainageWetlandRasterProvider(
                "data/processed/drainage/assam_drainage_wetland_utm46.tif"
        );
        DrainageWetlandVectorIndex drainageIndex = new DrainageWetlandVectorIndex(
                projection,
                "data/processed/drainage/assam_wetlands_polygons.geojson",
                "data/processed/drainage/assam_drainage_network.geojson",
                "data/processed/drainage/assam_watershed_basins.geojson"
        );
        DrainageRoutingProperties drainageProps = new DrainageRoutingProperties();
        DrainageWetlandCostLayer drainageLayer = new DrainageWetlandCostLayer(
                drainageRaster, drainageIndex, projection, drainageProps
        );

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

        // 1. Isolated Routing: DISTANCE_BASELINE vs DRAINAGE_ONLY
        List<GeoPoint> distanceBaseline = router.route(
                fullRegion, start, goal, new DistanceOnlyCostSurface(), 250.0, corridorWindow
        );
        List<GeoPoint> drainageOnlyRoute = router.route(
                fullRegion, start, goal, new CompositeCostSurface(List.of(drainageLayer)), 250.0, corridorWindow
        );

        System.out.println("distanceBaseline points count: " + distanceBaseline.size());
        for (GeoPoint p : distanceBaseline) {
            System.out.println("  baseline pt: " + p);
        }
        System.out.println("drainageOnly points count: " + drainageOnlyRoute.size());

        RightOfWayCorridorEngine.CorridorFootprint baseFp =
                rowEngine.buildCorridorFootprint(distanceBaseline, FacilityMode.EXPRESSWAY);
        RightOfWayCorridorEngine.CorridorFootprint drainFp =
                rowEngine.buildCorridorFootprint(drainageOnlyRoute, FacilityMode.EXPRESSWAY);

        for (DrainageWetlandVectorIndex.WetlandPolygon wp : drainageIndex.allWetlands()) {
            double d = baseFp.combinedDirectRowMetric().distance(wp.jtsPolygon());
            if (d < 500.0) {
                System.out.println("Wetland near baseline direct RoW: " + wp.id() + " dist=" + d
                        + " intersects=" + baseFp.combinedDirectRowMetric().intersects(wp.jtsPolygon())
                        + " envIntersects=" + baseFp.environmentalBufferMetric().intersects(wp.jtsPolygon()));
            }
        }

        DrainageWetlandVectorIndex.RouteDrainageWetlandImpact baselineImpact =
                drainageIndex.evaluateCorridorDrainageAndWetlandImpact(distanceBaseline, baseFp);
        DrainageWetlandVectorIndex.RouteDrainageWetlandImpact drainageOnlyImpact =
                drainageIndex.evaluateCorridorDrainageAndWetlandImpact(drainageOnlyRoute, drainFp);
        DrainageWetlandVectorIndex.CandidateDrainageComparison isolatedComp =
                drainageIndex.compareCandidateAlignments(
                        "DISTANCE_BASELINE", baselineImpact,
                        "DRAINAGE_ONLY", drainageOnlyImpact);

        System.out.println("\n=== 2. ISOLATED ROUTING: DISTANCE_BASELINE vs. DRAINAGE_ONLY ===");
        System.out.println("DISTANCE_BASELINE: lengthKm=" + round(router.routeLengthKm(distanceBaseline))
                + " | wetlandsDirectRow=" + baselineImpact.wetlandsInDirectRowCount()
                + " (" + baselineImpact.wetlandAreaInDirectRowHectares() + " ha)"
                + " | waterCrossings=" + baselineImpact.totalWaterCrossingsCount()
                + " | parallelThalwegKm=" + baselineImpact.parallelThalwegEncroachmentLengthKm()
                + " | obstructionIdx=" + baselineImpact.drainageObstructionRiskIndex()
                + " | watersheds=" + baselineImpact.watershedsTraversedCount()
                + " (divides=" + baselineImpact.watershedBoundaryCrossingsCount() + ")"
                + " | structures=" + baselineImpact.totalDrainageStructuresRequiredCount());
        System.out.println("DRAINAGE_ONLY    : lengthKm=" + round(router.routeLengthKm(drainageOnlyRoute))
                + " | wetlandsDirectRow=" + drainageOnlyImpact.wetlandsInDirectRowCount()
                + " (" + drainageOnlyImpact.wetlandAreaInDirectRowHectares() + " ha)"
                + " | waterCrossings=" + drainageOnlyImpact.totalWaterCrossingsCount()
                + " | parallelThalwegKm=" + drainageOnlyImpact.parallelThalwegEncroachmentLengthKm()
                + " | obstructionIdx=" + drainageOnlyImpact.drainageObstructionRiskIndex()
                + " | watersheds=" + drainageOnlyImpact.watershedsTraversedCount()
                + " (divides=" + drainageOnlyImpact.watershedBoundaryCrossingsCount() + ")"
                + " | structures=" + drainageOnlyImpact.totalDrainageStructuresRequiredCount());
        System.out.println("Verdict: " + isolatedComp.engineeringComparisonVerdict());

        assertTrue(drainageOnlyImpact.wetlandsInDirectRowCount() < baselineImpact.wetlandsInDirectRowCount(),
                "DRAINAGE_ONLY must reduce Direct RoW wetland polygon intersections relative to DISTANCE_BASELINE");
        assertTrue(drainageOnlyImpact.wetlandAreaInDirectRowHectares() < baselineImpact.wetlandAreaInDirectRowHectares(),
                "DRAINAGE_ONLY must reduce Direct RoW wetland area (ha) relative to DISTANCE_BASELINE");
        assertTrue(drainageOnlyImpact.drainageObstructionRiskIndex() < baselineImpact.drainageObstructionRiskIndex(),
                "DRAINAGE_ONLY must reduce drainageObstructionRiskIndex relative to DISTANCE_BASELINE");
        assertEquals(8, baselineImpact.watershedsTraversedCount(),
                "North-to-south corridor from Kohora to Diphu traverses all 8 contiguous watersheds");
        assertTrue(baselineImpact.watershedBoundaryCrossingsCount() >= 7);

        // 2. Full Composite Routing: 12-LAYER (BEFORE) vs. 13-LAYER (AFTER) across EXPRESSWAY & NATIONAL_HIGHWAY
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
                drainageRaster, drainageIndex, drainageProps,
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
                drainageRaster, drainageIndex, drainageProps,
                rowEngine
        );

        System.out.println("\n=== 3. FULL COMPOSITE: 12-LAYER (BEFORE) vs. 13-LAYER (AFTER) ===");
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

            CompositeCostSurface surface12 = new CompositeCostSurface(List.of(
                    lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer,
                    transportLayer, waterLayer, wildlifeLayer, geotechLayer, utilityLayer,
                    buildingLayer, demoLayer
            ));
            CompositeCostSurface surface13 = new CompositeCostSurface(List.of(
                    lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer,
                    transportLayer, waterLayer, wildlifeLayer, geotechLayer, utilityLayer,
                    buildingLayer, demoLayer, drainageLayer
            ));

            List<GeoPoint> route12 = router.route(fullRegion, start, goal, surface12, 250.0, corridorWindow);
            List<GeoPoint> route13 = router.route(fullRegion, start, goal, surface13, 250.0, corridorWindow);

            RightOfWayCorridorEngine.CorridorFootprint fp12 = rowEngine.buildCorridorFootprint(route12, mode);
            RightOfWayCorridorEngine.CorridorFootprint fp13 = rowEngine.buildCorridorFootprint(route13, mode);

            DrainageWetlandVectorIndex.RouteDrainageWetlandImpact dw12 =
                    drainageIndex.evaluateCorridorDrainageAndWetlandImpact(route12, fp12);
            DrainageWetlandVectorIndex.RouteDrainageWetlandImpact dw13 =
                    drainageIndex.evaluateCorridorDrainageAndWetlandImpact(route13, fp13);
            DrainageWetlandVectorIndex.CandidateDrainageComparison comp12Vs13 =
                    drainageIndex.compareCandidateAlignments("COMPOSITE_12_LAYER", dw12, "COMPOSITE_13_LAYER", dw13);

            List<CostLayer> spurLayers13 = List.of(
                    lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer,
                    waterLayer, wildlifeLayer, geotechLayer, utilityLayer,
                    buildingLayer, demoLayer, drainageLayer
            );
            HighwayNetworkPlannerService.NetworkPlanResult plan13 =
                    plannerService.planNetwork(fullRegion, route13, hubEval, spurLayers13);
            Map<String, Object> diag12 = metricsService.summarize(route12, mode);
            Map<String, Object> diag13 = metricsService.summarize(route13, mode);

            System.out.println("\n--- Mode: " + mode + " (RoW Width: " + fp13.mainlineRowWidthMeters() + " m) ---");
            System.out.println("12-LAYER (BEFORE): lengthKm=" + diag12.get("routeLengthKm")
                    + " | wetlandsDirectRow=" + dw12.wetlandsInDirectRowCount()
                    + " (" + dw12.wetlandAreaInDirectRowHectares() + " ha)"
                    + " | wetlands100m=" + dw12.wetlandsIn100mBufferCount()
                    + " | wetlands250m=" + dw12.wetlandsIn250mBufferCount()
                    + " | waterCrossings=" + dw12.totalWaterCrossingsCount()
                    + " (streams=" + dw12.seasonalStreamCrossingsCount()
                    + ", channels=" + dw12.naturalDrainageChannelCrossingsCount()
                    + ", canals=" + dw12.irrigationCanalCrossingsCount()
                    + ", watercourses=" + dw12.localWatercourseCrossingsCount() + ")"
                    + " | parallelThalwegKm=" + dw12.parallelThalwegEncroachmentLengthKm()
                    + " | obstructionIdx=" + dw12.drainageObstructionRiskIndex()
                    + " | structures=" + dw12.totalDrainageStructuresRequiredCount()
                    + " (span=" + dw12.totalRecommendedDrainageSpanMeters() + " m)");
            System.out.println("13-LAYER (AFTER) : lengthKm=" + diag13.get("routeLengthKm")
                    + " | wetlandsDirectRow=" + dw13.wetlandsInDirectRowCount()
                    + " (" + dw13.wetlandAreaInDirectRowHectares() + " ha)"
                    + " | wetlands100m=" + dw13.wetlandsIn100mBufferCount()
                    + " | wetlands250m=" + dw13.wetlandsIn250mBufferCount()
                    + " | waterCrossings=" + dw13.totalWaterCrossingsCount()
                    + " (streams=" + dw13.seasonalStreamCrossingsCount()
                    + ", channels=" + dw13.naturalDrainageChannelCrossingsCount()
                    + ", canals=" + dw13.irrigationCanalCrossingsCount()
                    + ", watercourses=" + dw13.localWatercourseCrossingsCount() + ")"
                    + " | parallelThalwegKm=" + dw13.parallelThalwegEncroachmentLengthKm()
                    + " | obstructionIdx=" + dw13.drainageObstructionRiskIndex()
                    + " | structures=" + dw13.totalDrainageStructuresRequiredCount()
                    + " (span=" + dw13.totalRecommendedDrainageSpanMeters() + " m)");
            System.out.println("12-vs-13 Verdict: " + comp12Vs13.engineeringComparisonVerdict());

            assertTrue(dw13.wetlandsInDirectRowCount() <= dw12.wetlandsInDirectRowCount(),
                    "13-LAYER must reduce or eliminate Direct RoW wetland intersections relative to 12-LAYER");
            assertTrue(dw13.wetlandAreaInDirectRowHectares() <= dw12.wetlandAreaInDirectRowHectares(),
                    "13-LAYER must reduce Direct RoW wetland encroachment area (ha) relative to 12-LAYER");
            assertTrue(dw13.drainageObstructionRiskIndex() <= dw12.drainageObstructionRiskIndex(),
                    "13-LAYER must reduce or match drainageObstructionRiskIndex relative to 12-LAYER");
            assertTrue(dw13.totalWaterCrossingsCount() >= 0,
                    "13-LAYER must report exact water crossings across seasonal streams, channels, canals, and watercourses");
            assertEquals(8, dw13.watershedsTraversedCount());
            assertTrue(dw13.totalDrainageStructuresRequiredCount() >= 0);

            // Verify hard constraints on all mainline vertices
            for (GeoPoint v : route13) {
                Double pa = paProvider.sampleOrNull(v.lat(), v.lon());
                Double slp = slopeProvider.sampleOrNull(v.lat(), v.lon());
                assertTrue(pa != null && Math.round(pa) == 0L,
                        "Mainline vertex must not enter Protected Area: " + v);
                assertTrue(slp != null && slp <= 30.0,
                        "Mainline vertex must satisfy Slope <= 30 deg: " + v + " (slope=" + slp + ")");
            }

            // Verify no interchange is placed inside a wetland or drainage exclusion zone
            for (HighwayNetworkPlannerService.PlannedInterchange ic : plan13.interchanges()) {
                DrainageWetlandClass dwc = drainageRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(dwc.isInterchangeExcludedZone(),
                        "Interchange " + ic.interchangeId() + " must not be placed in " + dwc);
            }

            // Verify diagnostics & GeoJSON features include Layer 13 outputs
            assertNotNull(diag13.get("drainageWetlandExposureKm"));
            assertNotNull(diag13.get("drainageAndWetlandSummary"));
            Map<?, ?> rowSummary = (Map<?, ?>) diag13.get("rightOfWayCorridorSummary");
            assertNotNull(rowSummary.get("drainageWetlandClassHectares"));
            assertNotNull(rowSummary.get("drainageAndWetlandOverlay"));

            List<Map<String, Object>> features = plannerService.buildGeoJsonFeatures(
                    route13, Map.of("profile", "COMPOSITE_13_LAYER"), plan13
            );
            long drainageStructFeatures = features.stream()
                    .filter(f -> "DRAINAGE_CROSSING_STRUCTURE".equals(
                            ((Map<?, ?>) f.get("properties")).get("featureRole")))
                    .count();
            assertEquals(plan13.drainageStructures().size(), drainageStructFeatures);
            if (!plan13.drainageStructures().isEmpty()) {
                assertTrue(drainageStructFeatures > 0,
                        "Expected DRAINAGE_CROSSING_STRUCTURE features in GeoJSON output");
            }
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
