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

    @Test
    @SuppressWarnings("unchecked")
    void kazirangaAssamCorridorReportsNonZeroForestHectaresInTradeOffAndLayer1Audit() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        Map<String, Object> route = controller.route(
                26.5897, 93.40035, 25.84573, 93.43781,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                true, 1,
                "assam", null, null
        );

        Map<?, ?> meta = (Map<?, ?>) route.get("metadata");
        Map<?, ?> tradeOff = (Map<?, ?>) meta.get("tradeOffProfile");
        Map<?, ?> envSummary = (Map<?, ?>) tradeOff.get("environmentalSummary");
        double forestHa = ((Number) envSummary.get("forestHectaresDirectRow")).doubleValue();
        assertTrue(forestHa > 100.0,
                "Kaziranga–Karbi Anglong forested corridor must report accurate non-zero forest diversion hectares, got: " + forestHa);

        List<Map<String, Object>> layerAudit = (List<Map<String, Object>>) meta.get("layerAudit");
        assertNotNull(layerAudit);
        assertEquals(13, layerAudit.size());
        String l1Headline = String.valueOf(layerAudit.get(0).get("headlineProof"));
        assertTrue(l1Headline.contains("ha"), "Layer 1 LULC proof should cite 2D RoW forest hectares: " + l1Headline);

        // Verify 13-layer feeder spur profiles & spur engineering structure analysis
        List<Map<String, Object>> spurProfiles = (List<Map<String, Object>>) meta.get("spurLayerProfiles");
        assertNotNull(spurProfiles, "metadata.spurLayerProfiles must be present");
        assertFalse(spurProfiles.isEmpty(), "Expected at least one sampled feeder/terminal spur profile");
        List<Map<String, Object>> spurSamples = (List<Map<String, Object>>) spurProfiles.get(0).get("samples");
        assertNotNull(spurSamples);
        assertTrue(spurSamples.size() >= 2, "Feeder spur profile must contain sampled points");
        assertNotNull(spurSamples.get(0).get("lulc"));
        assertNotNull(spurSamples.get(0).get("slopeBand"));

        Map<?, ?> spurEngSummary = (Map<?, ?>) meta.get("spurEngineeringSummary");
        assertNotNull(spurEngSummary, "metadata.spurEngineeringSummary must be present");
        assertTrue(((Number) spurEngSummary.get("spursEvaluated")).intValue() >= 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dimapurFeederSpurFloodAndConnectingRoadsAppearAcrossAll13LayerAuditCards() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        Map<String, Object> route = controller.route(
                26.5897, 93.40035, 25.84573, 93.43781,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", "Dimapur", null, 0.85,
                true, 2,
                "assam", null, null
        );

        Map<?, ?> meta = (Map<?, ?>) route.get("metadata");
        List<Map<String, Object>> layerAudit = (List<Map<String, Object>>) meta.get("layerAudit");
        assertNotNull(layerAudit);
        assertEquals(13, layerAudit.size());

        // Every one of the 13 layer cards must include connectingRoadsImpact listing Dimapur
        for (Map<String, Object> rec : layerAudit) {
            assertEquals(Boolean.TRUE, rec.get("includesConnectingRoads"),
                    "Layer " + rec.get("layerId") + " must set includesConnectingRoads=true when spurs exist");
            Map<String, Object> impact = (Map<String, Object>) rec.get("connectingRoadsImpact");
            assertNotNull(impact, "Layer " + rec.get("layerId") + " must include connectingRoadsImpact");
            List<Map<String, Object>> perSpur = (List<Map<String, Object>>) impact.get("perSpur");
            assertNotNull(perSpur);
            assertTrue(perSpur.stream().anyMatch(r -> String.valueOf(r.get("hubName")).toLowerCase().contains("dimapur")),
                    "Layer " + rec.get("layerId") + " connectingRoadsImpact must list Dimapur spur");
        }

        // Layer 2 Flood must report Dimapur's flood-prone exposure in exposureBreakdown & headline (never falsely claim 0.0 km)
        Map<String, Object> l2 = layerAudit.get(1);
        assertEquals("L2_FLOOD", l2.get("layerId"));
        Map<String, Object> l2Breakdown = (Map<String, Object>) l2.get("exposureBreakdown");
        double modHighKm = ((Number) l2Breakdown.getOrDefault("MODERATE", 0.0)).doubleValue()
                + ((Number) l2Breakdown.getOrDefault("HIGH", 0.0)).doubleValue()
                + ((Number) l2Breakdown.getOrDefault("VERY_HIGH", 0.0)).doubleValue();
        String l2Headline = String.valueOf(l2.get("headlineProof"));
        if (modHighKm > 0.01) {
            assertFalse(l2Headline.contains("(0.0 km flood risk)"),
                    "Layer 2 Flood must NOT claim 0.0 km flood risk when connecting roads cross Moderate/High flood zones: " + l2Headline);
            assertTrue(l2Headline.toLowerCase().contains("connecting roads") || l2Headline.toLowerCase().contains("dimapur"),
                    "Layer 2 Flood headline must explicitly mention connecting road flood risk: " + l2Headline);
            Map<String, Object> l2Comp = (Map<String, Object>) l2.get("comparison");
            assertTrue(((Number) l2Comp.get("routeValue")).doubleValue() > 0.01,
                    "Layer 2 comparison routeValue must include connecting-road Moderate-Severe flood exposure");
        }
        System.out.println("L2 Flood Breakdown (Main + Spurs): " + l2Breakdown);
        System.out.println("L2 Flood Headline: " + l2Headline);
        System.out.println("L2 Flood Verdict: " + l2.get("verdict"));
        System.out.println("L2 Connecting Roads Impact: " + l2.get("connectingRoadsImpact"));

        // Verify apples-to-apples straight-line feeder spur statistics + per-km rates are emitted
        Map<String, Object> l1 = layerAudit.get(0);
        Map<String, Object> l3 = layerAudit.get(2);
        Map<String, Object> l6 = layerAudit.get(5);
        Map<String, Object> l11 = layerAudit.get(10);
        Map<String, Object> l1Comp = (Map<String, Object>) l1.get("comparison");
        Map<String, Object> l3Comp = (Map<String, Object>) l3.get("comparison");
        Map<String, Object> l6Comp = (Map<String, Object>) l6.get("comparison");
        Map<String, Object> l11Comp = (Map<String, Object>) l11.get("comparison");
        assertEquals(Boolean.TRUE, l1Comp.get("includesConnectingRoads"));
        assertNotNull(l1Comp.get("baselineRateNote"));
        assertNotNull(l1Comp.get("routeRateNote"));
        Map<String, Object> l1Impact = (Map<String, Object>) l1.get("connectingRoadsImpact");
        assertNotNull(l1Impact.get("baselineLengthKm"), "connectingRoadsImpact must include baselineLengthKm for straight-line spurs");
        Map<String, Object> l9 = layerAudit.get(8);
        Map<String, Object> l9Breakdown = (Map<String, Object>) l9.get("exposureBreakdown");
        double activeLandslideKm = ((Number) l9Breakdown.getOrDefault("ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE", 0.0)).doubleValue();
        double highLandslideKm = ((Number) l9Breakdown.getOrDefault("HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP", 0.0)).doubleValue();
        double severeFloodKm = ((Number) l2Breakdown.getOrDefault("VERY_HIGH", 0.0)).doubleValue();
        double highFloodKm = ((Number) l2Breakdown.getOrDefault("HIGH", 0.0)).doubleValue();
        System.out.println("L9 Geotech Breakdown (Main + Spurs): " + l9Breakdown);
        System.out.println("L9 Geotech Verdict: " + l9.get("verdict") + " | Check: " + l9.get("hardConstraintCheck"));
        System.out.println("=== ASSAM + DIMAPUR RE-BALANCED BENCHMARK ===");
        System.out.println("L1 LULC:      " + l1Comp);
        System.out.println("L2 Flood:     " + l2.get("comparison") + " | high=" + highFloodKm + " km, severe=" + severeFloodKm + " km");
        System.out.println("L3 Slope:     " + l3Comp);
        System.out.println("L4 PA:        " + layerAudit.get(3).get("comparison"));
        System.out.println("L6 Transport: " + l6Comp);
        System.out.println("L9 Geotech:   " + l9.get("comparison") + " | active=" + activeLandslideKm + " km, high=" + highLandslideKm + " km");
        System.out.println("L11 Building: " + l11Comp);
        assertEquals(0.0, activeLandslideKm, 1e-6, "Active Landslide Zone must be 0.0 km across main corridor and connecting roads");
        assertEquals(0.0, highFloodKm, 1e-6, "High Flood Zone must be 0.0 km across main corridor and connecting roads");
        assertEquals(0.0, severeFloodKm, 1e-6, "Severe (VERY_HIGH) Flood Zone must be 0.0 km across main corridor and connecting roads");
    }
}
