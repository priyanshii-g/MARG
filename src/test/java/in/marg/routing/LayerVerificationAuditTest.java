package in.marg.routing;

import in.marg.api.CompositeRoutingController;
import in.marg.region.CorridorIngestionService;
import in.marg.region.RegionalCorridorRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerVerificationAuditTest {

    @Test
    @SuppressWarnings("unchecked")
    void tier2CorridorEmitsAll13LayerAuditRecordsAndRouteLayerProfile() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        Map<String, Object> route = controller.route(
                26.925, 81.185, 26.785, 82.140,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "gangetic_plains", null, null
        );

        Map<?, ?> meta = (Map<?, ?>) route.get("metadata");
        assertNotNull(meta, "metadata must be present");

        List<Map<String, Object>> layerAudit = (List<Map<String, Object>>) meta.get("layerAudit");
        assertNotNull(layerAudit, "metadata.layerAudit must be present");
        assertEquals(13, layerAudit.size(), "Must emit exactly 13 layer verification records");

        for (int i = 0; i < 13; i++) {
            Map<String, Object> rec = layerAudit.get(i);
            assertEquals(i + 1, ((Number) rec.get("layerNumber")).intValue(), "Layer numbers must be 1..13 in order");
            assertNotNull(rec.get("layerId"));
            assertNotNull(rec.get("name"));
            assertNotNull(rec.get("domain"));
            assertNotNull(rec.get("standard"));
            assertNotNull(rec.get("verdict"));
            assertNotNull(rec.get("headlineProof"));
            assertFalse(String.valueOf(rec.get("headlineProof")).isBlank());
            assertNotNull(rec.get("comparison"));
        }

        // Layer 4 Protected Areas hard-constraint pass
        Map<String, Object> l4 = layerAudit.get(3);
        assertEquals("L4_PROTECTED_AREAS", l4.get("layerId"));
        assertEquals("HARD_CONSTRAINT_PASS", l4.get("verdict"));

        // Layer 6 Transport terminal access compliance
        Map<String, Object> l6 = layerAudit.get(5);
        assertEquals("L6_TRANSPORT", l6.get("layerId"));
        assertEquals("HARD_CONSTRAINT_PASS", l6.get("verdict"));

        // Compact sampled profile for route-segment colorization & DEM sparkline
        List<Map<String, Object>> profile = (List<Map<String, Object>>) meta.get("routeLayerProfile");
        assertNotNull(profile, "metadata.routeLayerProfile must be present");
        assertTrue(profile.size() >= 10, "routeLayerProfile must have sampled points along corridor, got " + profile.size());
        Map<String, Object> first = profile.get(0);
        assertNotNull(first.get("lat"));
        assertNotNull(first.get("lon"));
        assertNotNull(first.get("chKm"));
        assertNotNull(first.get("lulc"));
        assertNotNull(first.get("flood"));
        assertNotNull(first.get("slopeBand"));
        assertNotNull(first.get("settlement"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tier1PanIndiaCorridorEmitsAll13LayerAuditRecordsAndProfile() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        Map<String, Object> route = controller.route(
                26.8467, 80.9462, 26.4499, 80.3319,
                250.0, 0.25, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "pan_india", null, null
        );

        Map<?, ?> meta = (Map<?, ?>) route.get("metadata");
        assertNotNull(meta);

        List<Map<String, Object>> layerAudit = (List<Map<String, Object>>) meta.get("layerAudit");
        assertNotNull(layerAudit, "Tier-1 metadata.layerAudit must be present");
        assertEquals(13, layerAudit.size(), "Tier-1 must emit all 13 layer verification records");

        List<Map<String, Object>> profile = (List<Map<String, Object>>) meta.get("routeLayerProfile");
        assertNotNull(profile, "Tier-1 metadata.routeLayerProfile must be present");
        assertTrue(profile.size() >= 10, "Tier-1 routeLayerProfile must be populated");
        assertNotNull(profile.get(0).get("slopeDeg"));
        assertNotNull(profile.get(0).get("lulc"));
        assertNotNull(profile.get(0).get("flood"));
    }
}
