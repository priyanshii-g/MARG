package in.marg.routing;

import in.marg.bhuvan.BhuvanCatalog;
import in.marg.bhuvan.BhuvanFeatureInfoClient;
import in.marg.bhuvan.BhuvanLulcSampler;
import in.marg.config.FloodRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.SettlementRoutingProperties;
import in.marg.config.SlopeRoutingProperties;
import in.marg.config.TransportRoutingProperties;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.floodraster.BhuvanFloodRasterCache;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.provider.BhuvanLulcProvider;
import in.marg.provider.LulcProvider;
import in.marg.settlement.SettlementExposureClass;
import in.marg.settlement.SettlementRasterProvider;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.Wgs84UtmProjection;
import in.marg.terrain.SlopeRasterProvider;
import in.marg.transport.FacilityMode;
import in.marg.transport.HighwayNetworkPlannerService;
import in.marg.transport.HubEvaluationService;
import in.marg.transport.TransportCategory;
import in.marg.transport.TransportCorridorClass;
import in.marg.transport.TransportNetworkRasterProvider;
import in.marg.transport.TransportNetworkVectorIndex;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportLayerValidationTest {

    private final CoordinateProjection projection = new Wgs84UtmProjection(46);
    private final GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
    private final StudyRegion fullRegion = new StudyRegion(
            "Kaziranga–Karbi Anglong, Assam",
            25.75, 93.10, 26.65, 93.75,
            new GeoPoint(26.58970, 93.40035),
            new GeoPoint(25.84573, 93.43781)
    );

    @Test
    void validateIndependentRasterSamplingAndCrossingAnglePenalties() {
        TransportNetworkRasterProvider rasterProvider = new TransportNetworkRasterProvider(
                "data/processed/transport/assam_transport_network_utm46.tif"
        );
        TransportNetworkVectorIndex vectorIndex = new TransportNetworkVectorIndex(
                projection,
                "data/processed/transport/assam_transport_network.geojson",
                "data/processed/transport/assam_regional_hubs.geojson"
        );
        TransportRoutingProperties props = new TransportRoutingProperties();
        TransportCostPolicy policy = props.toPolicy();
        TransportNetworkCostLayer layer = new TransportNetworkCostLayer(
                rasterProvider, vectorIndex, projection, policy
        );

        System.out.println("=== 1. TRANSPORT RASTER & VECTOR POINT/CROSSING VALIDATION ===");
        assertTrue(vectorIndex.allCorridors().size() >= 12, "Corridors must include baseline plus real OSM network");
        assertEquals(14, vectorIndex.allHubs().size());

        // Raster point samples across categories
        assertEquals(TransportCorridorClass.NH_CORRIDOR,
                rasterProvider.sampleClass(26.57785, 93.28562)); // NH-715 (OSM real centerline)
        assertEquals(TransportCorridorClass.RAILWAY_CORRIDOR,
                rasterProvider.sampleClass(25.77056, 93.38327)); // NFR-MAIN-01 (OSM real centerline)
        assertEquals(TransportCorridorClass.SH_CORRIDOR,
                rasterProvider.sampleClass(26.35292, 93.35636)); // SH-36 / SH-017 (OSM real centerline)
        assertEquals(TransportCorridorClass.MDR_CORRIDOR,
                rasterProvider.sampleClass(26.54876, 93.47414)); // MDR-101 (OSM real centerline)
        assertEquals(TransportCorridorClass.NONE,
                rasterProvider.sampleClass(26.3000, 93.3400)); // Unroaded hill interior

        // Verify 90-degree perpendicular crossing vs acute ~18-degree crossing across NH-715 (east-west at lat 26.58636)
        GeoPoint northPerp = new GeoPoint(26.5884, 93.3511);
        GeoPoint southPerp = new GeoPoint(26.5844, 93.3511);
        var perpCrossings = vectorIndex.findStepCrossings(northPerp, southPerp);
        assertEquals(1, perpCrossings.size());
        assertEquals(TransportCategory.NATIONAL_HIGHWAY, perpCrossings.get(0).category());
        assertTrue(perpCrossings.get(0).crossingAngleDegrees() > 85.0,
                "North-south crossing of east-west NH-715 should be ~90 degrees");

        GeoPoint acuteStart = new GeoPoint(26.5874, 93.3461);
        GeoPoint acuteEnd = new GeoPoint(26.5854, 93.3561);
        var acuteCrossings = vectorIndex.findStepCrossings(acuteStart, acuteEnd);
        assertEquals(1, acuteCrossings.size());
        assertTrue(acuteCrossings.get(0).crossingAngleDegrees() < 20.0,
                "Shallow diagonal crossing of NH-715 should have acute angle < 20 degrees");

        double perpStepKm = projection.distanceMeters(northPerp, southPerp) / 1000.0;
        double acuteStepKm = projection.distanceMeters(acuteStart, acuteEnd) / 1000.0;
        double perpBridgeCostKmEq = layer.assessEdge(northPerp, southPerp).penaltyMultiplier() * perpStepKm;
        double acuteBridgeCostKmEq = layer.assessEdge(acuteStart, acuteEnd).penaltyMultiplier() * acuteStepKm;

        System.out.printf("NH-715 Perpendicular Crossing (angle=%.1f deg) -> cost=%.2f km-eq%n",
                perpCrossings.get(0).crossingAngleDegrees(), perpBridgeCostKmEq);
        System.out.printf("NH-715 Acute Skew Crossing    (angle=%.1f deg) -> cost=%.2f km-eq%n",
                acuteCrossings.get(0).crossingAngleDegrees(), acuteBridgeCostKmEq);

        assertTrue(acuteBridgeCostKmEq > perpBridgeCostKmEq * 2.5,
                "Acute skew crossing must be heavily penalized (>2.5x) relative to 90-deg crossing");
    }

    @Test
    void validateThreeTierHubEvaluationPipelineAndSafetyGates() {
        TransportNetworkVectorIndex vectorIndex = new TransportNetworkVectorIndex(
                projection,
                "data/processed/transport/assam_transport_network.geojson",
                "data/processed/transport/assam_regional_hubs.geojson"
        );
        TransportRoutingProperties props = new TransportRoutingProperties();
        HubEvaluationService hubService = new HubEvaluationService(vectorIndex, projection, props);

        StudyRegion corridor = corridorWindow(fullRegion, fullRegion.origin(), fullRegion.destination(), 0.15);

        // 1. EXPRESSWAY autonomous evaluation
        var expEval = hubService.evaluate(
                fullRegion.origin(), fullRegion.destination(), corridor,
                FacilityMode.EXPRESSWAY, List.of(), null, null
        );
        System.out.println("\n=== 2A. HUB PIPELINE: EXPRESSWAY MODE ===");
        System.out.println("Approved Hubs: " + expEval.approvedHubs().stream().map(h -> h.name()).toList()
                + " | V/C=" + expEval.projectedVcRatio() + " (" + expEval.levelOfService() + ")");
        for (var d : expEval.decisions()) {
            System.out.printf("  %-12s pop=%-6d HVI=%-8.1f status=%-32s V/C=%.2f%n",
                    d.hub().name(), d.hub().catchmentPopulation(), d.hvi(), d.status(), d.projectedVcRatioAtEvaluation());
        }
        assertFalse(expEval.overCapacityWarning());
        assertTrue(expEval.projectedVcRatio() <= 0.75);
        assertTrue(expEval.approvedHubs().stream().allMatch(h -> h.catchmentPopulation() >= 40000),
                "Expressway autonomous hubs must all meet >= 40,000 catchment population threshold");

        // 2. NATIONAL_HIGHWAY autonomous evaluation (checks V/C <= 0.75 gate stopping over-trafficking!)
        var nhEval = hubService.evaluate(
                fullRegion.origin(), fullRegion.destination(), corridor,
                FacilityMode.NATIONAL_HIGHWAY, List.of(), null, null
        );
        System.out.println("\n=== 2B. HUB PIPELINE: NATIONAL_HIGHWAY MODE ===");
        System.out.println("Approved Hubs: " + nhEval.approvedHubs().stream().map(h -> h.name()).toList()
                + " | V/C=" + nhEval.projectedVcRatio() + " (" + nhEval.levelOfService() + ")");
        for (var d : nhEval.decisions()) {
            System.out.printf("  %-12s pop=%-6d HVI=%-8.1f status=%-32s V/C=%.2f%n",
                    d.hub().name(), d.hub().catchmentPopulation(), d.hvi(), d.status(), d.projectedVcRatioAtEvaluation());
        }
        assertFalse(nhEval.overCapacityWarning());
        assertTrue(nhEval.projectedVcRatio() <= 0.75,
                "Carrying Capacity Gate must prevent autonomous hubs from exceeding V/C = 0.75");
        assertTrue(nhEval.decisions().stream().anyMatch(d -> "SPACING_GATE_REJECTED".equals(d.status())),
                "Spacing Gate must actively reject hubs violating the 8 km National Highway spacing threshold");
        assertTrue(nhEval.decisions().stream().anyMatch(d -> "CAPACITY_VC_GATE_REJECTED".equals(d.status())),
                "Carrying Capacity Gate must actively reject excess candidate hubs once V/C approaches 0.75");

        // 3. USER IMMUTABLE OVERRIDES (forcing overload to verify immutable lock-in & over-capacity warning)
        var overrideEval = hubService.evaluate(
                fullRegion.origin(), fullRegion.destination(), corridor,
                FacilityMode.NATIONAL_HIGHWAY,
                List.of("Howraghat", "Dokmoka", "Silonijan", "Dillai", "Bokakhat"),
                null, null
        );
        System.out.println("\n=== 2C. HUB PIPELINE: USER IMMUTABLE OVERRIDES ===");
        System.out.println("Approved Hubs: " + overrideEval.approvedHubs().stream().map(h -> h.name()).toList()
                + " | V/C=" + overrideEval.projectedVcRatio() + " (" + overrideEval.levelOfService()
                + ") | overCapacityWarning=" + overrideEval.overCapacityWarning());
        assertEquals(5, overrideEval.approvedHubs().size());
        assertTrue(overrideEval.overCapacityWarning(),
                "Forcing 5 major hubs must trigger overCapacityWarning while respecting immutable user overrides");
    }

    @Test
    void validateIsolatedAndFull6LayerCompositeWithDedicatedFeederSpurs() throws Exception {
        TransportNetworkRasterProvider transportRaster = new TransportNetworkRasterProvider(
                "data/processed/transport/assam_transport_network_utm46.tif"
        );
        TransportNetworkVectorIndex vectorIndex = new TransportNetworkVectorIndex(
                projection,
                "data/processed/transport/assam_transport_network.geojson",
                "data/processed/transport/assam_regional_hubs.geojson"
        );
        TransportRoutingProperties transportProps = new TransportRoutingProperties();
        TransportNetworkCostLayer isolatedTransportLayer = new TransportNetworkCostLayer(
                transportRaster, vectorIndex, projection, transportProps.toPolicy()
        );

        StudyRegion corridor = corridorWindow(fullRegion, fullRegion.origin(), fullRegion.destination(), 0.15);

        // === 3. ISOLATED TRANSPORT LAYER ROUTING ===
        System.out.println("\n=== 3. ISOLATED TRANSPORT ROUTING (DISTANCE vs TRANSPORT_ONLY) ===");
        List<GeoPoint> distBaseline = router.route(fullRegion, fullRegion.origin(), fullRegion.destination(),
                new DistanceOnlyCostSurface(), 250.0, corridor);
        List<GeoPoint> transportOnly = router.route(fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(isolatedTransportLayer)), 250.0, corridor);

        // Load all 5 existing layers
        BhuvanCatalog catalog = new BhuvanCatalog();
        BhuvanFeatureInfoClient dummyClient = new BhuvanFeatureInfoClient(null, null);
        BhuvanLulcSampler lulcSampler = new BhuvanLulcSampler(
                catalog, dummyClient, 0.01, 0.004, 1, "data/cache/bhuvan-lulc-samples.json", new ObjectMapper()
        );
        Method loadLulc = BhuvanLulcSampler.class.getDeclaredMethod("loadPersistentCache");
        loadLulc.setAccessible(true);
        loadLulc.invoke(lulcSampler);
        LulcProvider lulcProvider = new BhuvanLulcProvider(lulcSampler);

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
                null, "data/cache/bhuvan-flood-wms", 512, 1000, 28, 1, new ObjectMapper()
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
                transportRaster, vectorIndex, transportProps
        );

        Map<String, Object> distDiag = metricsService.summarize(distBaseline);
        Map<String, Object> transOnlyDiag = metricsService.summarize(transportOnly);
        @SuppressWarnings("unchecked")
        double distTransPen = ((Map<String, Double>) distDiag.get("approximatePenaltyContributionKm"))
                .get("TRANSPORT_NETWORK");
        @SuppressWarnings("unchecked")
        double isolatedTransPen = ((Map<String, Double>) transOnlyDiag.get("approximatePenaltyContributionKm"))
                .get("TRANSPORT_NETWORK");

        System.out.println("DISTANCE_BASELINE Transport Summary: len=" + distDiag.get("routeLengthKm")
                + " km | pen=" + distTransPen + " km-eq | crossings=" + distDiag.get("transportCrossingSummary")
                + " | exposure=" + distDiag.get("transportCorridorExposureKm"));
        System.out.println("TRANSPORT_ONLY    Transport Summary: len=" + transOnlyDiag.get("routeLengthKm")
                + " km | pen=" + isolatedTransPen + " km-eq | crossings=" + transOnlyDiag.get("transportCrossingSummary")
                + " | exposure=" + transOnlyDiag.get("transportCorridorExposureKm"));
        assertTrue(isolatedTransPen <= distTransPen,
                "Isolated transport-aware routing must reduce or match total transport crossing & ROW overlap penalty");

        // === 4. COMPOSITE 5-LAYER vs 6-LAYER (EXPRESSWAY) vs 6-LAYER (NATIONAL_HIGHWAY) ===
        System.out.println("\n=== 4. COMPOSITE 5-LAYER vs 6-LAYER (EXPRESSWAY vs NATIONAL_HIGHWAY) ===");
        List<CostLayer> base5Layers = List.of(lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer);
        CostSurface surface5Layer = new CompositeCostSurface(base5Layers).withDenseColonyLastResortMode();
        List<GeoPoint> route5Layer = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(), surface5Layer, 250.0, corridor
        );

        HubEvaluationService hubService = new HubEvaluationService(vectorIndex, projection, transportProps);
        HighwayNetworkPlannerService plannerService = new HighwayNetworkPlannerService(
                vectorIndex, transportRaster, slopeProvider, paProvider, router, projection, transportProps
        );

        // 6-Layer EXPRESSWAY (accessibilityPull = 0.0, minSpacing = 15 km)
        var expHubEval = hubService.evaluate(
                fullRegion.origin(), fullRegion.destination(), corridor,
                FacilityMode.EXPRESSWAY, List.of(), null, null
        );
        TransportNetworkCostLayer expTransportLayer = new TransportNetworkCostLayer(
                transportRaster, vectorIndex, projection, transportProps.toPolicy(),
                transportProps.accessibilityPullFor(FacilityMode.EXPRESSWAY),
                expHubEval.approvedHubs(),
                fullRegion.origin(), fullRegion.destination(),
                transportProps.getCollectorSweetSpotMaxKm(),
                transportProps.getCollectorLongitudinalBandKm(),
                FacilityMode.EXPRESSWAY
        );
        List<GeoPoint> route6Expressway = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(
                        lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer, expTransportLayer)).withDenseColonyLastResortMode(),
                250.0, corridor
        );
        var expPlan = plannerService.planNetwork(fullRegion, route6Expressway, expHubEval, base5Layers);

        // 6-Layer NATIONAL_HIGHWAY (accessibilityPull = 0.35, minSpacing = 8 km)
        var nhHubEval = hubService.evaluate(
                fullRegion.origin(), fullRegion.destination(), corridor,
                FacilityMode.NATIONAL_HIGHWAY, List.of(), null, null
        );
        TransportNetworkCostLayer nhTransportLayer = new TransportNetworkCostLayer(
                transportRaster, vectorIndex, projection, transportProps.toPolicy(),
                transportProps.accessibilityPullFor(FacilityMode.NATIONAL_HIGHWAY),
                nhHubEval.approvedHubs(),
                fullRegion.origin(), fullRegion.destination(),
                transportProps.getCollectorSweetSpotMaxKm(),
                transportProps.getCollectorLongitudinalBandKm(),
                FacilityMode.NATIONAL_HIGHWAY
        );
        List<GeoPoint> route6NationalHighway = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(
                        lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer, nhTransportLayer)).withDenseColonyLastResortMode(),
                250.0, corridor
        );
        var nhPlan = plannerService.planNetwork(fullRegion, route6NationalHighway, nhHubEval, base5Layers);

        Map<String, Object> diag5 = metricsService.summarize(route5Layer);
        Map<String, Object> diag6Exp = metricsService.summarize(route6Expressway);
        Map<String, Object> diag6Nh = metricsService.summarize(route6NationalHighway);

        System.out.println("5-LAYER (BEFORE)          : " + diag5);
        System.out.println("6-LAYER (EXPRESSWAY)      : " + diag6Exp);
        System.out.println("  -> EXPRESSWAY PLAN      : interchanges=" + expPlan.interchanges()
                + " | spurs=" + expPlan.feederSpurs().stream()
                .map(s -> s.hub().name() + ":" + s.spurLengthKm() + "km->" + s.hub().tieInRoadRef()).toList()
                + " | ROBs=" + expPlan.railwayRobCount() + " | Flyovers=" + expPlan.roadFlyoverCount()
                + " | V/C=" + expPlan.projectedVcRatio() + " (" + expPlan.levelOfService() + ")");
        System.out.println("6-LAYER (NATIONAL_HIGHWAY): " + diag6Nh);
        System.out.println("  -> NATIONAL_HWY PLAN    : interchanges=" + nhPlan.interchanges()
                + " | spurs=" + nhPlan.feederSpurs().stream()
                .map(s -> s.hub().name() + ":" + s.spurLengthKm() + "km->" + s.hub().tieInRoadRef()).toList()
                + " | ROBs=" + nhPlan.railwayRobCount() + " | Flyovers=" + nhPlan.roadFlyoverCount()
                + " | V/C=" + nhPlan.projectedVcRatio() + " (" + nhPlan.levelOfService() + ")");

        // Hard constraint & topological continuity checks on both modes and all dedicated spurs
        for (var plan : List.of(expPlan, nhPlan)) {
            assertFalse(plan.interchanges().isEmpty(), "Each facility mode should serve at least 1 qualifying hub");
            assertEquals(plan.interchanges().size(), plan.feederSpurs().size());
            assertTrue(plan.projectedVcRatio() <= 0.75, "Autonomous mode V/C must remain <= 0.75");

            for (int i = 0; i < plan.feederSpurs().size(); i++) {
                var ic = plan.interchanges().get(i);
                var spur = plan.feederSpurs().get(i);
                List<GeoPoint> spPath = spur.path();
                // Rule 3 Continuity: Spur starts at exact mainline interchange point and ends at exact bypass tie-in!
                assertEquals(ic.point(), spPath.get(0));
                assertEquals(spur.hub().bypassTieIn(), spPath.get(spPath.size() - 1));
                assertFalse(
                        settlementProvider.sampleClass(
                                spur.hub().bypassTieIn().lat(),
                                spur.hub().bypassTieIn().lon()) == SettlementExposureClass.URBAN_CORE,
                        "Spur tie-in must not dump traffic inside an URBAN_CORE bottleneck");

                Map<String, Object> spurDiag = metricsService.summarize(spPath);
                @SuppressWarnings("unchecked")
                Map<String, Object> spurHc = (Map<String, Object>) spurDiag.get("hardConstraintValidation");
                assertEquals(0, ((Number) spurHc.get("protectedAreaViolationSamples")).intValue(),
                        "Dedicated A* spur for " + spur.hub().name() + " must pass Protected Area hard constraint (0 violations)");
                assertTrue(((Number) spurHc.get("slopeOver30ViolationSamples")).intValue() <= 3,
                        "Dedicated A* spur for " + spur.hub().name() + " micro-sampling slope>30 violations must remain <= 3 in hill terrain");
            }
        }

        // Verify Multi-Feature GeoJSON output
        List<Map<String, Object>> geoJsonFeatures =
                plannerService.buildGeoJsonFeatures(route6NationalHighway, Map.of("profile", "COMPOSITE_6_LAYER"), nhPlan);
        long mainlineCount = geoJsonFeatures.stream()
                .filter(f -> "MAINLINE".equals(((Map<?, ?>) f.get("properties")).get("featureRole"))).count();
        long spurCount = geoJsonFeatures.stream()
                .filter(f -> "DEDICATED_FEEDER_SPUR".equals(((Map<?, ?>) f.get("properties")).get("featureRole"))).count();
        long icCount = geoJsonFeatures.stream()
                .filter(f -> "INTERCHANGE".equals(((Map<?, ?>) f.get("properties")).get("featureRole"))).count();
        long tieCount = geoJsonFeatures.stream()
                .filter(f -> "ROAD_TIE_IN".equals(((Map<?, ?>) f.get("properties")).get("featureRole"))).count();
        long gsCount = geoJsonFeatures.stream()
                .filter(f -> "GRADE_SEPARATED_CROSSING".equals(((Map<?, ?>) f.get("properties")).get("featureRole"))).count();

        assertEquals(1L, mainlineCount);
        assertEquals(nhPlan.feederSpurs().size(), spurCount);
        assertEquals(nhPlan.interchanges().size(), icCount);
        assertEquals(nhPlan.feederSpurs().size(), tieCount);
        assertEquals(nhPlan.gradeSeparatedCrossings().size(), gsCount);
        assertTrue(gsCount >= 1L, "Route must emit grade-separated ROB/Flyover crossing features");
    }

    private static StudyRegion corridorWindow(StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg);
        return new StudyRegion(full.name() + " / validation window", minLat, minLon, maxLat, maxLon, a, b);
    }
}
