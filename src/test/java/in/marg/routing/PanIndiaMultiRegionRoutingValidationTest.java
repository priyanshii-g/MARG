package in.marg.routing;

import in.marg.api.CompositeRoutingController;
import in.marg.api.StudyRegionController;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.region.CorridorIngestionService;
import in.marg.region.CorridorTierMode;
import in.marg.region.RegionalCorridorPreset;
import in.marg.region.RegionalCorridorRegistry;
import in.marg.spatial.Wgs84UtmProjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanIndiaMultiRegionRoutingValidationTest {

    @Test
    void validateTwoTierCatalogAndLazyLoadingSemantics() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();

        // 1. At startup, 0 regional pipelines are loaded into heap memory
        assertEquals(3, registry.listPresets().size());
        assertFalse(registry.isRegionLoadedInMemory("assam"),
                "Assam pipeline must not be loaded before first query");
        assertFalse(registry.isRegionLoadedInMemory("western_ghats"),
                "Western Ghats pipeline must not be loaded before first query");
        assertFalse(registry.isRegionLoadedInMemory("gangetic_plains"),
                "Gangetic Plains pipeline must not be loaded before first query");
        assertTrue(registry.loadedRegionIds().isEmpty());

        // 2. Querying Western Ghats lazily loads ONLY western_ghats (UTM 43N)
        Map<String, Object> wgResponse = registry.route(
                "western_ghats", null,
                null, null, null, null,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null
        );
        assertEquals("FeatureCollection", wgResponse.get("type"));
        for (var f : (List<Map<String, Object>>) wgResponse.get("features")) {
            Map<?, ?> props = (Map<?, ?>) f.get("properties");
            System.out.println("WG_FEAT: role=" + props.get("featureRole") + " id=" + props.get("interchangeId") + "/" + props.get("bridgeId") + "/" + props.get("structureId") + " geom=" + f.get("geometry"));
        }
        assertEquals("western_ghats", wgResponse.get("regionId"));
        assertEquals(CorridorTierMode.TIER_2_PRECACHED_BENCHMARK.name(), wgResponse.get("corridorTierMode"));
        assertTrue(registry.isRegionLoadedInMemory("western_ghats"),
                "Western Ghats must be loaded in memory after routing query");
        assertFalse(registry.isRegionLoadedInMemory("assam"),
                "Assam must remain unloaded when only Western Ghats was queried");
        assertFalse(registry.isRegionLoadedInMemory("gangetic_plains"),
                "Gangetic Plains must remain unloaded when only Western Ghats was queried");

        Map<?, ?> wgMeta = (Map<?, ?>) wgResponse.get("metadata");
        assertEquals(Boolean.TRUE, wgMeta.get("lazyLoadedOnThisRequest"));
        assertEquals("EPSG:32643", wgMeta.get("coordinateReferenceSystem"));
        assertEquals(43, wgMeta.get("utmZone"));

        // 3. Second query to Western Ghats reuses the in-memory bundle (lazyLoadedOnThisRequest == false)
        Map<String, Object> wgSecond = registry.route(
                "western_ghats", null,
                18.280, 73.880, 17.820, 73.980,
                250.0, 0.15, 2000, null,
                "NATIONAL_HIGHWAY", null, null, null
        );
        Map<?, ?> wgSecondMeta = (Map<?, ?>) wgSecond.get("metadata");
        assertEquals(Boolean.FALSE, wgSecondMeta.get("lazyLoadedOnThisRequest"));

        // 4. Verify StudyRegionController /api/study-region/corridors catalog
        RegionalCorridorPreset assamPreset = registry.getPreset("assam");
        StudyRegionController studyController = new StudyRegionController(assamPreset.studyRegion(), registry);
        Map<String, Object> catalog = studyController.listCorridors();
        assertNotNull(catalog.get("tier2PreCachedCorridors"));
        assertNotNull(catalog.get("tier1DynamicPanIndiaEngine"));

        // 5. Evicting region releases it from the registry map
        registry.evictRegionFromMemory("western_ghats");
        assertFalse(registry.isRegionLoadedInMemory("western_ghats"));
    }

    @Test
    void validateArbitraryPointSelectionAndSmartSnappingInAllThreeTier2Corridors() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();

        // Corridor 1: Assam — arbitrary interior points + blocked Protected Area point snapping
        // Query 1a: User's exact test points: Dokmoka to Karbi Anglong plateau
        Map<String, Object> assamUserQuery = registry.route(
                "assam", null,
                26.350, 93.250,
                25.950, 93.550,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null
        );
        assertEquals("assam", assamUserQuery.get("regionId"));
        assertTrue(((Number) ((Map<?, ?>) assamUserQuery.get("metadata")).get("routeLengthKm")).doubleValue() > 20.0);

        // Query 1b: (26.620, 93.360) lies inside the Kaziranga Protected Area mask (lat > 26.60)
        Map<String, Object> assamSnapped = registry.route(
                "assam", null,
                26.625, 93.385,
                26.020, 93.420,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null
        );
        assertEquals("assam", assamSnapped.get("regionId"));
        Map<?, ?> assamMeta = (Map<?, ?>) assamSnapped.get("metadata");
        assertEquals("EPSG:32646", assamMeta.get("coordinateReferenceSystem"));
        Map<?, ?> assamSnapInfo = (Map<?, ?>) assamMeta.get("endpointResolution");
        assertEquals(Boolean.TRUE, assamSnapInfo.get("startSnapped"),
                "Start point inside Kaziranga PA mask must be automatically snapped to nearest navigable grid cell");
        assertTrue(((Number) assamSnapInfo.get("startSnapDistanceMeters")).doubleValue() > 0.0);

        // Corridor 2: Western Ghats (Pune-Bhor-Satara, UTM 43N) — blocked Sahyadri PA / >30 deg ridge point snapping
        // (17.840, 73.720) is inside the Sahyadri Protected Area mask centered at (17.880, 73.720) with r=7.5 km
        Map<String, Object> wgArbitrary = registry.route(
                "western_ghats", null,
                18.220, 73.860,
                17.840, 73.720,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null
        );
        assertEquals("western_ghats", wgArbitrary.get("regionId"));
        Map<?, ?> wgMeta = (Map<?, ?>) wgArbitrary.get("metadata");
        assertEquals("EPSG:32643", wgMeta.get("coordinateReferenceSystem"));
        Map<?, ?> wgSnapInfo = (Map<?, ?>) wgMeta.get("endpointResolution");
        assertEquals(Boolean.TRUE, wgSnapInfo.get("goalSnapped"),
                "Goal point inside Sahyadri PA / steep escarpment must be snapped to nearest feasible grid cell");
        assertTrue(((Number) wgMeta.get("routeLengthKm")).doubleValue() > 20.0);

        // Corridor 3: Indo-Gangetic Plains (Barabanki-Ramsanehighat-Ayodhya, UTM 44N) — arbitrary random interior points
        Map<String, Object> gpArbitrary = registry.route(
                "gangetic_plains", null,
                26.960, 81.320,
                26.740, 81.980,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null
        );
        assertEquals("gangetic_plains", gpArbitrary.get("regionId"));
        Map<?, ?> gpMeta = (Map<?, ?>) gpArbitrary.get("metadata");
        assertEquals("EPSG:32644", gpMeta.get("coordinateReferenceSystem"));
        assertEquals(44, gpMeta.get("utmZone"));
        assertTrue(((Number) gpMeta.get("routeLengthKm")).doubleValue() > 40.0);
        List<?> gpFeatures = (List<?>) gpArbitrary.get("features");
        assertFalse(gpFeatures.isEmpty());
    }

    @Test
    void validateTier1DynamicPanIndiaEngineAcrossMultipleUtmZones() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();

        // 1. Dynamic UTM Zone Selection Verification (Zones 42N..47N)
        assertEquals(42, Wgs84UtmProjection.indiaUtmZoneForLongitude(70.2)); // Gujarat / Kutch
        assertEquals(43, Wgs84UtmProjection.indiaUtmZoneForLongitude(75.8)); // Rajasthan / Maharashtra
        assertEquals(44, Wgs84UtmProjection.indiaUtmZoneForLongitude(80.9)); // UP / MP / Telangana
        assertEquals(45, Wgs84UtmProjection.indiaUtmZoneForLongitude(87.0)); // Bihar / Odisha / West Bengal
        assertEquals(46, Wgs84UtmProjection.indiaUtmZoneForLongitude(93.4)); // Assam / Meghalaya
        assertEquals(47, Wgs84UtmProjection.indiaUtmZoneForLongitude(96.5)); // Eastern Arunachal Pradesh

        // 2. Tier 1 Dynamic Route in Rajasthan (Aravalli belt, UTM 43N)
        Map<String, Object> rajasthanRoute = registry.route(
                "pan_india", "TIER_1_DYNAMIC_PAN_INDIA",
                26.820, 75.650,
                26.480, 74.720,
                250.0, 0.12, 2000, null,
                "EXPRESSWAY", null, null, null
        );
        assertEquals(CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA.name(), rajasthanRoute.get("corridorTierMode"));
        assertEquals("pan_india", rajasthanRoute.get("regionId"));
        Map<?, ?> rajMeta = (Map<?, ?>) rajasthanRoute.get("metadata");
        assertEquals(43, rajMeta.get("utmZone"));
        assertEquals("EPSG:32643", rajMeta.get("coordinateReferenceSystem"));
        double rajLenKm = ((Number) rajMeta.get("routeLengthKm")).doubleValue();
        double rajBaseKm = ((Number) rajMeta.get("distanceBaselineLengthKm")).doubleValue();
        assertTrue(rajLenKm >= rajBaseKm,
                "13-layer dynamic Pan-India route must deflect around local obstacles relative to straight baseline");

        // 3. Auto-detection of Tier 1 when coordinates are outside the 3 presets (Central India / MP, UTM 44N)
        Map<String, Object> centralIndiaRoute = registry.route(
                null, null,
                23.220, 78.650,
                22.850, 79.150,
                250.0, 0.12, 2000, null,
                "NATIONAL_HIGHWAY", null, null, null
        );
        assertEquals(CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA.name(), centralIndiaRoute.get("corridorTierMode"));
        Map<?, ?> mpMeta = (Map<?, ?>) centralIndiaRoute.get("metadata");
        assertEquals(44, mpMeta.get("utmZone"));
        assertEquals("EPSG:32644", mpMeta.get("coordinateReferenceSystem"));

        // 4. Smart Snapping in Tier 1 when start point is clicked directly inside Ranthambore Tiger Reserve Core
        Map<String, Object> sanctuarySnapRoute = registry.route(
                "pan_india", null,
                26.017, 76.502, // Ranthambore Core center
                25.720, 76.200,
                250.0, 0.12, 2000, null,
                "EXPRESSWAY", null, null, null
        );
        Map<?, ?> snapMeta = (Map<?, ?>) sanctuarySnapRoute.get("metadata");
        Map<?, ?> snapResolution = (Map<?, ?>) snapMeta.get("endpointResolution");
        assertEquals(Boolean.TRUE, snapResolution.get("startSnapped"),
                "Start coordinate clicked inside Ranthambore Tiger Reserve core must snap to nearest navigable boundary cell");
    }

    @Test
    void validateExplicitRegionQueriesAndDemonstrationPresetHighlighting() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);
        StudyRegionController studyController = new StudyRegionController(registry.getPreset("assam").studyRegion(), registry);

        // 1. Verify that the 3 corridor regions can be listed and their boundary polygons retrieved for highlighting
        Map<String, Object> catalog = studyController.listCorridors();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> presets = (List<Map<String, Object>>) catalog.get("tier2PreCachedCorridors");
        assertEquals(3, presets.size());
        assertTrue(presets.stream().anyMatch(p -> "assam".equals(p.get("id"))));
        assertTrue(presets.stream().anyMatch(p -> "western_ghats".equals(p.get("id"))));
        assertTrue(presets.stream().anyMatch(p -> "gangetic_plains".equals(p.get("id"))));

        // 2. Query /api/routes/composite?region=assam (demonstration default endpoints)
        Map<String, Object> assamRoute = controller.route(
                null, null, null, null,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                "assam", null, null
        );
        assertEquals("assam", assamRoute.get("regionId"));
        assertEquals("FeatureCollection", assamRoute.get("type"));

        // 3. Query /api/routes/composite?region=western_ghats (demonstration default endpoints)
        Map<String, Object> wgRoute = controller.route(
                null, null, null, null,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                "western_ghats", null, null
        );
        assertEquals("western_ghats", wgRoute.get("regionId"));
        assertEquals("EPSG:32643", ((Map<?, ?>) wgRoute.get("metadata")).get("coordinateReferenceSystem"));

        // 4. Query /api/routes/composite?region=gangetic_plains (demonstration default endpoints)
        Map<String, Object> gpRoute = controller.route(
                null, null, null, null,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                "gangetic_plains", null, null
        );
        assertEquals("gangetic_plains", gpRoute.get("regionId"));
        assertEquals("EPSG:32644", ((Map<?, ?>) gpRoute.get("metadata")).get("coordinateReferenceSystem"));

        // 5. Query with explicit region AND custom clicked coordinates inside that highlighted region
        Map<String, Object> wgCustom = controller.route(
                18.250, 73.900, 17.800, 74.050,
                250.0, 0.15, 2000, null,
                "NATIONAL_HIGHWAY", null, null, null,
                "western_ghats", null, null
        );
        assertEquals("western_ghats", wgCustom.get("regionId"));
        assertTrue(((Number) ((Map<?, ?>) wgCustom.get("metadata")).get("routeLengthKm")).doubleValue() > 20.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateSacredHeritageExclusionAndExistingRoadTerminalConnectors() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        // 1. Gangetic Plains: Destination at Shri Ram Janmabhoomi must hard-snap outward
        Map<String, Object> ramSnapRoute = controller.route(
                26.915, 81.245, 26.7956, 82.1944,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                "gangetic_plains", null, null
        );
        Map<?, ?> ramMeta = (Map<?, ?>) ramSnapRoute.get("metadata");
        Map<?, ?> ramEndRes = (Map<?, ?>) ramMeta.get("endpointResolution");
        assertNotNull(ramEndRes);
        assertTrue(Boolean.TRUE.equals(ramEndRes.get("goalSnapped")),
                "Shri Ram Janmabhoomi destination must snap outside the sacred exclusion precinct");
        String ramGoalReason = String.valueOf(ramEndRes.get("goalSnapReason")).toUpperCase();
        assertTrue(ramGoalReason.contains("SACRED") || ramGoalReason.contains("RELIGIOUS"),
                "Goal snap reason must reflect sacred/religious exclusion, was: " + ramGoalReason);

        // 2. Gangetic Plains: Cross-Ayodhya route must have 0 mainline vertices inside Ram Janmabhoomi precinct
        Map<String, Object> ayodhyaBypassRoute = controller.route(
                26.915, 81.245, 26.785, 82.220,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                "gangetic_plains", null, null
        );
        List<Map<String, Object>> bypassFeatures = (List<Map<String, Object>>) ayodhyaBypassRoute.get("features");
        Map<String, Object> bypassMainline = bypassFeatures.stream()
                .filter(f -> {
                    Object role = ((Map<?, ?>) f.get("properties")).get("featureRole");
                    return "GREENFIELD_MAINLINE".equals(role) || "MAINLINE".equals(role);
                })
                .findFirst()
                .orElseThrow();
        List<List<Number>> coords = (List<List<Number>>) ((Map<?, ?>) bypassMainline.get("geometry")).get("coordinates");
        for (List<Number> c : coords) {
            double lon = c.get(0).doubleValue();
            double lat = c.get(1).doubleValue();
            boolean insideSacredBox = lat >= 26.786 && lat <= 26.806 && lon >= 82.182 && lon <= 82.208;
            assertFalse(insideSacredBox,
                    "Mainline vertex violated Shri Ram Janmabhoomi sacred exclusion box at lat=" + lat + ", lon=" + lon);
        }

        // 3. Tier-1 Pan-India: Start directly on Shri Ram Janmabhoomi must snap with SAC-RAM-JANMABHOOMI
        Map<String, Object> panRamRoute = controller.route(
                26.7956, 82.1944, 26.850, 82.550,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                null, "TIER_1_DYNAMIC_PAN_INDIA", null
        );
        Map<?, ?> panMeta = (Map<?, ?>) panRamRoute.get("metadata");
        Map<?, ?> panEndRes = (Map<?, ?>) panMeta.get("endpointResolution");
        assertNotNull(panEndRes);
        assertTrue(Boolean.TRUE.equals(panEndRes.get("startSnapped")),
                "Tier-1 Pan-India must snap start coordinate off Shri Ram Janmabhoomi");
        String panStartReason = String.valueOf(panEndRes.get("startSnapReason"));
        assertTrue(panStartReason.contains("SACRED_HERITAGE") && panStartReason.contains("SAC-RAM-JANMABHOOMI"),
                "Tier-1 snap reason should reference SACRED_HERITAGE and SAC-RAM-JANMABHOOMI, was: " + panStartReason);

        // 4. Gangetic Plains: Off-road endpoints must connect via TERMINAL_ACCESS_ROAD & ROAD_TIE_IN and count in stats
        Map<String, Object> offRoadRoute = controller.route(
                26.960, 81.320, 26.740, 81.980,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                "gangetic_plains", null, null
        );
        Map<?, ?> offMeta = (Map<?, ?>) offRoadRoute.get("metadata");
        Map<?, ?> termConn = (Map<?, ?>) offMeta.get("terminalRoadConnections");
        assertNotNull(termConn, "metadata.terminalRoadConnections must be populated");
        assertTrue(Boolean.TRUE.equals(termConn.get("includedInMainlineAndStatistics")),
                "Terminal connectors must be flagged as included in mainline & statistics");
        double termAccessKm = ((Number) termConn.get("totalTerminalAccessRoadLengthKm")).doubleValue();
        assertTrue(termAccessKm > 0.0, "Off-road terminals should produce non-zero access connector length");

        Map<?, ?> netSummary = (Map<?, ?>) offMeta.get("transportNetworkAndCapacitySummary");
        assertNotNull(netSummary);
        assertTrue(((Number) netSummary.get("totalTerminalAccessRoadLengthKm")).doubleValue() > 0.0);

        List<Map<String, Object>> offFeatures = (List<Map<String, Object>>) offRoadRoute.get("features");
        long tieInCount = offFeatures.stream()
                .filter(f -> "ROAD_TIE_IN".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                .count();
        long connectorCount = offFeatures.stream()
                .filter(f -> "TERMINAL_ACCESS_ROAD".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                .count();
        assertTrue(tieInCount >= 2, "Expected origin & destination ROAD_TIE_IN markers, got " + tieInCount);
        assertTrue(connectorCount >= 1, "Expected at least 1 TERMINAL_ACCESS_ROAD geometry, got " + connectorCount);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateAutoConnectIntermediateHubsToggleAndCountCapping() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        // 1. With autoConnectHubs = false and no user targetHubs: 0 autonomous hubs approved
        Map<String, Object> autoOffRoute = controller.route(
                26.925, 81.185, 26.790, 82.200,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 2,
                "gangetic_plains", null, null
        );
        Map<?, ?> autoOffMeta = (Map<?, ?>) autoOffRoute.get("metadata");
        Map<?, ?> autoOffPipeline = (Map<?, ?>) autoOffMeta.get("hubEvaluationPipeline");
        assertEquals(Boolean.FALSE, autoOffPipeline.get("autoConnectHubs"));
        List<?> autoOffApproved = (List<?>) autoOffPipeline.get("approvedHubIds");
        assertTrue(autoOffApproved.isEmpty(), "When autoConnectHubs is false and no user targetHubs, approved hubs must be empty");

        // 2. With autoConnectHubs = true and maxAutoHubs = 1: exactly 1 autonomous hub approved
        Map<String, Object> auto1Route = controller.route(
                26.925, 81.185, 26.790, 82.200,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                true, 1,
                "gangetic_plains", null, null
        );
        Map<?, ?> auto1Meta = (Map<?, ?>) auto1Route.get("metadata");
        Map<?, ?> auto1Pipeline = (Map<?, ?>) auto1Meta.get("hubEvaluationPipeline");
        assertEquals(Boolean.TRUE, auto1Pipeline.get("autoConnectHubs"));
        List<?> auto1Approved = (List<?>) auto1Pipeline.get("approvedHubIds");
        assertEquals(1, auto1Approved.size(), "When maxAutoHubs=1, exactly 1 autonomous hub must be approved");

        // 3. User custom hub (e.g. Ramnagar) is always connected even when autoConnectHubs = false
        Map<String, Object> customWithAutoOff = controller.route(
                26.925, 81.185, 26.790, 82.200,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", "Ramnagar", null, null,
                false, 2,
                "gangetic_plains", null, null
        );
        Map<?, ?> customMeta = (Map<?, ?>) customWithAutoOff.get("metadata");
        Map<?, ?> customPipeline = (Map<?, ?>) customMeta.get("hubEvaluationPipeline");
        List<?> customApproved = (List<?>) customPipeline.get("approvedHubIds");
        assertEquals(1, customApproved.size(), "User specified Ramnagar must be approved even if autoConnectHubs is false");
        assertTrue(String.valueOf(customApproved.get(0)).toLowerCase().contains("ramnagar"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateAssamWithDimapurAndAutoConnect() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        Map<String, Object> assamRoute = controller.route(
                26.58970, 93.40035, 25.84573, 93.43781,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", "Dimapur", null, null,
                true, 2,
                "assam", null, null
        );
        Map<?, ?> meta = (Map<?, ?>) assamRoute.get("metadata");
        Map<?, ?> pipeline = (Map<?, ?>) meta.get("hubEvaluationPipeline");
        List<?> approved = (List<?>) pipeline.get("approvedHubIds");
        assertEquals(3, approved.size(), "When user specifies Dimapur and selects 2 auto hubs, 3 hubs must be approved");
        assertTrue(approved.stream().anyMatch(h -> String.valueOf(h).contains("Dimapur")), "User hub Dimapur must be approved");

        List<Map<?, ?>> features = (List<Map<?, ?>>) assamRoute.get("features");
        long spurCount = features.stream()
                .filter(f -> "DEDICATED_FEEDER_SPUR".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                .count();
        assertEquals(3, spurCount, "Expected exactly 3 dedicated feeder spurs for Dimapur + 2 autonomous hubs");

        // Verify capacity analysis and 6-lane configuration recommendations
        Map<?, ?> netSummary = (Map<?, ?>) meta.get("transportNetworkAndCapacitySummary");
        Map<?, ?> trafficAnalysis = (Map<?, ?>) netSummary.get("trafficCapacityAnalysis");
        assertEquals(Boolean.TRUE, trafficAnalysis.get("sixLaneRecommended"),
                "High induced traffic from Dimapur and regional hubs must recommend 6-lane configuration under IRC:SP:84 §2.2");
        assertEquals("6_LANE_EXPANSION", trafficAnalysis.get("laneConfigurationRecommended"));
        assertTrue(String.valueOf(trafficAnalysis.get("capacityStandardNote")).contains("6-Lane Expressway"));

        // Verify that feeder spurs carry decisionStatus and decisionReason
        boolean hasSpurWithReason = features.stream()
                .filter(f -> "DEDICATED_FEEDER_SPUR".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                .anyMatch(f -> {
                    Map<?, ?> p = (Map<?, ?>) f.get("properties");
                    return p.get("decisionReason") != null && !String.valueOf(p.get("decisionReason")).isBlank();
                });
        assertTrue(hasSpurWithReason, "Feeder spurs must carry explicit decisionReason for frontend display");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validateMultiObjectiveTradeOffsAndRequestScopedPreferences() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        // 1. Route with BALANCED preset
        Map<String, Object> balancedRoute = controller.route(
                26.58970, 93.40035, 25.84573, 93.43781,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "assam", null, null,
                1.0, 1.0, 1.0, "BALANCED"
        );
        Map<?, ?> balancedMeta = (Map<?, ?>) balancedRoute.get("metadata");
        Map<?, ?> balancedProfile = (Map<?, ?>) balancedMeta.get("tradeOffProfile");
        assertNotNull(balancedProfile, "Trade-off profile must be computed and present in metadata");
        Map<?, ?> balancedScores = (Map<?, ?>) balancedProfile.get("scores");
        assertNotNull(balancedScores.get("environmentalScore"));
        assertNotNull(balancedScores.get("socialScore"));
        assertNotNull(balancedScores.get("directnessScore"));
        assertNotNull(balancedScores.get("connectivityScore"));
        assertNotNull(balancedProfile.get("tradeOffNarrative"));
        List<?> balancedCompromises = (List<?>) balancedProfile.get("keyCompromises");
        assertFalse(balancedCompromises.isEmpty(), "Key compromises must be listed");

        // 2. Route with ECO_SHIELD preset (high environmental weight = 2.5)
        Map<String, Object> ecoRoute = controller.route(
                26.58970, 93.40035, 25.84573, 93.43781,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "assam", null, null,
                2.5, 1.0, 0.8, "ECO_SHIELD"
        );
        Map<?, ?> ecoMeta = (Map<?, ?>) ecoRoute.get("metadata");
        Map<?, ?> ecoProfile = (Map<?, ?>) ecoMeta.get("tradeOffProfile");
        assertNotNull(ecoProfile);
        String ecoNarrative = (String) ecoProfile.get("tradeOffNarrative");
        assertTrue(ecoNarrative.contains("Eco-Shield") || ecoNarrative.contains("natural habitat"),
                "Narrative must explain the eco-shield priority");

        // 3. Subsequent route with default weights verifies zero pollution/mutation of shared singleton state
        Map<String, Object> resetRoute = controller.route(
                26.58970, 93.40035, 25.84573, 93.43781,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "assam", null, null
        );
        Map<?, ?> resetMeta = (Map<?, ?>) resetRoute.get("metadata");
        Map<?, ?> resetProfile = (Map<?, ?>) resetMeta.get("tradeOffProfile");
        Map<?, ?> resetPref = (Map<?, ?>) resetMeta.get("alignmentPreference");
        assertEquals(1.0, ((Number) resetPref.get("environmentalWeight")).doubleValue(), 1e-4,
                "Default request must revert to environmentalWeight 1.0 without singleton state pollution");
        assertEquals(1.0, ((Number) resetPref.get("socialWeight")).doubleValue(), 1e-4);
        assertEquals(1.0, ((Number) resetPref.get("directnessWeight")).doubleValue(), 1e-4);
    }
}
