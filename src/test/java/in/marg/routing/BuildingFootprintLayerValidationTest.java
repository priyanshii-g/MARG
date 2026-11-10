package in.marg.routing;

import in.marg.bhuvan.BhuvanCatalog;
import in.marg.bhuvan.BhuvanFeatureInfoClient;
import in.marg.bhuvan.BhuvanLulcSampler;
import in.marg.building.BuildingDensityClass;
import in.marg.building.BuildingDensityRasterProvider;
import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.building.BuildingType;
import in.marg.building.SettlementClassification;
import in.marg.config.BuildingRoutingProperties;
import in.marg.config.FloodRoutingProperties;
import in.marg.config.GeotechRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.SettlementRoutingProperties;
import in.marg.config.SlopeRoutingProperties;
import in.marg.config.TransportRoutingProperties;
import in.marg.config.UtilityRoutingProperties;
import in.marg.config.WaterBodyRoutingProperties;
import in.marg.config.WildlifeRoutingProperties;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.floodraster.BhuvanFloodRasterCache;
import in.marg.geotech.GeotechHazardClass;
import in.marg.geotech.GeotechLineamentVectorIndex;
import in.marg.geotech.GeotechRasterProvider;
import in.marg.hydrology.RiverNetworkVectorIndex;
import in.marg.hydrology.WaterBodyClass;
import in.marg.hydrology.WaterBodyRasterProvider;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.provider.BhuvanLulcProvider;
import in.marg.provider.LulcProvider;
import in.marg.row.RightOfWayCorridorEngine;
import in.marg.settlement.SettlementExposureClass;
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
import in.marg.utility.UtilityCorridorClass;
import in.marg.utility.UtilityNetworkVectorIndex;
import in.marg.utility.UtilityRasterProvider;
import in.marg.wildlife.WildlifeCorridorVectorIndex;
import in.marg.wildlife.WildlifeRasterProvider;
import in.marg.wildlife.WildlifeZoneClass;
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

class BuildingFootprintLayerValidationTest {

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
    void validateBuildingDensityRasterAndVectorIndex() {
        BuildingDensityRasterProvider buildingRaster = new BuildingDensityRasterProvider(
                "data/processed/buildings/assam_building_density_utm46.tif"
        );
        BuildingFootprintVectorIndex buildingIndex = new BuildingFootprintVectorIndex(
                projection,
                "data/processed/buildings/assam_settlement_boundaries.geojson",
                "data/processed/buildings/assam_building_footprints.geojson"
        );
        BuildingRoutingProperties props = new BuildingRoutingProperties();
        BuildingFootprintCostLayer buildingLayer = new BuildingFootprintCostLayer(
                buildingRaster, buildingIndex, projection, props
        );

        System.out.println("=== 1. BUILDING FOOTPRINT & SETTLEMENT BOUNDARY VECTOR/RASTER VALIDATION ===");
        assertEquals(104, buildingIndex.allSettlements().size());
        assertEquals(1882, buildingIndex.allBuildings().size());

        long urbanCount = buildingIndex.allSettlements().stream()
                .filter(s -> s.settlementClassification() == SettlementClassification.URBAN)
                .count();
        long ruralCount = buildingIndex.allSettlements().stream()
                .filter(s -> s.settlementClassification() == SettlementClassification.RURAL)
                .count();
        long industrialCount = buildingIndex.allSettlements().stream()
                .filter(s -> s.settlementClassification() == SettlementClassification.INDUSTRIAL)
                .count();
        assertEquals(38, urbanCount);
        assertEquals(58, ruralCount);
        assertEquals(8, industrialCount);

        long resBuildings = buildingIndex.allBuildings().stream()
                .filter(b -> b.buildingType() == BuildingType.RESIDENTIAL).count();
        long comBuildings = buildingIndex.allBuildings().stream()
                .filter(b -> b.buildingType() == BuildingType.COMMERCIAL).count();
        long instBuildings = buildingIndex.allBuildings().stream()
                .filter(b -> b.buildingType() == BuildingType.INSTITUTIONAL).count();
        long relBuildings = buildingIndex.allBuildings().stream()
                .filter(b -> b.buildingType() == BuildingType.RELIGIOUS).count();
        long indBuildings = buildingIndex.allBuildings().stream()
                .filter(b -> b.buildingType() == BuildingType.INDUSTRIAL).count();
        long vilInfBuildings = buildingIndex.allBuildings().stream()
                .filter(b -> b.buildingType() == BuildingType.VILLAGE_INFRASTRUCTURE).count();

        assertEquals(908, resBuildings);
        assertEquals(476, comBuildings);
        assertEquals(173, instBuildings);
        assertEquals(151, relBuildings);
        assertEquals(100, indBuildings);
        assertEquals(74, vilInfBuildings);

        // Origin is in open rural corridor (NONE); Destination ties into Diphu urban fringe (HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP)
        assertEquals(BuildingDensityClass.NONE, buildingRaster.sampleClass(26.58970, 93.40035));
        assertEquals(BuildingDensityClass.HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP,
                buildingRaster.sampleClass(25.84573, 93.43781));

        // Verify interchange exclusion rules
        assertTrue(BuildingDensityClass.INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT.isInterchangeExcludedZone());
        assertTrue(BuildingDensityClass.HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP.isInterchangeExcludedZone());
        assertTrue(BuildingDensityClass.MEDIUM_DENSITY_VILLAGE_CLUSTER.isInterchangeExcludedZone());
        assertFalse(BuildingDensityClass.LOW_DENSITY_RURAL_RIBBON.isInterchangeExcludedZone());
        assertFalse(BuildingDensityClass.SPARSE_HOMESTEAD_FRINGE.isInterchangeExcludedZone());
        assertFalse(BuildingDensityClass.NONE.isInterchangeExcludedZone());

        // Find an institutional/religious building centroid and verify raster + soft/hard assessment
        var schoolOrNamghar = buildingIndex.allBuildings().stream()
                .filter(b -> b.buildingType() == BuildingType.INSTITUTIONAL)
                .findFirst().orElseThrow();
        GeoPoint instPt = schoolOrNamghar.centroid();
        assertEquals(BuildingDensityClass.INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT,
                buildingRaster.sampleClass(instPt.lat(), instPt.lon()));

        CostAssessment softInst = buildingLayer.assess(instPt);
        assertFalse(softInst.blocked());
        assertEquals(4.40, softInst.penaltyMultiplier(), 1e-6);

        BuildingRoutingProperties strictProps = new BuildingRoutingProperties();
        strictProps.setBlockInstitutionalAndReligiousCompounds(true);
        BuildingFootprintCostLayer strictLayer = new BuildingFootprintCostLayer(
                buildingRaster, buildingIndex, projection, strictProps
        );
        CostAssessment strictInst = strictLayer.assess(instPt);
        assertTrue(strictInst.blocked(),
                "Institutional and religious compounds must be hard-blocked when blockInstitutionalAndReligiousCompounds=true");

        // Verify 2D RoW edge conflict assessment when a 250 m step slices across a settlement's buildings
        MetricPoint mInst = schoolOrNamghar.centroidMetric();
        GeoPoint stepA = projection.inverse(new MetricPoint(mInst.x() - 125.0, mInst.y()));
        GeoPoint stepB = projection.inverse(new MetricPoint(mInst.x() + 125.0, mInst.y()));
        CostAssessment edgeConflict = buildingLayer.assessEdge(stepA, stepB);
        assertFalse(edgeConflict.blocked());
        assertTrue(edgeConflict.penaltyMultiplier() > 3.0,
                "Slicing a 60 m RoW directly through an institutional compound and settlement core must incur high R&R conflict cost: "
                        + edgeConflict.penaltyMultiplier());
        System.out.println("Edge Conflict across " + schoolOrNamghar.name()
                + " -> multiplier=" + edgeConflict.penaltyMultiplier()
                + " | className=" + edgeConflict.className());
    }

    @Test
    void validateIsolatedBuildingRoutingAndFullComposite10Vs11LayerAcrossModes() throws Exception {
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

        CompositeRouteMetricsService metricsService = new CompositeRouteMetricsService(
                projection, lulcProvider, lulcProps, floodCache, floodProps,
                slopeProvider, slopeProps, paProvider, settlementProvider, settlementProps,
                transportRaster, transportIndex, transportProps,
                waterRaster, riverIndex, waterProps,
                wildlifeRaster, wildlifeIndex, wildlifeProps,
                geotechRaster, geotechIndex, geotechProps,
                utilityRaster, utilityIndex, utilityProps,
                buildingRaster, buildingIndex, buildingProps,
                rowEngine
        );

        StudyRegion corridor = corridorWindow(fullRegion, fullRegion.origin(), fullRegion.destination(), 0.15);

        // === 2. ISOLATED BUILDING FOOTPRINT ROUTING (DISTANCE_BASELINE vs BUILDING_ONLY) ===
        System.out.println("\n=== 2. ISOLATED BUILDING FOOTPRINT ROUTING (DISTANCE_BASELINE vs BUILDING_ONLY) ===");
        List<GeoPoint> distBaseline = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new DistanceOnlyCostSurface(), 250.0, corridor
        );
        List<GeoPoint> buildingOnly = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(buildingLayer)), 250.0, corridor
        );

        Map<String, Object> distDiag = metricsService.summarize(distBaseline, FacilityMode.EXPRESSWAY);
        Map<String, Object> bldOnlyDiag = metricsService.summarize(buildingOnly, FacilityMode.EXPRESSWAY);
        @SuppressWarnings("unchecked")
        double distBldPen = ((Map<String, Double>) distDiag.get("approximatePenaltyContributionKm"))
                .get("BUILDING_FOOTPRINTS");
        @SuppressWarnings("unchecked")
        double isolatedBldPen = ((Map<String, Double>) bldOnlyDiag.get("approximatePenaltyContributionKm"))
                .get("BUILDING_FOOTPRINTS");

        System.out.println("DISTANCE_BASELINE Building: len=" + distDiag.get("routeLengthKm")
                + " km | pen=" + distBldPen + " km-eq | rowImpact=" + distDiag.get("buildingAndSettlementRowImpactSummary")
                + " | exposure=" + distDiag.get("buildingDensityExposureKm"));
        System.out.println("BUILDING_ONLY     Building: len=" + bldOnlyDiag.get("routeLengthKm")
                + " km | pen=" + isolatedBldPen + " km-eq | rowImpact=" + bldOnlyDiag.get("buildingAndSettlementRowImpactSummary")
                + " | exposure=" + bldOnlyDiag.get("buildingDensityExposureKm"));

        assertTrue(isolatedBldPen < distBldPen,
                "Isolated building-footprint-aware routing must reduce building acquisition & settlement severance penalties");

        // Verify strict blockInstitutionalAndReligiousCompounds=true routes cleanly with 0 vertices on compound footprints
        BuildingRoutingProperties strictProps = new BuildingRoutingProperties();
        strictProps.setBlockInstitutionalAndReligiousCompounds(true);
        BuildingFootprintCostLayer strictLayer = new BuildingFootprintCostLayer(
                buildingRaster, buildingIndex, projection, strictProps
        );
        List<GeoPoint> strictBldRoute = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(strictLayer)), 250.0, corridor
        );
        for (GeoPoint v : strictBldRoute) {
            assertFalse(
                    buildingRaster.sampleClass(v.lat(), v.lon())
                            == BuildingDensityClass.INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT,
                    "Strict building-blocked route must have 0 vertices on INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT");
        }

        // === 3. FULL COMPOSITE 10-LAYER (BEFORE) vs 11-LAYER (AFTER: EXPRESSWAY & NATIONAL_HIGHWAY) ===
        System.out.println("\n=== 3. COMPOSITE 10-LAYER (BEFORE) vs 11-LAYER (AFTER) ===");
        HubEvaluationService hubService = new HubEvaluationService(transportIndex, projection, transportProps);
        HighwayNetworkPlannerService plannerService = new HighwayNetworkPlannerService(
                transportIndex, transportRaster, slopeProvider, paProvider,
                router, projection, transportProps,
                waterRaster, riverIndex, waterProps,
                wildlifeRaster, wildlifeIndex, wildlifeProps,
                geotechRaster, geotechIndex, geotechProps,
                utilityRaster, utilityIndex, utilityProps,
                buildingRaster, buildingIndex, buildingProps,
                rowEngine
        );

        for (FacilityMode mode : List.of(FacilityMode.EXPRESSWAY, FacilityMode.NATIONAL_HIGHWAY)) {
            var hubEval = hubService.evaluate(
                    fullRegion.origin(), fullRegion.destination(), corridor,
                    mode, List.of(), null, null
            );
            TransportNetworkCostLayer modeTransportLayer = new TransportNetworkCostLayer(
                    transportRaster, transportIndex, projection, transportProps.toPolicy(),
                    transportProps.accessibilityPullFor(mode),
                    hubEval.approvedHubs(),
                    fullRegion.origin(), fullRegion.destination(),
                    transportProps.getCollectorSweetSpotMaxKm(),
                    transportProps.getCollectorLongitudinalBandKm()
            );

            // 10-Layer (Before Building Footprints & 2D Settlement Polygons)
            List<GeoPoint> route10 = router.route(
                    fullRegion, fullRegion.origin(), fullRegion.destination(),
                    new CompositeCostSurface(List.of(
                            lulcLayer, floodLayer, slopeLayer, paLayer,
                            settlementLayer, modeTransportLayer, waterLayer,
                            wildlifeLayer, geotechLayer, utilityLayer)),
                    250.0, corridor
            );
            Map<String, Object> diag10 = metricsService.summarize(route10, mode);

            // 11-Layer (After Settlement Boundaries & Individual Building Footprints)
            List<CostLayer> spurAll11NonTransportLayers = List.of(
                    lulcLayer, floodLayer, slopeLayer, paLayer,
                    settlementLayer, waterLayer, wildlifeLayer,
                    geotechLayer, utilityLayer, buildingLayer
            );
            List<GeoPoint> route11 = router.route(
                    fullRegion, fullRegion.origin(), fullRegion.destination(),
                    new CompositeCostSurface(List.of(
                            lulcLayer, floodLayer, slopeLayer, paLayer,
                            settlementLayer, modeTransportLayer, waterLayer,
                            wildlifeLayer, geotechLayer, utilityLayer, buildingLayer)),
                    250.0, corridor
            );
            Map<String, Object> diag11 = metricsService.summarize(route11, mode);
            var plan11 = plannerService.planNetwork(
                    fullRegion, route11, hubEval, spurAll11NonTransportLayers
            );

            @SuppressWarnings("unchecked")
            double pen10 = ((Map<String, Double>) diag10.get("approximatePenaltyContributionKm"))
                    .get("BUILDING_FOOTPRINTS");
            @SuppressWarnings("unchecked")
            double pen11 = ((Map<String, Double>) diag11.get("approximatePenaltyContributionKm"))
                    .get("BUILDING_FOOTPRINTS");

            @SuppressWarnings("unchecked")
            Map<String, Object> bldSummary10 =
                    (Map<String, Object>) diag10.get("buildingAndSettlementRowImpactSummary");
            @SuppressWarnings("unchecked")
            Map<String, Object> bldSummary11 =
                    (Map<String, Object>) diag11.get("buildingAndSettlementRowImpactSummary");
            assertNotNull(bldSummary10);
            assertNotNull(bldSummary11);

            System.out.println("\n--- MODE: " + mode.name() + " ---");
            System.out.println("10-LAYER (BEFORE) : len=" + diag10.get("routeLengthKm")
                    + " km | bldPen=" + pen10 + " km-eq | rowImpact=" + bldSummary10
                    + " | bldExposure=" + diag10.get("buildingDensityExposureKm"));
            System.out.println("11-LAYER (AFTER)  : len=" + diag11.get("routeLengthKm")
                    + " km | bldPen=" + pen11 + " km-eq | rowImpact=" + bldSummary11
                    + " | bldExposure=" + diag11.get("buildingDensityExposureKm"));
            System.out.println("11-LAYER CONFLICTS: " + plan11.settlementConflictZones().stream()
                    .map(c -> c.settlementName() + " (" + c.settlementClassification()
                            + ", bldInRow=" + c.buildingsInDirectRowCount()
                            + ", bldInConst=" + c.buildingsInConstructionZoneCount()
                            + ", haInRow=" + c.areaInDirectRowHectares()
                            + "ha @ ch=" + c.chainageKm() + "km -> " + c.recommendedEngineeringMitigation() + ")")
                    .toList());

            assertTrue(pen11 <= pen10,
                    "11-Layer composite route in " + mode + " mode must reduce or match 10-Layer building footprint penalty");

            int bldDirect10 = ((Number) bldSummary10.get("buildingsInDirectRowCount")).intValue();
            int bldDirect11 = ((Number) bldSummary11.get("buildingsInDirectRowCount")).intValue();
            assertTrue(bldDirect11 <= bldDirect10,
                    "11-Layer composite route must reduce or match buildings inside Direct RoW ("
                            + bldDirect11 + " vs " + bldDirect10 + ")");

            @SuppressWarnings("unchecked")
            Map<String, Object> hc11 = (Map<String, Object>) diag11.get("hardConstraintValidation");
            assertTrue(
                    ((Number) hc11.get("protectedAreaViolationSamples")).intValue() <= 1,
                    "11-Layer mainline sub-grid 125 m boundary diagnostic must not exceed the 1 corner pixel baseline");
            assertEquals(0, hc11.get("protectedAreaNoDataSamples"));
            assertEquals(0, hc11.get("slopeNoDataViolationSamples"));

            // Every 250 m routing vertex on the 11-Layer mainline must strictly satisfy PA==0 and Slope<=30 deg
            for (GeoPoint v : route11) {
                Double pa = paProvider.sampleOrNull(v.lat(), v.lon());
                Double slp = slopeProvider.sampleOrNull(v.lat(), v.lon());
                assertTrue(pa != null && Math.round(pa) == 0L,
                        "Mainline vertex must not enter Protected Area: " + v);
                assertTrue(slp != null && slp <= 30.0,
                        "Mainline vertex must satisfy Slope <= 30 deg: " + v + " (slope=" + slp + ")");
            }

            // Ensure no highway interchange sits on high-density built-up or institutional/religious compounds
            for (var ic : plan11.interchanges()) {
                BuildingDensityClass icBld = buildingRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(icBld.isInterchangeExcludedZone(),
                        "Interchange " + ic.interchangeId() + " must never sit on dense built-up or institutional/religious compounds: " + icBld);

                UtilityCorridorClass icUtil = utilityRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(icUtil.isInterchangeExcludedZone());

                GeotechHazardClass icGeo = geotechRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(icGeo.isInterchangeUnsafeSubgrade());

                WildlifeZoneClass icWild = wildlifeRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(icWild.isInterchangeExcludedZone());

                WaterBodyClass icWater = waterRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(
                        icWater == WaterBodyClass.MAJOR_PERENNIAL_RIVER
                                || icWater == WaterBodyClass.MEDIUM_PERENNIAL_RIVER
                                || icWater == WaterBodyClass.MINOR_PERENNIAL_STREAM
                                || icWater == WaterBodyClass.INLAND_WETLAND_WATERBODY);
            }

            // Ensure all dedicated A* spurs pass vertex hard constraints & Protected Area checks
            for (int i = 0; i < plan11.feederSpurs().size(); i++) {
                var ic = plan11.interchanges().get(i);
                var spur = plan11.feederSpurs().get(i);
                assertEquals(ic.point(), spur.path().get(0));
                assertEquals(spur.hub().bypassTieIn(), spur.path().get(spur.path().size() - 1));
                assertFalse(
                        settlementProvider.sampleClass(
                                spur.hub().bypassTieIn().lat(),
                                spur.hub().bypassTieIn().lon()) == SettlementExposureClass.URBAN_CORE);
                for (GeoPoint sv : spur.path()) {
                    Double pa = paProvider.sampleOrNull(sv.lat(), sv.lon());
                    Double slp = slopeProvider.sampleOrNull(sv.lat(), sv.lon());
                    assertTrue(pa != null && Math.round(pa) == 0L);
                    assertTrue(slp != null && slp <= 30.0);
                }
            }

            // Verify Multi-Feature GeoJSON output includes SETTLEMENT_BUILDING_CONFLICT_ZONE features
            List<Map<String, Object>> geoJsonFeatures =
                    plannerService.buildGeoJsonFeatures(route11, Map.of("profile", "COMPOSITE_11_LAYER"), plan11);
            long conflictFeatures = geoJsonFeatures.stream()
                    .filter(f -> "SETTLEMENT_BUILDING_CONFLICT_ZONE".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                    .count();
            assertEquals(plan11.settlementConflictZones().size(), conflictFeatures,
                    "GeoJSON FeatureCollection must emit a SETTLEMENT_BUILDING_CONFLICT_ZONE Point feature for every settlement conflict zone");
        }
    }

    private static StudyRegion corridorWindow(StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg);
        return new StudyRegion(full.name() + " / building validation window", minLat, minLon, maxLat, maxLon, a, b);
    }
}
