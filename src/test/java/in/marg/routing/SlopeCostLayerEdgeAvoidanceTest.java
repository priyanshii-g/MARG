package in.marg.routing;

import in.marg.api.CompositeRoutingController;
import in.marg.config.SlopeRoutingProperties;
import in.marg.model.GeoPoint;
import in.marg.region.CorridorIngestionService;
import in.marg.region.RegionalCorridorRegistry;
import in.marg.terrain.SlopeRasterProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlopeCostLayerEdgeAvoidanceTest {

    @Test
    void subGridCliffEdgeIsHardBlockedInStrictModeAndBarrierPenalizedInLastResortMode() {
        SlopeRasterProvider stubProvider = new SlopeRasterProvider("unused.tif") {
            @Override
            public Double sampleOrNull(double latitude, double longitude) {
                // Vertex above 30 deg when latitude > 26.5, otherwise gentle 8 deg
                return latitude > 26.5 ? 36.4 : 8.0;
            }

            @Override
            public SegmentSlopeSummary inspectSegmentInterior(
                    double lat1, double lon1, double lat2, double lon2, double limitDegrees) {
                // Simulate 2 sub-grid 30 m pixels exceeding 30 deg (peaking at 37.6 deg) between two gentle vertices
                return new SegmentSlopeSummary(7, 7, 0, 2, 37.6);
            }
        };

        SlopeRoutingProperties props = new SlopeRoutingProperties();
        SlopeCostLayer strictLayer = new SlopeCostLayer(stubProvider, props);
        SlopeCostLayer lastResortLayer = strictLayer.withEdgeBarrierLastResortMode();

        GeoPoint a = new GeoPoint(26.100, 93.400);
        GeoPoint b = new GeoPoint(26.102, 93.400);
        GeoPoint steepVertex = new GeoPoint(26.600, 93.400);

        assertTrue(strictLayer.hasEdgeCost(), "SlopeCostLayer must enable sub-grid edge evaluation");
        assertFalse(strictLayer.assess(a).blocked());
        assertFalse(strictLayer.assess(b).blocked());

        // Strict mode: sub-grid >30 deg edge must be hard-blocked
        CostAssessment strictEdge = strictLayer.assessEdge(a, b);
        assertTrue(strictEdge.blocked(), "Sub-grid >30 deg interior cliff must hard-block edge in strict mode");
        assertEquals("SLOPE_EDGE_OVER_LIMIT", strictEdge.className());

        // Last-resort mode: sub-grid >30 deg edge drops to >= 500x barrier penalty, while >30 deg vertex stays hard-blocked
        CostAssessment fallbackEdge = lastResortLayer.assessEdge(a, b);
        assertFalse(fallbackEdge.blocked(), "Last-resort mode must allow unavoidable mountain pass with extreme barrier penalty");
        assertTrue(fallbackEdge.penaltyMultiplier() >= SlopeCostLayer.OVER_LIMIT_EDGE_BARRIER_PENALTY * 0.5,
                "Barrier penalty should be extreme, got " + fallbackEdge.penaltyMultiplier());
        assertEquals("SLOPE_EDGE_OVER_LIMIT_BARRIER", fallbackEdge.className());
        assertTrue(lastResortLayer.assess(steepVertex).blocked(),
                "Vertices >30 deg must remain strictly hard-blocked even in last-resort edge mode");
    }

    @Test
    @SuppressWarnings("unchecked")
    void assamAndWesternGhatsCorridorsAvoidAllOver30DegreeSlopeViolations() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        // 1. Assam hilly corridor (Kohora -> Diphu)
        Map<String, Object> assamRoute = controller.route(
                26.5897, 93.40035, 25.84573, 93.43781,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "assam", null, null
        );
        verifyZeroOver30Slope("assam", assamRoute);

        // 2. Western Ghats escarpment corridor (default Pune -> Satara preset)
        Map<String, Object> wgRoute = controller.route(
                null, null, null, null,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "western_ghats", null, null
        );
        verifyZeroOver30Slope("western_ghats", wgRoute);
    }

    @SuppressWarnings("unchecked")
    private static void verifyZeroOver30Slope(String label, Map<String, Object> routeResponse) {
        Map<?, ?> meta = (Map<?, ?>) routeResponse.get("metadata");
        assertNotNull(meta, label + ": metadata must be present");

        Map<?, ?> diag = (Map<?, ?>) meta.get("routeDiagnostics");
        Map<?, ?> hc = (Map<?, ?>) diag.get("hardConstraintValidation");
        int slopeOver30 = ((Number) hc.get("slopeOver30ViolationSamples")).intValue();

        List<Map<String, Object>> layerAudit = (List<Map<String, Object>>) meta.get("layerAudit");
        Map<String, Object> l3 = layerAudit.stream()
                .filter(rec -> "L3_SLOPE".equals(rec.get("layerId")))
                .findFirst()
                .orElseThrow();

        List<Map<String, Object>> profile = (List<Map<String, Object>>) meta.get("routeLayerProfile");
        double maxProfileSlope = profile.stream()
                .map(p -> p.get("slopeDeg"))
                .filter(v -> v instanceof Number)
                .mapToDouble(v -> ((Number) v).doubleValue())
                .max()
                .orElse(0.0);

        System.out.printf("[%s] length=%.2f km | slopeOver30Samples=%d | maxProfileSlope=%.2f deg | L3 verdict=%s | detail=%s%n",
                label,
                ((Number) meta.get("routeLengthKm")).doubleValue(),
                slopeOver30,
                maxProfileSlope,
                l3.get("verdict"),
                ((Map<?, ?>) l3.get("hardConstraintCheck")).get("detail"));

        assertEquals(0, slopeOver30,
                label + ": Expected 0 diagnostic 125m samples > 30 deg, got " + slopeOver30);
        assertEquals("HARD_CONSTRAINT_PASS", l3.get("verdict"),
                label + ": Expected L3_SLOPE verdict HARD_CONSTRAINT_PASS, got " + l3.get("verdict"));
        assertTrue(maxProfileSlope <= 30.0,
                label + ": Expected steepest routeLayerProfile slope <= 30.0 deg, got " + maxProfileSlope);
    }
}
