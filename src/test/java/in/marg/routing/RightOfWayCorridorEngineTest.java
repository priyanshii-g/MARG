package in.marg.routing;

import in.marg.bhuvan.BhuvanCatalog;
import in.marg.bhuvan.BhuvanFeatureInfoClient;
import in.marg.bhuvan.BhuvanLulcSampler;
import in.marg.config.FloodRoutingProperties;
import in.marg.config.GeotechRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.RowCorridorProperties;
import in.marg.config.SettlementRoutingProperties;
import in.marg.config.SlopeRoutingProperties;
import in.marg.config.TransportRoutingProperties;
import in.marg.config.UtilityRoutingProperties;
import in.marg.config.WaterBodyRoutingProperties;
import in.marg.config.WildlifeRoutingProperties;
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
import in.marg.row.RowImpactZone;
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

class RightOfWayCorridorEngineTest {

    private final CoordinateProjection projection = new Wgs84UtmProjection(46);
    private final GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
    private final StudyRegion fullRegion = new StudyRegion(
            "Kaziranga–Karbi Anglong, Assam",
            25.75, 93.10, 26.65, 93.75,
            new GeoPoint(26.58970, 93.40035),
            new GeoPoint(25.84573, 93.43781)
    );

    @Test
    void validateJtsMetricBufferingAndUnionDeduplicationAcrossFacilityModes() {
        RightOfWayCorridorEngine engine = new RightOfWayCorridorEngine(projection, new RowCorridorProperties());

        // Construct an exact 10,000 m (10.0 km) West-to-East alignment in UTM Zone 46N
        MetricPoint mStart = projection.forward(new GeoPoint(26.20000, 93.40000));
        MetricPoint mEnd = new MetricPoint(mStart.x() + 10_000.0, mStart.y());
        MetricPoint mMid = new MetricPoint(mStart.x() + 5_000.0, mStart.y());
        MetricPoint mSpurTip = new MetricPoint(mStart.x() + 5_000.0, mStart.y() + 2_000.0);

        List<GeoPoint> mainline = List.of(
                projection.inverse(mStart),
                projection.inverse(mMid),
                projection.inverse(mEnd));
        List<GeoPoint> spurPath = List.of(
                projection.inverse(mMid),
                projection.inverse(mSpurTip));
        GeoPoint interchangePt = projection.inverse(mMid);

        RightOfWayCorridorEngine.CorridorFootprint expPure =
                engine.buildCorridorFootprint(mainline, FacilityMode.EXPRESSWAY);
        RightOfWayCorridorEngine.CorridorFootprint nhPure =
                engine.buildCorridorFootprint(mainline, FacilityMode.NATIONAL_HIGHWAY);

        // Theoretical 10,000 m x 60 m + pi * 30^2 = 602,827 m^2 = 60.28 ha
        assertEquals(60.0, expPure.mainlineRowWidthMeters(), 1e-6);
        assertEquals(60.28, expPure.mainlineRowAreaHectares(), 0.25);

        // Theoretical 10,000 m x 45 m + pi * 22.5^2 = 451,590 m^2 = 45.16 ha
        assertEquals(45.0, nhPure.mainlineRowWidthMeters(), 1e-6);
        assertEquals(45.16, nhPure.mainlineRowAreaHectares(), 0.25);

        // Construction zone (100 m half-width -> 200 m total): 10,000 x 200 + pi * 100^2 = 203.14 ha
        assertEquals(203.14, expPure.constructionZoneGrossHectares(), 0.50);
        assertEquals(
                expPure.constructionZoneGrossHectares() - expPure.totalDirectRowAcquisitionHectares(),
                expPure.constructionZoneNetRingHectares(),
                0.05);

        // Environmental buffer (250 m half-width -> 500 m total): 10,000 x 500 + pi * 250^2 = 519.63 ha
        assertEquals(519.63, expPure.environmentalBufferGrossHectares(), 1.00);

        // Full network with 2.0 km spur (30 m RoW) and 150 m radius interchange at midpoint
        RightOfWayCorridorEngine.CorridorFootprint expWithSpurAndIc =
                engine.buildCorridorFootprint(
                        mainline,
                        FacilityMode.EXPRESSWAY,
                        List.of(spurPath),
                        List.of(interchangePt));

        assertEquals(6.07, expWithSpurAndIc.feederSpursRowAreaHectares(), 0.15);
        double rawInterchangeCircleHa = (Math.PI * 150.0 * 150.0) / 10_000.0; // ~7.07 ha
        // Because the interchange overlaps both the 60m mainline and 30m spur, net additional area is strictly smaller
        assertTrue(expWithSpurAndIc.interchangeLoopsAdditionalHectares() > 4.0);
        assertTrue(expWithSpurAndIc.interchangeLoopsAdditionalHectares() < rawInterchangeCircleHa);

        double naiveSumHa = expWithSpurAndIc.mainlineRowAreaHectares()
                + expWithSpurAndIc.feederSpursRowAreaHectares()
                + rawInterchangeCircleHa;
        assertTrue(
                expWithSpurAndIc.totalDirectRowAcquisitionHectares() < naiveSumHa,
                "JTS UnaryUnionOp must deduplicate overlapping mainline, spur, and interchange RoW areas");

        System.out.println("\n=== JTS METRIC BUFFERING & UNION DEDUPLICATION VALIDATION ===");
        System.out.printf("10 km Expressway (60m RoW) Mainline Area      : %.2f ha%n", expPure.mainlineRowAreaHectares());
        System.out.printf("10 km National Highway (45m RoW) Mainline Area: %.2f ha%n", nhPure.mainlineRowAreaHectares());
        System.out.printf("2 km Feeder Spur (30m RoW) Area               : %.2f ha%n", expWithSpurAndIc.feederSpursRowAreaHectares());
        System.out.printf("Interchange Net Additional Area (150m radius) : %.2f ha (vs %.2f ha raw circle)%n",
                expWithSpurAndIc.interchangeLoopsAdditionalHectares(), rawInterchangeCircleHa);
        System.out.printf("Total Deduplicated Direct RoW Acquisition     : %.2f ha (vs %.2f ha naive sum)%n",
                expWithSpurAndIc.totalDirectRowAcquisitionHectares(), naiveSumHa);
    }

    @Test
    void validateGeneralPurposeVectorOverlayForPointsLinesAndPolygons() {
        RightOfWayCorridorEngine engine = new RightOfWayCorridorEngine(projection, new RowCorridorProperties());

        MetricPoint mStart = projection.forward(new GeoPoint(26.20000, 93.40000));
        MetricPoint mEnd = new MetricPoint(mStart.x() + 4_000.0, mStart.y());
        MetricPoint mMid = new MetricPoint(mStart.x() + 2_000.0, mStart.y());

        List<GeoPoint> mainline = List.of(
                projection.inverse(mStart),
                projection.inverse(mEnd));

        // Expressway mode: Direct RoW half-width = 30m, Construction half-width = 100m, Env half-width = 250m
        RightOfWayCorridorEngine.CorridorFootprint corridor =
                engine.buildCorridorFootprint(mainline, FacilityMode.EXPRESSWAY);

        // 1. Point Overlay Validation (Buildings / Facilities at exact lateral offsets from centerline)
        GeoPoint ptInsideRow = projection.inverse(new MetricPoint(mMid.x(), mMid.y() + 12.0));      // 12m offset -> inside 30m half-width
        GeoPoint ptInConstruct = projection.inverse(new MetricPoint(mMid.x(), mMid.y() + 65.0));    // 65m offset -> inside 100m construction zone
        GeoPoint ptInEnvBuffer = projection.inverse(new MetricPoint(mMid.x(), mMid.y() - 180.0));   // 180m offset -> inside 250m env buffer
        GeoPoint ptOutside = projection.inverse(new MetricPoint(mMid.x(), mMid.y() + 400.0));       // 400m offset -> outside corridor

        List<RightOfWayCorridorEngine.SpatialPointFeature> testPoints = List.of(
                new RightOfWayCorridorEngine.SpatialPointFeature("BLD-1", "Village House", "RESIDENTIAL", ptInsideRow, Map.of()),
                new RightOfWayCorridorEngine.SpatialPointFeature("SCH-1", "Primary School", "SCHOOL", ptInConstruct, Map.of()),
                new RightOfWayCorridorEngine.SpatialPointFeature("HSP-1", "Rural Clinic", "HEALTHCARE", ptInEnvBuffer, Map.of()),
                new RightOfWayCorridorEngine.SpatialPointFeature("TMP-1", "Distant Temple", "HERITAGE", ptOutside, Map.of()));

        RightOfWayCorridorEngine.PointOverlayReport ptReport = engine.intersectPoints(corridor, testPoints);
        assertEquals(4, ptReport.totalFeaturesEvaluated());
        assertEquals(1, ptReport.directRowHitCount());
        assertEquals(1, ptReport.constructionZoneRingHitCount());
        assertEquals(1, ptReport.environmentalBufferRingHitCount());
        assertEquals(3, ptReport.affectedPoints().size());

        RightOfWayCorridorEngine.PointIntersectionResult hitRow = ptReport.affectedPoints().get(0);
        assertEquals("BLD-1", hitRow.id());
        assertEquals(RowImpactZone.DIRECT_ROW_FOOTPRINT, hitRow.impactZone());
        assertEquals(12.0, hitRow.distanceToCenterlineMeters(), 0.2);
        assertEquals(-18.0, hitRow.signedDistanceToRowEdgeMeters(), 0.3);

        RightOfWayCorridorEngine.PointIntersectionResult hitConst = ptReport.affectedPoints().get(1);
        assertEquals("SCH-1", hitConst.id());
        assertEquals(RowImpactZone.CONSTRUCTION_CLEARANCE_ZONE, hitConst.impactZone());
        assertEquals(35.0, hitConst.signedDistanceToRowEdgeMeters(), 0.3);

        // 2. Line Overlay Validation (Perpendicular Stream crossing the 60m RoW / 200m Construction / 500m Env Buffer)
        GeoPoint streamSouth = projection.inverse(new MetricPoint(mMid.x(), mMid.y() - 400.0));
        GeoPoint streamNorth = projection.inverse(new MetricPoint(mMid.x(), mMid.y() + 400.0));
        List<RightOfWayCorridorEngine.SpatialLineFeature> testLines = List.of(
                new RightOfWayCorridorEngine.SpatialLineFeature(
                        "STR-1", "Seasonal Drainage Channel", "SEASONAL_STREAM",
                        List.of(streamSouth, streamNorth), Map.of()));

        List<RightOfWayCorridorEngine.LineIntersectionResult> lineHits = engine.intersectLines(corridor, testLines);
        assertEquals(1, lineHits.size());
        assertEquals(60.0, lineHits.get(0).lengthInDirectRowMeters(), 0.5);
        assertEquals(200.0, lineHits.get(0).lengthInConstructionZoneMeters(), 1.0);
        assertEquals(500.0, lineHits.get(0).lengthInEnvironmentalBufferMeters(), 1.5);

        // 3. Polygon Overlay Validation (100m x 100m = 1.0 ha parcel centered on the alignment)
        List<GeoPoint> parcelRing = List.of(
                projection.inverse(new MetricPoint(mMid.x() - 50.0, mMid.y() - 50.0)),
                projection.inverse(new MetricPoint(mMid.x() + 50.0, mMid.y() - 50.0)),
                projection.inverse(new MetricPoint(mMid.x() + 50.0, mMid.y() + 50.0)),
                projection.inverse(new MetricPoint(mMid.x() - 50.0, mMid.y() + 50.0)),
                projection.inverse(new MetricPoint(mMid.x() - 50.0, mMid.y() - 50.0)));

        List<RightOfWayCorridorEngine.PolygonIntersectionResult> polyHits = engine.intersectPolygons(
                corridor,
                List.of(RightOfWayCorridorEngine.SpatialPolygonFeature.fromRing(
                        "PRC-1", "Agricultural Plot", "IRRIGATED_CROPLAND", parcelRing, Map.of())));

        assertEquals(1, polyHits.size());
        assertEquals(1.00, polyHits.get(0).totalFeatureAreaHectares(), 0.02);
        // 100m length x 60m RoW width = 6,000 m^2 = 0.60 ha (60% of 1.0 ha plot)
        assertEquals(0.60, polyHits.get(0).areaInDirectRowHectares(), 0.02);
        assertEquals(60.0, polyHits.get(0).percentOfFeatureInDirectRow(), 1.0);
        assertEquals(1.00, polyHits.get(0).areaInConstructionZoneHectares(), 0.02);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateFull10LayerNetworkRightOfWaySwathAuditAndGeoJsonPolygons() throws Exception {
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

        RightOfWayCorridorEngine rowEngine = new RightOfWayCorridorEngine(
                projection, new RowCorridorProperties());

        CompositeRouteMetricsService metricsService = new CompositeRouteMetricsService(
                projection, lulcProvider, lulcProps, floodCache, floodProps,
                slopeProvider, slopeProps, paProvider, settlementProvider, settlementProps,
                transportRaster, transportIndex, transportProps,
                waterRaster, riverIndex, waterProps,
                wildlifeRaster, wildlifeIndex, wildlifeProps,
                geotechRaster, geotechIndex, geotechProps,
                utilityRaster, utilityIndex, utilityProps,
                rowEngine
        );

        StudyRegion corridor = corridorWindow(fullRegion, fullRegion.origin(), fullRegion.destination(), 0.15);
        HubEvaluationService hubService = new HubEvaluationService(transportIndex, projection, transportProps);
        HighwayNetworkPlannerService plannerService = new HighwayNetworkPlannerService(
                transportIndex, transportRaster, slopeProvider, paProvider,
                router, projection, transportProps,
                waterRaster, riverIndex, waterProps,
                wildlifeRaster, wildlifeIndex, wildlifeProps,
                geotechRaster, geotechIndex, geotechProps,
                utilityRaster, utilityIndex, utilityProps,
                rowEngine
        );

        List<CostLayer> spurAll10NonTransportLayers = List.of(
                lulcLayer, floodLayer, slopeLayer, paLayer,
                settlementLayer, waterLayer, wildlifeLayer, geotechLayer, utilityLayer
        );

        System.out.println("\n=== FULL 10-LAYER NETWORK 2D RIGHT-OF-WAY (RoW) CORRIDOR SUMMARY ===");
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

            List<GeoPoint> mainline = router.route(
                    fullRegion, fullRegion.origin(), fullRegion.destination(),
                    new CompositeCostSurface(List.of(
                            lulcLayer, floodLayer, slopeLayer, paLayer,
                            settlementLayer, modeTransportLayer, waterLayer, wildlifeLayer, geotechLayer, utilityLayer)).withDenseColonyLastResortMode(),
                    250.0, corridor
            );

            HighwayNetworkPlannerService.NetworkPlanResult plan =
                    plannerService.planNetwork(fullRegion, mainline, hubEval, spurAll10NonTransportLayers);

            RightOfWayCorridorEngine.CorridorFootprint rowFp = plan.rowCorridorFootprint();
            assertNotNull(rowFp);
            assertEquals(mode == FacilityMode.EXPRESSWAY ? 60.0 : 45.0, rowFp.mainlineRowWidthMeters(), 1e-6);
            assertTrue(rowFp.mainlineRowAreaHectares() > 350.0);
            assertTrue(rowFp.totalDirectRowAcquisitionHectares() >= rowFp.mainlineRowAreaHectares());

            Map<String, Object> diag = metricsService.summarize(mainline, mode);
            Map<String, Object> rowSummary = (Map<String, Object>) diag.get("rightOfWayCorridorSummary");
            assertNotNull(rowSummary);
            assertEquals(mode.name(), rowSummary.get("activeFacilityMode"));

            int crossSections = ((Number) rowSummary.get("crossSectionsEvaluated")).intValue();
            assertTrue(crossSections > 600);
            assertEquals(5, ((Number) rowSummary.get("lateralPointsPerCrossSection")).intValue());

            Map<String, Double> lulcHa = (Map<String, Double>) rowSummary.get("lulcAcquisitionHectares");
            assertFalse(lulcHa.isEmpty());
            double sumLulcHa = lulcHa.values().stream().mapToDouble(Double::doubleValue).sum();
            assertEquals(rowFp.mainlineRowAreaHectares(), sumLulcHa, 2.0);

            List<Map<String, Object>> features = plannerService.buildGeoJsonFeatures(mainline, Map.of(), plan);
            long mainlineRowPolygons = features.stream()
                    .filter(f -> "MAINLINE_ROW_CORRIDOR".equals(
                            ((Map<String, Object>) f.get("properties")).get("featureRole")))
                    .count();
            long spurRowPolygons = features.stream()
                    .filter(f -> "FEEDER_SPUR_ROW_CORRIDOR".equals(
                            ((Map<String, Object>) f.get("properties")).get("featureRole")))
                    .count();
            assertEquals(1L, mainlineRowPolygons);
            assertEquals(plan.feederSpurs().size(), spurRowPolygons);

            System.out.printf(
                    "[%s] Mainline: %.2f km | RoW Width: %.0f m (%s) | Mainline RoW: %.2f ha | Spurs RoW: %.2f ha | Interchanges Add'l: %.2f ha | Total Direct RoW: %.2f ha | Construction Zone: %.2f ha | Env Buffer: %.2f ha%n",
                    mode.name(),
                    plan.mainlineLengthKm(),
                    rowFp.mainlineRowWidthMeters(),
                    rowFp.ircStandard(),
                    rowFp.mainlineRowAreaHectares(),
                    rowFp.feederSpursRowAreaHectares(),
                    rowFp.interchangeLoopsAdditionalHectares(),
                    rowFp.totalDirectRowAcquisitionHectares(),
                    rowFp.constructionZoneGrossHectares(),
                    rowFp.environmentalBufferGrossHectares());
            System.out.printf("   LULC Acquisition Breakdown (ha): %s%n", lulcHa);
            System.out.printf("   Settlement Exposure (ha)       : %s%n", rowSummary.get("settlementExposureHectares"));
            System.out.printf("   Wildlife Zone Exposure (ha)    : %s%n", rowSummary.get("wildlifeZoneHectares"));
            System.out.printf("   Utility Assets in Corridor     : %s%n", rowSummary.get("utilityAssetsInCorridor"));
            System.out.printf("   RoW Boundary Hard Audit        : %s%n", rowSummary.get("rowBoundaryHardConstraintAudit"));
        }
    }

    private static StudyRegion corridorWindow(StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg);
        return new StudyRegion(full.name() + " / RoW validation window", minLat, minLon, maxLat, maxLon, a, b);
    }
}
