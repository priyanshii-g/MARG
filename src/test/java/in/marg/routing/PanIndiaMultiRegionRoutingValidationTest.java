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
}
