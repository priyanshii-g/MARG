package in.marg.region;

import in.marg.api.CompositeRoutingController;
import in.marg.model.GeoPoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorridorIngestionValidationTest {

    @Test
    void validateOnDemandCorridorIngestionAndSubsequent13LayerRouting() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);

        // 1. Ingest a brand new custom corridor: Bundelkhand Expressway (Jhansi to Banda, UP/MP)
        CorridorIngestionService.IngestionRequest request = new CorridorIngestionService.IngestionRequest(
                "bundelkhand",
                "Bundelkhand Expressway Demonstration Corridor (Jhansi–Banda)",
                "Uttar Pradesh / Madhya Pradesh",
                "VINDHYA_AND_BUNDELKHAND_CRATON",
                25.45, 78.58, // Jhansi
                25.48, 80.34, // Banda
                25.10, 78.20,
                25.80, 80.60
        );

        CorridorIngestionService.IngestionResult result = ingestionService.ingestCorridor(request);
        assertEquals("SUCCESS", result.status());
        assertEquals("bundelkhand", result.regionId());
        assertEquals(44, result.utmZone());
        assertEquals("EPSG:32644", result.coordinateReferenceSystem());
        assertEquals(13, result.totalLayersIngested());

        // 2. Verify it is registered dynamically in RegionalCorridorRegistry
        RegionalCorridorPreset preset = registry.getPreset("bundelkhand");
        assertNotNull(preset);
        assertEquals("CORRIDOR-BUNDELKHAND", preset.code());
        assertEquals(44, preset.utmZone());
        assertEquals("EPSG:32644", preset.epsgCode());

        // 3. Verify it shows up in describeTwoTierCatalog()
        Map<String, Object> catalog = registry.describeTwoTierCatalog();
        var presets = (java.util.List<?>) catalog.get("tier2PreCachedCorridors");
        assertTrue(presets.stream().anyMatch(p -> "bundelkhand".equals(((Map<?, ?>) p).get("id"))));

        // 4. Execute full 13-Layer routing query in the newly ingested corridor
        Map<String, Object> routeResponse = registry.route(
                "bundelkhand", null,
                25.45, 78.58,
                25.48, 80.34,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null
        );

        assertEquals("FeatureCollection", routeResponse.get("type"));
        assertEquals("bundelkhand", routeResponse.get("regionId"));
        assertEquals(CorridorTierMode.TIER_2_PRECACHED_BENCHMARK.name(), routeResponse.get("corridorTierMode"));

        Map<?, ?> meta = (Map<?, ?>) routeResponse.get("metadata");
        assertEquals("EPSG:32644", meta.get("coordinateReferenceSystem"));
        assertEquals(44, meta.get("utmZone"));
        assertTrue(((Number) meta.get("routeLengthKm")).doubleValue() > 50.0);

        var features = (java.util.List<?>) routeResponse.get("features");
        assertFalse(features.isEmpty());

        // 5. Verify the existing Tier 1 Dynamic Pan-India Engine still works instantly as before
        Map<String, Object> panIndiaRoute = registry.route(
                "pan_india", "TIER_1_DYNAMIC_PAN_INDIA",
                26.820, 75.650,
                26.480, 74.720,
                250.0, 0.12, 2000, null,
                "EXPRESSWAY", null, null, null
        );
        assertEquals(CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA.name(), panIndiaRoute.get("corridorTierMode"));
        Map<?, ?> panMeta = (Map<?, ?>) panIndiaRoute.get("metadata");
        assertEquals(43, panMeta.get("utmZone"));
    }

    @Test
    void validateSingleRequestZeroConfigurationAutoIngestionAndRouting() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        // Evaluator simply clicks Point A and Point B on the map outside existing presets,
        // with deep=true (toggle on UI). They type NO regionId, and run ONLY ONE call!
        double startLat = 26.15;
        double startLon = 76.85;
        double endLat = 25.85;
        double endLon = 77.40;

        Map<String, Object> response = controller.route(
                startLat, startLon, endLat, endLon,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                null, null, true // deep = true
        );

        assertNotNull(response);
        assertEquals("FeatureCollection", response.get("type"));
        // RegionId was generated automatically by backend!
        String regionId = (String) response.get("regionId");
        assertNotNull(regionId);
        assertTrue(regionId.startsWith("corridor_"));
        assertEquals(CorridorTierMode.TIER_2_PRECACHED_BENCHMARK.name(), response.get("corridorTierMode"));

        Map<?, ?> meta = (Map<?, ?>) response.get("metadata");
        assertEquals("EPSG:32643", meta.get("coordinateReferenceSystem"));
        assertEquals(43, meta.get("utmZone"));
        assertTrue(((Number) meta.get("routeLengthKm")).doubleValue() > 20.0);

        // Verify the newly auto-ingested corridor is now registered in the registry
        assertTrue(registry.hasPreset(regionId));

        // Subsequent query without deep=true immediately reuses the ingested corridor
        Map<String, Object> secondResponse = controller.route(
                startLat, startLon, endLat, endLon,
                250.0, 0.15, 2000, null,
                "NATIONAL_HIGHWAY", null, null, null,
                null, null, false
        );
        assertEquals("NATIONAL_HIGHWAY", secondResponse.get("facilityMode"));
        assertEquals(regionId, secondResponse.get("regionId"));
    }

    @Test
    void testUserExactQueryWithDeepTrue() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        Map<String, Object> response = controller.route(
                20.150, 76.850, 23.850, 77.400,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                null, null, true // deep = true
        );
        assertNotNull(response);
    }
}
