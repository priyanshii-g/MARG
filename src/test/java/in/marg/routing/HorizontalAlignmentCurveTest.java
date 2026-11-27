package in.marg.routing;

import in.marg.api.CompositeRoutingController;
import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.region.CorridorIngestionService;
import in.marg.region.RegionalCorridorRegistry;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.spatial.Wgs84UtmProjection;
import in.marg.transport.FacilityMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HorizontalAlignmentCurveTest {

    @Test
    void smoothsSharpNinetyDegreeCornerIntoCompliantExpresswayCircularArc() {
        CoordinateProjection proj = new Wgs84UtmProjection(46);
        GeoPoint start = new GeoPoint(26.50, 93.40);
        MetricPoint mStart = proj.forward(start);
        GeoPoint corner = proj.inverse(new MetricPoint(mStart.x() + 3000.0, mStart.y()));
        GeoPoint goal = proj.inverse(new MetricPoint(mStart.x() + 3000.0, mStart.y() + 3000.0));

        List<GeoPoint> lBend = List.of(start, corner, goal);
        HorizontalCurveSmoother.SmoothingResult result = HorizontalCurveSmoother.smoothAndAudit(
                lBend,
                new DistanceOnlyCostSurface(),
                null,
                proj,
                null,
                HorizontalCurveSmoother.CurveProfile.EXPRESSWAY);

        HorizontalCurveSmoother.HorizontalGeometryReport report = result.report();
        assertTrue(report.compliant(), "90-degree corner must be smoothed to IRC Expressway compliance");
        assertEquals(0, report.sharpCornerViolationsCount(), "Must eliminate sharp >45° corner");
        assertTrue(report.maxLocalDeflectionDegrees() < 15.0,
                "Expected smooth circular arc deflections < 15°, got " + report.maxLocalDeflectionDegrees());
        assertTrue(report.smoothCurvesFittedCount() >= 1, "Must fit at least 1 circular arc");
        assertTrue(report.estimatedMinCurveRadiusMeters() >= 670.0,
                "Expected IRC:SP:99-2013 compliant radius >= 670 m, got " + report.estimatedMinCurveRadiusMeters());
        assertEquals(start.lat(), result.smoothedPath().get(0).lat(), 1e-6);
        assertEquals(goal.lat(), result.smoothedPath().get(result.smoothedPath().size() - 1).lat(), 1e-6);
    }

    @Test
    void astarTurnDisciplineAndSmoothingAvoidHardBlockWithoutSharpCorners() {
        CoordinateProjection proj = new Wgs84UtmProjection(46);
        GreenfieldAStarRouter router = new GreenfieldAStarRouter(proj);
        GeoPoint start = new GeoPoint(26.50, 93.38);
        GeoPoint goal = new GeoPoint(26.50, 93.52);
        StudyRegion region = new StudyRegion("synthetic", 26.45, 93.35, 26.58, 93.55, start, goal);

        // Rectangular hard obstacle sitting directly across the straight line
        CostSurface obstacleSurface = pt -> {
            if (pt.lat() >= 26.49 && pt.lat() <= 26.52 && pt.lon() >= 93.43 && pt.lon() <= 93.47) {
                return new CostAssessment(true, Double.POSITIVE_INFINITY, "synthetic", "OBSTACLE");
            }
            return CostAssessment.free("OPEN");
        };

        List<GeoPoint> raw = router.route(
                region, start, goal, obstacleSurface, 250.0, region,
                RouteConstraints.forFacilityMode(null, FacilityMode.EXPRESSWAY));

        HorizontalCurveSmoother.SmoothingResult smoothed = HorizontalCurveSmoother.smoothAndAudit(
                raw, obstacleSurface, null, proj, region, HorizontalCurveSmoother.CurveProfile.EXPRESSWAY);

        assertEquals(0, smoothed.report().sharpCornerViolationsCount(),
                "Expected zero sharp corners after turn-disciplined A* + smoothing");
        assertTrue(smoothed.report().maxLocalDeflectionDegrees() <= 45.0,
                "Expected max deflection <= 45°, got " + smoothed.report().maxLocalDeflectionDegrees());
        for (GeoPoint pt : smoothed.smoothedPath()) {
            assertTrue(!obstacleSurface.assess(pt).blocked(),
                    "Smoothed curve must never intrude into hard-blocked obstacle at " + pt);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void assamAndGangeticRoutesMeetIrcHorizontalGeometryWithZeroConstraintRegressions() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        // 1. Assam Expressway with Dimapur feeder spur
        Map<String, Object> assamRes = controller.route(
                26.5897, 93.40035, 25.84573, 93.43781,
                250.0, 0.15, 2000, 20.0,
                "EXPRESSWAY", "Dimapur", null, 0.85,
                true, 2,
                "assam", null, null
        );
        Map<String, Object> assamMeta = (Map<String, Object>) assamRes.get("metadata");
        Map<String, Object> assamGeom = (Map<String, Object>) assamMeta.get("horizontalGeometryAudit");
        assertNotNull(assamGeom, "Assam route must emit horizontalGeometryAudit");
        System.out.println("ASSAM_EXPRESSWAY_GEOM: " + assamGeom);

        assertEquals(0, ((Number) assamGeom.get("sharpCornerViolationsCount")).intValue(),
                "Assam Expressway must have 0 sharp >45° corners");
        assertTrue(((Number) assamGeom.get("maxLocalDeflectionDegrees")).doubleValue() <= 45.0,
                "Assam Expressway max bend must be <= 45°");
        assertTrue(((Number) assamGeom.get("smoothCurvesFittedCount")).intValue() > 0,
                "Assam Expressway must have smooth circular arcs fitted");

        // Verify zero hard-constraint regressions on Assam
        List<Map<String, Object>> assamLayers = (List<Map<String, Object>>) assamMeta.get("layerAudit");
        assertEquals("HARD_CONSTRAINT_PASS", assamLayers.get(3).get("verdict"), "L4 Protected Areas must pass");
        assertEquals("HARD_CONSTRAINT_PASS", assamLayers.get(5).get("verdict"), "L6 Transport & Curves must pass");
        Map<String, Object> l6Audit = assamLayers.get(5);
        assertTrue(String.valueOf(l6Audit.get("headlineProof")).contains("Smooth IRC curves fitted"),
                "Layer 6 headlineProof must report smooth IRC curves: " + l6Audit.get("headlineProof"));

        // Verify zero Direct-RoW buildings regression & zero >30 deg slope regression on Assam
        System.out.println("ASSAM_L3_SLOPE_AUDIT: " + assamLayers.get(2).get("verdict") + " | " + assamLayers.get(2).get("hardConstraintCheck"));
        assertEquals("HARD_CONSTRAINT_PASS", assamLayers.get(2).get("verdict"), "L3 Slope must pass: " + assamLayers.get(2).get("hardConstraintCheck"));
        Map<String, Object> l11Comp = (Map<String, Object>) assamLayers.get(10).get("comparison");
        assertEquals(0.0, ((Number) l11Comp.get("routeValue")).doubleValue(), 1e-6,
                "Assam route must keep 0 Direct-RoW building hits after curve smoothing");

        // 2. Gangetic Plains Expressway
        Map<String, Object> gangeticRes = controller.route(
                26.925, 81.185, 26.785, 82.140,
                250.0, 0.15, 2000, 20.0,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "gangetic_plains", null, null
        );
        Map<String, Object> gangeticMeta = (Map<String, Object>) gangeticRes.get("metadata");
        Map<String, Object> gangeticGeom = (Map<String, Object>) gangeticMeta.get("horizontalGeometryAudit");
        assertNotNull(gangeticGeom, "Gangetic route must emit horizontalGeometryAudit");
        System.out.println("GANGETIC_EXPRESSWAY_GEOM: " + gangeticGeom);
        assertEquals(0, ((Number) gangeticGeom.get("sharpCornerViolationsCount")).intValue(),
                "Gangetic Expressway must have 0 sharp >45° corners");
    }
}
