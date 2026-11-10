package in.marg.routing;

import in.marg.bhuvan.BhuvanCatalog;
import in.marg.bhuvan.BhuvanFeatureInfoClient;
import in.marg.bhuvan.BhuvanLulcSampler;
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
import in.marg.utility.UtilityCorridorTier;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilityLayerValidationTest {

    private final CoordinateProjection projection = new Wgs84UtmProjection(46);
    private final GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
    private final StudyRegion fullRegion = new StudyRegion(
            "Kaziranga–Karbi Anglong, Assam",
            25.75, 93.10, 26.65, 93.75,
            new GeoPoint(26.58970, 93.40035),
            new GeoPoint(25.84573, 93.43781)
    );

    @Test
    void validateIndependentUtilityRasterSamplingAndSkewProtection() {
        UtilityRasterProvider utilityRaster = new UtilityRasterProvider(
                "data/processed/utilities/assam_utility_corridors_utm46.tif"
        );
        UtilityNetworkVectorIndex utilityIndex = new UtilityNetworkVectorIndex(
                projection,
                "data/processed/utilities/assam_power_transmission_lines.geojson",
                "data/processed/utilities/assam_utility_assets.geojson"
        );
        UtilityRoutingProperties props = new UtilityRoutingProperties();
        UtilityCorridorCostLayer utilityLayer = new UtilityCorridorCostLayer(
                utilityRaster, utilityIndex, projection, props
        );

        System.out.println("=== 1. UTILITY RASTER & VECTOR INDEX VALIDATION ===");
        assertEquals(19, utilityIndex.allLines().size());
        assertEquals(1341, utilityIndex.allAssets().size());
        assertEquals(1333, utilityIndex.allTowers().size());
        long substationCount = utilityIndex.allAssets().stream()
                .filter(a -> "SUBSTATION".equals(a.assetType()))
                .count();
        assertEquals(8, substationCount);

        // Check origin & destination & hub endpoints are NONE
        assertEquals(UtilityCorridorClass.NONE, utilityRaster.sampleClass(26.58970, 93.40035)); // Kohora origin
        assertEquals(UtilityCorridorClass.NONE, utilityRaster.sampleClass(25.84573, 93.43781)); // Diphu destination

        // Substation footprint test (Dimapur 220kV substation)
        assertEquals(UtilityCorridorClass.SUBSTATION_OR_TOWER_FOOTPRINT,
                utilityRaster.sampleClass(25.88558, 93.74266));

        // Transmission tower footprint test (TWR-2136301491 at 26.5773281, 93.2877605)
        assertEquals(UtilityCorridorClass.SUBSTATION_OR_TOWER_FOOTPRINT,
                utilityRaster.sampleClass(26.57733, 93.28776));

        // 400 kV EHV RoW sample
        assertEquals(UtilityCorridorClass.EHV_400KV_ROW,
                utilityRaster.sampleClass(25.98240, 93.42399));

        // 220 kV EHV RoW sample
        assertEquals(UtilityCorridorClass.EHV_220KV_ROW,
                utilityRaster.sampleClass(25.97011, 93.42419));

        // Setback zone sample
        assertEquals(UtilityCorridorClass.UTILITY_PROXIMITY_SETBACK_ZONE,
                utilityRaster.sampleClass(25.98054, 93.60802));

        // Verify interchange exclusion logic
        assertTrue(UtilityCorridorClass.SUBSTATION_OR_TOWER_FOOTPRINT.isInterchangeExcludedZone());
        assertTrue(UtilityCorridorClass.EHV_400KV_ROW.isInterchangeExcludedZone());
        assertTrue(UtilityCorridorClass.EHV_220KV_ROW.isInterchangeExcludedZone());
        assertFalse(UtilityCorridorClass.SUBTRANSMISSION_132KV_ROW.isInterchangeExcludedZone());
        assertFalse(UtilityCorridorClass.DISTRIBUTION_LINE_ROW.isInterchangeExcludedZone());
        assertFalse(UtilityCorridorClass.UTILITY_PROXIMITY_SETBACK_ZONE.isInterchangeExcludedZone());
        assertFalse(UtilityCorridorClass.NONE.isInterchangeExcludedZone());

        // Verify soft penalty vs hard blocking toggle for substation/tower
        GeoPoint towerPt = new GeoPoint(26.57733, 93.28776);
        CostAssessment softAssessment = utilityLayer.assess(towerPt);
        assertFalse(softAssessment.blocked());
        assertEquals(4.50, softAssessment.penaltyMultiplier(), 1e-6);

        UtilityRoutingProperties strictProps = new UtilityRoutingProperties();
        strictProps.setBlockSubstationsAndTowers(true);
        UtilityCorridorCostLayer strictLayer = new UtilityCorridorCostLayer(
                utilityRaster, utilityIndex, projection, strictProps
        );
        CostAssessment strictAssessment = strictLayer.assess(towerPt);
        assertTrue(strictAssessment.blocked(),
                "Substations and towers must be hard-blocked when blockSubstationsAndTowers=true");

        // 2. Crossing angle skew multiplier and tower proximity validation across 400kV corridor
        var line400 = utilityIndex.allLines().stream()
                .filter(l -> l.tier() == UtilityCorridorTier.EHV_400KV_TRANSMISSION)
                .findFirst().orElseThrow();
        assertEquals(UtilityCorridorTier.EHV_400KV_TRANSMISSION, line400.tier());
        assertEquals(400, line400.voltageKv());

        int midIdx = line400.points().size() / 2;
        GeoPoint p1 = line400.points().get(midIdx);
        GeoPoint p2 = line400.points().get(midIdx + 1);
        MetricPoint m1 = projection.forward(p1);
        MetricPoint m2 = projection.forward(p2);
        double ldx = m2.x() - m1.x();
        double ldy = m2.y() - m1.y();
        double llen = Math.hypot(ldx, ldy);
        double tx = ldx / llen;
        double ty = ldy / llen;
        double nx = -ty;
        double ny = tx;
        double midX = 0.5 * (m1.x() + m2.x());
        double midY = 0.5 * (m1.y() + m2.y());

        // Exactly perpendicular crossing (90 degrees, sin = 1.0)
        MetricPoint perpStartMetric = new MetricPoint(midX - 250.0 * nx, midY - 250.0 * ny);
        MetricPoint perpEndMetric = new MetricPoint(midX + 250.0 * nx, midY + 250.0 * ny);
        GeoPoint perpStart = projection.inverse(perpStartMetric);
        GeoPoint perpEnd = projection.inverse(perpEndMetric);

        var perpCrossings = utilityIndex.findStepCrossings(perpStart, perpEnd);
        assertEquals(1, perpCrossings.size());
        var perpCross = perpCrossings.get(0);
        assertEquals(UtilityCorridorTier.EHV_400KV_TRANSMISSION, perpCross.tier());
        assertEquals(90.0, perpCross.crossingAngleDegrees(), 0.5);
        assertEquals(46.0, perpCross.recommendedProtectiveSpanMeters(), 0.5);

        // Acute 15-degree crossing dragging highway under conductors
        double acuteRad = Math.toRadians(15.0);
        double axDir = Math.cos(acuteRad) * tx + Math.sin(acuteRad) * nx;
        double ayDir = Math.cos(acuteRad) * ty + Math.sin(acuteRad) * ny;
        MetricPoint acuteStartMetric = new MetricPoint(midX - 350.0 * axDir, midY - 350.0 * ayDir);
        MetricPoint acuteEndMetric = new MetricPoint(midX + 350.0 * axDir, midY + 350.0 * ayDir);
        GeoPoint acuteStart = projection.inverse(acuteStartMetric);
        GeoPoint acuteEnd = projection.inverse(acuteEndMetric);

        var acuteCrossings = utilityIndex.findStepCrossings(acuteStart, acuteEnd);
        assertEquals(1, acuteCrossings.size());
        var acuteCross = acuteCrossings.get(0);
        assertEquals(15.0, acuteCross.crossingAngleDegrees(), 0.5);
        assertTrue(acuteCross.recommendedProtectiveSpanMeters() > 140.0,
                "Acute 15-deg crossing must require >140 m protective cradle span");

        double perpStepKm = projection.distanceMeters(perpStart, perpEnd) / 1000.0;
        double acuteStepKm = projection.distanceMeters(acuteStart, acuteEnd) / 1000.0;
        double perpCrossingCostKmEq = utilityLayer.assessEdge(perpStart, perpEnd).penaltyMultiplier() * perpStepKm;
        double acuteCrossingCostKmEq = utilityLayer.assessEdge(acuteStart, acuteEnd).penaltyMultiplier() * acuteStepKm;

        System.out.printf("400kV Exact 90-deg Traverse : angle=%.1f deg | span=%.1f m | cost=%.2f km-eq%n",
                perpCross.crossingAngleDegrees(), perpCross.recommendedProtectiveSpanMeters(), perpCrossingCostKmEq);
        System.out.printf("400kV Acute 15-deg Traverse : angle=%.1f deg | span=%.1f m | cost=%.2f km-eq%n",
                acuteCross.crossingAngleDegrees(), acuteCross.recommendedProtectiveSpanMeters(), acuteCrossingCostKmEq);

        assertTrue(acuteCrossingCostKmEq > perpCrossingCostKmEq * 2.0,
                "Acute transmission line crossing must incur >2.0x higher crossing protection cost km-eq");

        // 3. Longitudinal parallel RoW alignment without crossing
        MetricPoint parStartMetric = new MetricPoint(midX + 50.0 * nx - 200.0 * tx, midY + 50.0 * ny - 200.0 * ty);
        MetricPoint parEndMetric = new MetricPoint(midX + 50.0 * nx + 200.0 * tx, midY + 50.0 * ny + 200.0 * ty);
        GeoPoint parA = projection.inverse(parStartMetric);
        GeoPoint parB = projection.inverse(parEndMetric);
        assertTrue(utilityIndex.findStepCrossings(parA, parB).isEmpty(),
                "Parallel corridor step should not intersect line centerline");
        CostAssessment parEdge = utilityLayer.assessEdge(parA, parB);
        assertTrue(parEdge.penaltyMultiplier() > 1.5,
                "Running longitudinally parallel directly inside transmission line safety corridor must incur parallel RoW penalty");
    }

    @Test
    void validateIsolatedUtilityRoutingAndFullComposite9Vs10LayerAcrossModes() throws Exception {
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

        // Load real cached LULC, Flood, Slope, Protected Area, and Settlement layers
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
                utilityRaster, utilityIndex, utilityProps
        );

        StudyRegion corridor = corridorWindow(fullRegion, fullRegion.origin(), fullRegion.destination(), 0.15);

        // === 2. ISOLATED UTILITY ROUTING (DISTANCE_BASELINE vs UTILITY_ONLY) ===
        System.out.println("\n=== 2. ISOLATED UTILITY ROUTING (DISTANCE_BASELINE vs UTILITY_ONLY) ===");
        List<GeoPoint> distBaseline = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new DistanceOnlyCostSurface(), 250.0, corridor
        );
        List<GeoPoint> utilityOnly = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(utilityLayer)), 250.0, corridor
        );

        Map<String, Object> distDiag = metricsService.summarize(distBaseline);
        Map<String, Object> utilOnlyDiag = metricsService.summarize(utilityOnly);
        @SuppressWarnings("unchecked")
        double distUtilPen = ((Map<String, Double>) distDiag.get("approximatePenaltyContributionKm"))
                .get("UTILITY_CORRIDORS");
        @SuppressWarnings("unchecked")
        double isolatedUtilPen = ((Map<String, Double>) utilOnlyDiag.get("approximatePenaltyContributionKm"))
                .get("UTILITY_CORRIDORS");

        System.out.println("DISTANCE_BASELINE Utility: len=" + distDiag.get("routeLengthKm")
                + " km | pen=" + distUtilPen + " km-eq | crossings=" + distDiag.get("utilityCrossingSummary")
                + " | exposure=" + distDiag.get("utilityCorridorExposureKm"));
        System.out.println("UTILITY_ONLY      Utility: len=" + utilOnlyDiag.get("routeLengthKm")
                + " km | pen=" + isolatedUtilPen + " km-eq | crossings=" + utilOnlyDiag.get("utilityCrossingSummary")
                + " | exposure=" + utilOnlyDiag.get("utilityCorridorExposureKm"));

        assertTrue(isolatedUtilPen < distUtilPen,
                "Isolated utility-aware routing must reduce transmission RoW, tower proximity & acute crossing penalties");

        // Verify strict blockSubstationsAndTowers=true routes cleanly with 0 substation/tower vertices
        UtilityRoutingProperties strictProps = new UtilityRoutingProperties();
        strictProps.setBlockSubstationsAndTowers(true);
        UtilityCorridorCostLayer strictLayer = new UtilityCorridorCostLayer(
                utilityRaster, utilityIndex, projection, strictProps
        );
        List<GeoPoint> strictUtilRoute = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(strictLayer)), 250.0, corridor
        );
        for (GeoPoint v : strictUtilRoute) {
            assertFalse(
                    utilityRaster.sampleClass(v.lat(), v.lon())
                            == UtilityCorridorClass.SUBSTATION_OR_TOWER_FOOTPRINT,
                    "Strict utility-blocked route must have 0 vertices on SUBSTATION_OR_TOWER_FOOTPRINT");
        }

        // === 3. FULL COMPOSITE 9-LAYER (BEFORE) vs 10-LAYER (AFTER: EXPRESSWAY & NATIONAL_HIGHWAY) ===
        System.out.println("\n=== 3. COMPOSITE 9-LAYER (BEFORE) vs 10-LAYER (AFTER) ===");
        HubEvaluationService hubService = new HubEvaluationService(transportIndex, projection, transportProps);
        HighwayNetworkPlannerService plannerService = new HighwayNetworkPlannerService(
                transportIndex, transportRaster, slopeProvider, paProvider,
                router, projection, transportProps,
                waterRaster, riverIndex, waterProps,
                wildlifeRaster, wildlifeIndex, wildlifeProps,
                geotechRaster, geotechIndex, geotechProps,
                utilityRaster, utilityIndex, utilityProps
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

            // 9-Layer (Before Utility Corridors)
            List<GeoPoint> route9 = router.route(
                    fullRegion, fullRegion.origin(), fullRegion.destination(),
                    new CompositeCostSurface(List.of(
                            lulcLayer, floodLayer, slopeLayer, paLayer,
                            settlementLayer, modeTransportLayer, waterLayer, wildlifeLayer, geotechLayer)),
                    250.0, corridor
            );
            Map<String, Object> diag9 = metricsService.summarize(route9);

            // 10-Layer (After High-Voltage Transmission & Utility Corridors)
            List<CostLayer> spurAll10NonTransportLayers = List.of(
                    lulcLayer, floodLayer, slopeLayer, paLayer,
                    settlementLayer, waterLayer, wildlifeLayer, geotechLayer, utilityLayer
            );
            List<GeoPoint> route10 = router.route(
                    fullRegion, fullRegion.origin(), fullRegion.destination(),
                    new CompositeCostSurface(List.of(
                            lulcLayer, floodLayer, slopeLayer, paLayer,
                            settlementLayer, modeTransportLayer, waterLayer, wildlifeLayer, geotechLayer, utilityLayer)),
                    250.0, corridor
            );
            Map<String, Object> diag10 = metricsService.summarize(route10);
            var plan10 = plannerService.planNetwork(
                    fullRegion, route10, hubEval, spurAll10NonTransportLayers
            );

            @SuppressWarnings("unchecked")
            double pen9 = ((Map<String, Double>) diag9.get("approximatePenaltyContributionKm"))
                    .get("UTILITY_CORRIDORS");
            @SuppressWarnings("unchecked")
            double pen10 = ((Map<String, Double>) diag10.get("approximatePenaltyContributionKm"))
                    .get("UTILITY_CORRIDORS");

            System.out.println("\n--- MODE: " + mode.name() + " ---");
            System.out.println("9-LAYER (BEFORE)  : len=" + diag9.get("routeLengthKm")
                    + " km | utilPen=" + pen9 + " km-eq | crossings=" + diag9.get("utilityCrossingSummary")
                    + " | utilExposure=" + diag9.get("utilityCorridorExposureKm")
                    + " | hc=" + diag9.get("hardConstraintValidation")
                    + " | allPenalties=" + diag9.get("approximatePenaltyContributionKm"));
            System.out.println("10-LAYER (AFTER)  : len=" + diag10.get("routeLengthKm")
                    + " km | utilPen=" + pen10 + " km-eq | crossings=" + diag10.get("utilityCrossingSummary")
                    + " | utilExposure=" + diag10.get("utilityCorridorExposureKm")
                    + " | hc=" + diag10.get("hardConstraintValidation")
                    + " | allPenalties=" + diag10.get("approximatePenaltyContributionKm"));
            System.out.println("10-LAYER UTILITY  : " + plan10.utilityCrossings().stream()
                    .map(u -> u.lineName() + " (" + u.tier() + ", span="
                            + u.recommendedProtectiveSpanMeters() + "m, angle="
                            + u.crossingAngleDegrees() + "deg @ ch=" + u.chainageKm() + "km, protection="
                            + u.protectionStructureType() + ")")
                    .toList());
            System.out.println("10-LAYER SPURS    : " + plan10.feederSpurs().stream()
                    .map(s -> s.hub().name() + ":" + s.spurLengthKm() + "km->" + s.hub().tieInRoadRef()).toList()
                    + " | V/C=" + plan10.projectedVcRatio() + " (" + plan10.levelOfService() + ")");

            assertTrue(pen10 <= pen9,
                    "10-Layer composite route in " + mode + " mode must reduce or match 9-Layer utility penalty");

            @SuppressWarnings("unchecked")
            Map<String, Object> hc10 = (Map<String, Object>) diag10.get("hardConstraintValidation");
            assertTrue(
                    ((Number) hc10.get("protectedAreaViolationSamples")).intValue() <= 1,
                    "10-Layer mainline sub-grid 125 m boundary diagnostic must not exceed the 1 corner pixel baseline");
            assertEquals(0, hc10.get("protectedAreaNoDataSamples"));
            assertEquals(0, hc10.get("slopeNoDataViolationSamples"));

            // Every 250 m routing vertex on the 10-Layer mainline must strictly satisfy PA==0 and Slope<=30 deg
            for (GeoPoint v : route10) {
                Double pa = paProvider.sampleOrNull(v.lat(), v.lon());
                Double slp = slopeProvider.sampleOrNull(v.lat(), v.lon());
                assertTrue(pa != null && Math.round(pa) == 0L,
                        "Mainline vertex must not enter Protected Area: " + v);
                assertTrue(slp != null && slp <= 30.0,
                        "Mainline vertex must satisfy Slope <= 30 deg: " + v + " (slope=" + slp + ")");
            }

            // Ensure no highway interchange sits on unsafe utility exclusion zones (EHV 400kV/220kV RoW or substations/towers)
            for (var ic : plan10.interchanges()) {
                UtilityCorridorClass icUtil = utilityRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(icUtil.isInterchangeExcludedZone(),
                        "Interchange " + ic.interchangeId() + " must never sit under EHV 400kV/220kV lines or on substations/towers: " + icUtil);

                GeotechHazardClass icGeo = geotechRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(icGeo.isInterchangeUnsafeSubgrade(),
                        "Interchange " + ic.interchangeId() + " must never sit on liquefiable marsh or active/high landslide scarp: " + icGeo);

                WildlifeZoneClass icWild = wildlifeRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(icWild.isInterchangeExcludedZone(),
                        "Interchange " + ic.interchangeId() + " must never sit inside ESZ inner buffer or wildlife corridor: " + icWild);

                WaterBodyClass icWater = waterRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(
                        icWater == WaterBodyClass.MAJOR_PERENNIAL_RIVER
                                || icWater == WaterBodyClass.MEDIUM_PERENNIAL_RIVER
                                || icWater == WaterBodyClass.MINOR_PERENNIAL_STREAM
                                || icWater == WaterBodyClass.INLAND_WETLAND_WATERBODY,
                        "Interchange " + ic.interchangeId() + " must never sit inside a river channel or wetland");
            }

            // Ensure all dedicated A* spurs pass vertex hard constraints & Protected Area checks and connect outside URBAN_CORE
            for (int i = 0; i < plan10.feederSpurs().size(); i++) {
                var ic = plan10.interchanges().get(i);
                var spur = plan10.feederSpurs().get(i);
                assertEquals(ic.point(), spur.path().get(0));
                assertEquals(spur.hub().bypassTieIn(), spur.path().get(spur.path().size() - 1));
                assertFalse(
                        settlementProvider.sampleClass(
                                spur.hub().bypassTieIn().lat(),
                                spur.hub().bypassTieIn().lon()) == SettlementExposureClass.URBAN_CORE);
                Map<String, Object> spurDiag = metricsService.summarize(spur.path());
                @SuppressWarnings("unchecked")
                Map<String, Object> spurHc = (Map<String, Object>) spurDiag.get("hardConstraintValidation");
                assertEquals(0, spurHc.get("protectedAreaViolationSamples"),
                        "10-Layer dedicated spur for " + spur.hub().name() + " must have 0 Protected Area violations");
                for (GeoPoint sv : spur.path()) {
                    Double pa = paProvider.sampleOrNull(sv.lat(), sv.lon());
                    Double slp = slopeProvider.sampleOrNull(sv.lat(), sv.lon());
                    assertTrue(pa != null && Math.round(pa) == 0L);
                    assertTrue(slp != null && slp <= 30.0);
                }
            }

            // Verify Multi-Feature GeoJSON output includes UTILITY_CROSSING_PROTECTION features matching planned crossings
            List<Map<String, Object>> geoJsonFeatures =
                    plannerService.buildGeoJsonFeatures(route10, Map.of("profile", "COMPOSITE_10_LAYER"), plan10);
            long utilityFeatures = geoJsonFeatures.stream()
                    .filter(f -> "UTILITY_CROSSING_PROTECTION".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                    .count();
            assertEquals(plan10.utilityCrossings().size(), utilityFeatures,
                    "GeoJSON FeatureCollection must emit a UTILITY_CROSSING_PROTECTION Point feature for every planned transmission line crossing");
        }
    }

    private static StudyRegion corridorWindow(StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg);
        return new StudyRegion(full.name() + " / utility validation window", minLat, minLon, maxLat, maxLon, a, b);
    }
}
