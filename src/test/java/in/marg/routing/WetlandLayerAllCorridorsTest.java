package in.marg.routing;

import in.marg.api.CompositeRoutingController;
import in.marg.drainage.IndianWetlandRegistry;
import in.marg.region.CorridorIngestionService;
import in.marg.region.RegionalCorridorRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WetlandLayerAllCorridorsTest {

    @Test
    void panIndiaWetlandRegistryLoadsRichCoverageAcrossIndiaAndAssam() {
        IndianWetlandRegistry reg = IndianWetlandRegistry.getDefaultInstance();
        assertTrue(reg.allWetlands().size() >= 100,
                "Expected >= 100 Pan-India wetlands, got " + reg.allWetlands().size());

        long assamWetlands = reg.allWetlands().stream()
                .filter(w -> "Assam".equalsIgnoreCase(w.state()))
                .count();
        assertTrue(assamWetlands >= 25,
                "Expected >= 25 Assam wetlands in Pan-India registry, got " + assamWetlands);

        // Deepor Beel Ramsar Site (Guwahati, Assam)
        IndianWetlandRegistry.MappedWetland deepor = reg.findContainingWetland(26.1180, 91.6490);
        assertNotNull(deepor, "Deepor Beel Ramsar wetland must be detected at its center");
        assertTrue(deepor.isRamsar());
    }

    @Test
    @SuppressWarnings("unchecked")
    void layer13EmitsWetlandsAndDrainageAcrossAllThreeCorridorsAndPanIndia() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        // 1. Assam Tier-2 Corridor
        Map<String, Object> assam = controller.route(
                26.5897, 93.40035, 25.84573, 93.43781,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "assam", null, null
        );
        verifyLayer13("assam", assam, 30, 10);

        // 2. Western Ghats Tier-2 Corridor
        Map<String, Object> wg = controller.route(
                null, null, null, null,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "western_ghats", null, null
        );
        verifyLayer13("western_ghats", wg, 10, 10);

        // 3. Gangetic Plains Tier-2 Corridor
        Map<String, Object> gp = controller.route(
                null, null, null, null,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "gangetic_plains", null, null
        );
        verifyLayer13("gangetic_plains", gp, 10, 10);

        // 4. Pan-India Dynamic Corridor (Guwahati -> Nagaon in Assam, passing Deepor Beel / Pobitora / Samaguri wetlands)
        Map<String, Object> panIndiaAssam = controller.route(
                26.150, 91.550, 26.380, 92.800,
                250.0, 0.20, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "pan_india", "TIER_1_DYNAMIC_PAN_INDIA", null
        );
        verifyLayer13("pan_india_assam", panIndiaAssam, 4, 1);
    }

    @SuppressWarnings("unchecked")
    private static void verifyLayer13(String label, Map<String, Object> resp, int minWetlandPolygons, int minDrainageChannels) {
        List<Map<String, Object>> features = (List<Map<String, Object>>) resp.get("features");
        assertNotNull(features, label + ": features must be present");

        long wetlandPolys = features.stream()
                .filter(f -> "CORRIDOR_WETLAND_POLYGON".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                .count();
        long drainageChannels = features.stream()
                .filter(f -> "CORRIDOR_DRAINAGE_CHANNEL".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                .count();
        long culverts = features.stream()
                .filter(f -> "DRAINAGE_CROSSING_STRUCTURE".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                .count();

        Map<?, ?> meta = (Map<?, ?>) resp.get("metadata");
        List<Map<String, Object>> audit = (List<Map<String, Object>>) meta.get("layerAudit");
        Map<String, Object> l13 = audit.stream()
                .filter(r -> "L13_DRAINAGE_WETLANDS".equals(r.get("layerId")))
                .findFirst()
                .orElseThrow();

        Map<?, ?> hc = (Map<?, ?>) l13.get("hardConstraintCheck");
        String detail = hc != null ? String.valueOf(hc.get("detail")) : "";
        String headline = String.valueOf(l13.get("headlineProof"));

        System.out.printf("[%s] wetlandPolygons=%d | drainageChannels=%d | culverts=%d | verdict=%s | detail=%s | headline=%s%n",
                label, wetlandPolys, drainageChannels, culverts, l13.get("verdict"), detail, headline);

        assertTrue(wetlandPolys >= minWetlandPolygons,
                label + ": Expected >= " + minWetlandPolygons + " CORRIDOR_WETLAND_POLYGON features, got " + wetlandPolys);
        assertTrue(drainageChannels >= minDrainageChannels,
                label + ": Expected >= " + minDrainageChannels + " CORRIDOR_DRAINAGE_CHANNEL features, got " + drainageChannels);
        assertFalse(headline.contains("Zero Drainage obstruction risk index (0.0 pts)"),
                label + ": Layer 13 headline must not report empty 'Zero Drainage obstruction risk index (0.0 pts)'");
        assertTrue(detail.contains("screened") && detail.contains("wetland"),
                label + ": Layer 13 hardCheck detail should cite screened mapped wetlands, got: " + detail);
    }
}
