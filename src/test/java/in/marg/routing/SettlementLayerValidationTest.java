package in.marg.routing;

import in.marg.bhuvan.BhuvanCatalog;
import in.marg.bhuvan.BhuvanFeatureInfoClient;
import in.marg.bhuvan.BhuvanLulcSampler;
import in.marg.config.FloodRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.SettlementRoutingProperties;
import in.marg.config.SlopeRoutingProperties;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.flood.FloodHazardClass;
import in.marg.floodraster.BhuvanFloodRasterCache;
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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementLayerValidationTest {

    private final CoordinateProjection projection = new Wgs84UtmProjection(46);
    private final GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
    private final StudyRegion fullRegion = new StudyRegion(
            "Kaziranga–Karbi Anglong, Assam",
            25.75, 93.10, 26.65, 93.75,
            new GeoPoint(26.58970, 93.40035),
            new GeoPoint(25.84573, 93.43781)
    );

    @Test
    void validateSettlementLayerSamplingIsolatedAndCompositeRouting() throws Exception {
        SettlementRasterProvider settlementProvider = new SettlementRasterProvider(
                "data/processed/settlements/assam_settlement_exposure_utm46.tif"
        );
        SettlementRoutingProperties settlementProps = new SettlementRoutingProperties();
        SettlementCostLayer settlementLayer = new SettlementCostLayer(settlementProvider, settlementProps);

        System.out.println("=== 1. INDEPENDENT SETTLEMENT POINT SAMPLING ===");
        assertEquals(SettlementExposureClass.URBAN_CORE, settlementProvider.sampleClass(25.8400, 93.4300));
        assertEquals(SettlementExposureClass.URBAN_CORE, settlementProvider.sampleClass(26.6380, 93.5980));
        assertEquals(SettlementExposureClass.RURAL_CORE, settlementProvider.sampleClass(25.9680, 93.3820));
        assertEquals(SettlementExposureClass.RURAL_CORE, settlementProvider.sampleClass(26.0120, 93.3680));
        assertEquals(SettlementExposureClass.NONE, settlementProvider.sampleClass(26.3000, 93.3500));

        double[][] sampleCoords = {
                {25.8400, 93.4300}, // Diphu Town Core
                {26.6380, 93.5980}, // Bokakhat Town Core
                {25.9680, 93.3820}, // Borjan Village Core
                {26.0120, 93.3680}, // Longnit Village Core
                {25.9250, 93.4400}, // Lahorijan Village Core
                {25.8780, 93.4380}, // Birla Village Core
                {26.58970, 93.40035}, // Kohora Origin
                {25.84573, 93.43781}, // Diphu Destination
                {26.3000, 93.3500}  // Uninhabited Hill/Forest
        };
        for (double[] c : sampleCoords) {
            GeoPoint p = new GeoPoint(c[0], c[1]);
            SettlementExposureClass cls = settlementProvider.sampleClass(c[0], c[1]);
            CostAssessment ca = settlementLayer.assess(p);
            System.out.printf("Point (%.5f, %.5f) -> class=%-14s | assessment=%-24s | penalty=%.2f | blocked=%s%n",
                    c[0], c[1], cls, ca.className(), ca.penaltyMultiplier(), ca.blocked());
            assertFalse(ca.blocked());
        }

        // === 2. ISOLATED SETTLEMENT ROUTING TEST ===
        System.out.println("\n=== 2. ISOLATED SETTLEMENT ROUTING (DISTANCE vs SETTLEMENT_ONLY) ===");
        StudyRegion corridor = corridorWindow(fullRegion, fullRegion.origin(), fullRegion.destination(), 0.15);
        List<GeoPoint> distBaseline = router.route(fullRegion, fullRegion.origin(), fullRegion.destination(),
                new DistanceOnlyCostSurface(), 250.0, corridor);
        List<GeoPoint> settlementOnly = router.route(fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(settlementLayer)), 250.0, corridor);

        Map<String, Object> distSettleMetrics = summarizeSettlementAlongRoute(distBaseline, settlementProvider, settlementProps.toPolicy());
        Map<String, Object> settleOnlyMetrics = summarizeSettlementAlongRoute(settlementOnly, settlementProvider, settlementProps.toPolicy());
        System.out.println("DISTANCE_BASELINE (Kohora->Diphu): " + distSettleMetrics);
        System.out.println("SETTLEMENT_ONLY   (Kohora->Diphu): " + settleOnlyMetrics);
        assertTrue(((Number) settleOnlyMetrics.get("settlementPenaltyKmEq")).doubleValue()
                < ((Number) distSettleMetrics.get("settlementPenaltyKmEq")).doubleValue(),
                "Settlement-only routing must reduce settlement penalty compared to distance baseline");

        // === 3. 4-LAYER vs 5-LAYER COMPOSITE COMPARISON ===
        System.out.println("\n=== 3. COMPOSITE BEFORE (4-LAYER) vs AFTER (5-LAYER) ===");
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

        CompositeRouteMetricsService metricsService = new CompositeRouteMetricsService(
                projection, lulcProvider, lulcProps, floodCache, floodProps,
                slopeProvider, slopeProps, paProvider, settlementProvider, settlementProps
        );

        CostSurface surface4Layer = new CompositeCostSurface(List.of(
                lulcLayer, floodLayer, slopeLayer, paLayer
        ));
        CostSurface surface5Layer = new CompositeCostSurface(List.of(
                lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer
        ));

        List<GeoPoint> route4 = router.route(fullRegion, fullRegion.origin(), fullRegion.destination(),
                surface4Layer, 250.0, corridor);
        List<GeoPoint> route5 = router.route(fullRegion, fullRegion.origin(), fullRegion.destination(),
                surface5Layer, 250.0, corridor);

        Map<String, Object> diag4 = metricsService.summarize(route4);
        Map<String, Object> diag5 = metricsService.summarize(route5);

        System.out.println("4-LAYER COMPOSITE DIAGNOSTICS: " + diag4);
        System.out.println("5-LAYER COMPOSITE DIAGNOSTICS: " + diag5);

        @SuppressWarnings("unchecked")
        Map<String, Double> pen4 = (Map<String, Double>) diag4.get("approximatePenaltyContributionKm");
        @SuppressWarnings("unchecked")
        Map<String, Double> pen5 = (Map<String, Double>) diag5.get("approximatePenaltyContributionKm");

        assertTrue(pen5.get("SETTLEMENT") < pen4.get("SETTLEMENT"),
                "5-layer composite route must have lower settlement penalty than 4-layer route");
    }

    private Map<String, Object> summarizeSettlementAlongRoute(
            List<GeoPoint> route,
            SettlementRasterProvider provider,
            SettlementCostPolicy policy) {

        EnumMap<SettlementExposureClass, Double> kmByClass = new EnumMap<>(SettlementExposureClass.class);
        double totalKm = 0.0;
        double penaltyKm = 0.0;
        for (int i = 1; i < route.size(); i++) {
            MetricPoint a = projection.forward(route.get(i - 1));
            MetricPoint b = projection.forward(route.get(i));
            double segM = Math.hypot(b.x() - a.x(), b.y() - a.y());
            if (segM <= 0) continue;
            int parts = Math.max(1, (int) Math.ceil(segM / 125.0));
            double partKm = (segM / parts) / 1000.0;
            for (int j = 0; j < parts; j++) {
                double t = (j + 0.5) / parts;
                GeoPoint p = projection.inverse(new MetricPoint(a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y())));
                SettlementExposureClass c = provider.sampleClass(p.lat(), p.lon());
                kmByClass.merge(c, partKm, Double::sum);
                totalKm += partKm;
                penaltyKm += partKm * policy.penalty(c);
            }
        }
        Map<String, Double> exposure = new LinkedHashMap<>();
        for (var e : kmByClass.entrySet()) {
            exposure.put(e.getKey().name(), Math.round(e.getValue() * 100.0) / 100.0);
        }
        return Map.of(
                "routeLengthKm", Math.round(totalKm * 100.0) / 100.0,
                "vertices", route.size(),
                "settlementExposureKm", exposure,
                "settlementPenaltyKmEq", Math.round(penaltyKm * 100.0) / 100.0
        );
    }

    private static StudyRegion corridorWindow(StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg);
        return new StudyRegion(full.name() + " / validation window", minLat, minLon, maxLat, maxLon, a, b);
    }
}
