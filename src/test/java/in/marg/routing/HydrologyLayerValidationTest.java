package in.marg.routing;

import in.marg.bhuvan.BhuvanCatalog;
import in.marg.bhuvan.BhuvanFeatureInfoClient;
import in.marg.bhuvan.BhuvanLulcSampler;
import in.marg.config.FloodRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.SettlementRoutingProperties;
import in.marg.config.SlopeRoutingProperties;
import in.marg.config.TransportRoutingProperties;
import in.marg.config.WaterBodyRoutingProperties;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.floodraster.BhuvanFloodRasterCache;
import in.marg.hydrology.RiverHierarchyClass;
import in.marg.hydrology.RiverNetworkVectorIndex;
import in.marg.hydrology.WaterBodyClass;
import in.marg.hydrology.WaterBodyRasterProvider;
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

class HydrologyLayerValidationTest {

    private final CoordinateProjection projection = new Wgs84UtmProjection(46);
    private final GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
    private final StudyRegion fullRegion = new StudyRegion(
            "Kaziranga–Karbi Anglong, Assam",
            25.75, 93.10, 26.65, 93.75,
            new GeoPoint(26.58970, 93.40035),
            new GeoPoint(25.84573, 93.43781)
    );

    @Test
    void validateIndependentHydrologyRasterSamplingHydraulicBridgeSkewAndWetlandBlocking() {
        WaterBodyRasterProvider waterRaster = new WaterBodyRasterProvider(
                "data/processed/hydrology/assam_water_bodies_utm46.tif"
        );
        RiverNetworkVectorIndex riverIndex = new RiverNetworkVectorIndex(
                projection,
                "data/processed/hydrology/assam_perennial_rivers.geojson"
        );
        WaterBodyRoutingProperties props = new WaterBodyRoutingProperties();
        WaterBodyCostLayer waterLayer = new WaterBodyCostLayer(
                waterRaster, riverIndex, projection, props
        );

        System.out.println("=== 1. HYDROLOGY RASTER & RIVER CROSSING HYDRAULICS VALIDATION ===");
        assertEquals(10, riverIndex.allRivers().size());

        // Independent GeoTIFF point sampling across all water-body hierarchy classes
        assertEquals(WaterBodyClass.MAJOR_PERENNIAL_RIVER,
                waterRaster.sampleClass(26.3930, 93.4050)); // Kaliani River (140 m channel)
        assertEquals(WaterBodyClass.MAJOR_PERENNIAL_RIVER,
                waterRaster.sampleClass(26.2450, 93.6580)); // Dhansiri River (240 m channel)
        assertEquals(WaterBodyClass.MEDIUM_PERENNIAL_RIVER,
                waterRaster.sampleClass(26.1500, 93.3500)); // Deopani River (70 m channel)
        assertEquals(WaterBodyClass.MINOR_PERENNIAL_STREAM,
                waterRaster.sampleClass(26.5720, 93.3920)); // Kohora-Mora Diphlu Stream (38 m)
        assertEquals(WaterBodyClass.INLAND_WETLAND_WATERBODY,
                waterRaster.sampleClass(25.7750, 93.2750)); // Bhuvan LU_Webcode=20 Inland Wetland / Beel
        assertEquals(WaterBodyClass.NONE,
                waterRaster.sampleClass(26.3000, 93.3400)); // Dry hill interior

        // Verify origin & destination sit on dry land (NONE)
        assertEquals(WaterBodyClass.NONE,
                waterRaster.sampleClass(fullRegion.origin().lat(), fullRegion.origin().lon()));
        assertEquals(WaterBodyClass.NONE,
                waterRaster.sampleClass(fullRegion.destination().lat(), fullRegion.destination().lon()));

        // Verify blockWetlands toggle (soft penalty by default vs hard constraint when enabled)
        GeoPoint wetlandPt = new GeoPoint(25.7750, 93.2750);
        CostAssessment softWetland = waterLayer.assess(wetlandPt);
        assertFalse(softWetland.blocked());
        assertEquals(3.40, softWetland.penaltyMultiplier(), 1e-6);

        WaterBodyRoutingProperties strictWetlandProps = new WaterBodyRoutingProperties();
        strictWetlandProps.setBlockWetlands(true);
        WaterBodyCostLayer strictWetlandLayer = new WaterBodyCostLayer(
                waterRaster, riverIndex, projection, strictWetlandProps
        );
        assertTrue(strictWetlandLayer.assess(wetlandPt).blocked(),
                "Inland wetlands/beels must be hard-blocked when blockWetlands=true");

        // Verify 90-degree perpendicular river bridge vs <20-degree acute skew bridge across Kaliani River
        GeoPoint northBankPerp = new GeoPoint(26.3960, 93.4050);
        GeoPoint southBankPerp = new GeoPoint(26.3900, 93.4050);
        var perpCrossings = riverIndex.findStepCrossings(northBankPerp, southBankPerp);
        assertEquals(1, perpCrossings.size());
        assertEquals(RiverHierarchyClass.MAJOR_PERENNIAL_RIVER, perpCrossings.get(0).riverClass());
        assertTrue(perpCrossings.get(0).crossingAngleDegrees() > 82.0,
                "North-south bridge across east-west Kaliani River should be near-perpendicular (>82 deg)");

        GeoPoint acuteBankA = new GeoPoint(26.3942, 93.3960);
        GeoPoint acuteBankB = new GeoPoint(26.3918, 93.4140);
        var acuteCrossings = riverIndex.findStepCrossings(acuteBankA, acuteBankB);
        assertEquals(1, acuteCrossings.size());
        assertTrue(acuteCrossings.get(0).crossingAngleDegrees() < 20.0,
                "Shallow diagonal crossing of Kaliani River should have acute angle < 20 deg");

        double perpSpanM = perpCrossings.get(0).effectiveSkewSpanMeters();
        double acuteSpanM = acuteCrossings.get(0).effectiveSkewSpanMeters();
        double perpStepKm = projection.distanceMeters(northBankPerp, southBankPerp) / 1000.0;
        double acuteStepKm = projection.distanceMeters(acuteBankA, acuteBankB) / 1000.0;
        double perpBridgeCostKmEq = waterLayer.assessEdge(northBankPerp, southBankPerp).penaltyMultiplier() * perpStepKm;
        double acuteBridgeCostKmEq = waterLayer.assessEdge(acuteBankA, acuteBankB).penaltyMultiplier() * acuteStepKm;

        System.out.printf("Kaliani River Perpendicular Bridge (angle=%.1f deg, span=%.1f m) -> cost=%.2f km-eq%n",
                perpCrossings.get(0).crossingAngleDegrees(), perpSpanM, perpBridgeCostKmEq);
        System.out.printf("Kaliani River Acute Skew Bridge    (angle=%.1f deg, span=%.1f m) -> cost=%.2f km-eq%n",
                acuteCrossings.get(0).crossingAngleDegrees(), acuteSpanM, acuteBridgeCostKmEq);

        assertTrue(acuteSpanM > perpSpanM * 2.5,
                "Acute skew river crossing must inflate effective bridge deck span >2.5x");
        assertTrue(acuteBridgeCostKmEq > perpBridgeCostKmEq * 2.8,
                "Acute skew river crossing must incur >2.8x hydraulic scour & span penalty");
    }

    @Test
    void validateIsolatedHydrologyRoutingAndFull6LayerVs7LayerComposite() throws Exception {
        WaterBodyRasterProvider waterRaster = new WaterBodyRasterProvider(
                "data/processed/hydrology/assam_water_bodies_utm46.tif"
        );
        RiverNetworkVectorIndex riverIndex = new RiverNetworkVectorIndex(
                projection,
                "data/processed/hydrology/assam_perennial_rivers.geojson"
        );
        WaterBodyRoutingProperties waterProps = new WaterBodyRoutingProperties();
        WaterBodyCostLayer waterLayer = new WaterBodyCostLayer(
                waterRaster, riverIndex, projection, waterProps
        );

        TransportNetworkRasterProvider transportRaster = new TransportNetworkRasterProvider(
                "data/processed/transport/assam_transport_network_utm46.tif"
        );
        TransportNetworkVectorIndex transportIndex = new TransportNetworkVectorIndex(
                projection,
                "data/processed/transport/assam_transport_network.geojson",
                "data/processed/transport/assam_regional_hubs.geojson"
        );
        TransportRoutingProperties transportProps = new TransportRoutingProperties();

        // Load all 5 base environmental & social layers
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
                transportRaster, transportIndex, transportProps,
                waterRaster, riverIndex, waterProps
        );

        StudyRegion corridor = corridorWindow(fullRegion, fullRegion.origin(), fullRegion.destination(), 0.15);

        // === 2. ISOLATED HYDROLOGY LAYER ROUTING (DISTANCE_BASELINE vs HYDROLOGY_ONLY) ===
        System.out.println("\n=== 2. ISOLATED HYDROLOGY ROUTING (DISTANCE_BASELINE vs HYDROLOGY_ONLY) ===");
        List<GeoPoint> distBaseline = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new DistanceOnlyCostSurface(), 250.0, corridor
        );
        List<GeoPoint> hydrologyOnly = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(waterLayer)), 250.0, corridor
        );

        Map<String, Object> distDiag = metricsService.summarize(distBaseline);
        Map<String, Object> hydroOnlyDiag = metricsService.summarize(hydrologyOnly);
        @SuppressWarnings("unchecked")
        double distHydroPen = ((Map<String, Double>) distDiag.get("approximatePenaltyContributionKm"))
                .get("HYDROLOGY_WATER");
        @SuppressWarnings("unchecked")
        double isolatedHydroPen = ((Map<String, Double>) hydroOnlyDiag.get("approximatePenaltyContributionKm"))
                .get("HYDROLOGY_WATER");

        System.out.println("DISTANCE_BASELINE Hydrology: len=" + distDiag.get("routeLengthKm")
                + " km | pen=" + distHydroPen + " km-eq | bridges=" + distDiag.get("riverBridgeCrossingSummary")
                + " | exposure=" + distDiag.get("waterBodyExposureKm"));
        System.out.println("HYDROLOGY_ONLY    Hydrology: len=" + hydroOnlyDiag.get("routeLengthKm")
                + " km | pen=" + isolatedHydroPen + " km-eq | bridges=" + hydroOnlyDiag.get("riverBridgeCrossingSummary")
                + " | exposure=" + hydroOnlyDiag.get("waterBodyExposureKm"));

        assertTrue(isolatedHydroPen < distHydroPen,
                "Isolated hydrology-aware routing must reduce total river bridge skew & riparian overlap penalty");

        // === 3. FULL COMPOSITE 6-LAYER (BEFORE) vs 7-LAYER (AFTER: EXPRESSWAY & NATIONAL_HIGHWAY) ===
        System.out.println("\n=== 3. COMPOSITE 6-LAYER (BEFORE) vs 7-LAYER (AFTER) ===");
        HubEvaluationService hubService = new HubEvaluationService(transportIndex, projection, transportProps);
        HighwayNetworkPlannerService plannerService = new HighwayNetworkPlannerService(
                transportIndex, transportRaster, slopeProvider, paProvider,
                router, projection, transportProps,
                waterRaster, riverIndex, waterProps
        );

        for (FacilityMode mode : List.of(FacilityMode.EXPRESSWAY, FacilityMode.NATIONAL_HIGHWAY)) {
            var hubEval = hubService.evaluate(
                    fullRegion.origin(), fullRegion.destination(), corridor,
                    mode, List.of(), null, null
            );
            TransportNetworkCostLayer modeTransportLayer = new TransportNetworkCostLayer(
                    transportRaster, transportIndex, projection, transportProps.toPolicy(),
                    transportProps.accessibilityPullFor(mode),
                    hubEval.approvedHubs(),
                    fullRegion.origin(), fullRegion.destination(),
                    transportProps.getCollectorSweetSpotMaxKm(),
                    transportProps.getCollectorLongitudinalBandKm()
            );

            // 6-Layer (Before Hydrology)
            List<GeoPoint> route6 = router.route(
                    fullRegion, fullRegion.origin(), fullRegion.destination(),
                    new CompositeCostSurface(List.of(
                            lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer, modeTransportLayer)),
                    250.0, corridor
            );
            Map<String, Object> diag6 = metricsService.summarize(route6);

            // 7-Layer (After Hydrology)
            List<CostLayer> spurTerrainSocialHydroLayers = List.of(
                    lulcLayer, floodLayer, slopeLayer, paLayer, settlementLayer, waterLayer
            );
            List<GeoPoint> route7 = router.route(
                    fullRegion, fullRegion.origin(), fullRegion.destination(),
                    new CompositeCostSurface(List.of(
                            lulcLayer, floodLayer, slopeLayer, paLayer,
                            settlementLayer, modeTransportLayer, waterLayer)),
                    250.0, corridor
            );
            Map<String, Object> diag7 = metricsService.summarize(route7);
            var plan7 = plannerService.planNetwork(fullRegion, route7, hubEval, spurTerrainSocialHydroLayers);

            @SuppressWarnings("unchecked")
            double pen6 = ((Map<String, Double>) diag6.get("approximatePenaltyContributionKm"))
                    .get("HYDROLOGY_WATER");
            @SuppressWarnings("unchecked")
            double pen7 = ((Map<String, Double>) diag7.get("approximatePenaltyContributionKm"))
                    .get("HYDROLOGY_WATER");

            System.out.println("\n--- MODE: " + mode.name() + " ---");
            System.out.println("6-LAYER (BEFORE) : len=" + diag6.get("routeLengthKm")
                    + " km | hydroPen=" + pen6 + " km-eq | bridges=" + diag6.get("riverBridgeCrossingSummary")
                    + " | waterExposure=" + diag6.get("waterBodyExposureKm")
                    + " | hc=" + diag6.get("hardConstraintValidation")
                    + " | allPenalties=" + diag6.get("approximatePenaltyContributionKm"));
            System.out.println("7-LAYER (AFTER)  : len=" + diag7.get("routeLengthKm")
                    + " km | hydroPen=" + pen7 + " km-eq | bridges=" + diag7.get("riverBridgeCrossingSummary")
                    + " | waterExposure=" + diag7.get("waterBodyExposureKm")
                    + " | hc=" + diag7.get("hardConstraintValidation")
                    + " | allPenalties=" + diag7.get("approximatePenaltyContributionKm"));
            System.out.println("7-LAYER BRIDGES  : " + plan7.riverBridges().stream()
                    .map(b -> b.riverName() + " (" + b.structureType() + ", span=" + b.estimatedSkewSpanMeters()
                            + "m, angle=" + b.crossingAngleDegrees() + "deg @ ch=" + b.chainageKm() + "km)")
                    .toList());
            System.out.println("7-LAYER SPURS    : " + plan7.feederSpurs().stream()
                    .map(s -> s.hub().name() + ":" + s.spurLengthKm() + "km->" + s.hub().tieInRoadRef()).toList()
                    + " | V/C=" + plan7.projectedVcRatio() + " (" + plan7.levelOfService() + ")");

            assertTrue(pen7 <= pen6,
                    "7-Layer composite route in " + mode + " mode must reduce or match 6-Layer hydrology penalty");

            @SuppressWarnings("unchecked")
            Map<String, Object> hc6 = (Map<String, Object>) diag6.get("hardConstraintValidation");
            @SuppressWarnings("unchecked")
            Map<String, Object> hc7 = (Map<String, Object>) diag7.get("hardConstraintValidation");
            assertTrue(
                    ((Number) hc7.get("protectedAreaViolationSamples")).intValue()
                            <= ((Number) hc6.get("protectedAreaViolationSamples")).intValue(),
                    "7-Layer mainline must not increase sub-grid Protected Area boundary samples vs 6-Layer");
            assertEquals(0, hc7.get("protectedAreaNoDataSamples"));
            assertEquals(0, hc7.get("slopeNoDataViolationSamples"));

            // Every 250 m routing vertex on the 7-Layer mainline must strictly satisfy PA==0 and Slope<=30 deg
            for (GeoPoint v : route7) {
                Double pa = paProvider.sampleOrNull(v.lat(), v.lon());
                Double slp = slopeProvider.sampleOrNull(v.lat(), v.lon());
                assertTrue(pa != null && Math.round(pa) == 0L,
                        "Mainline vertex must not enter Protected Area: " + v);
                assertTrue(slp != null && slp <= 30.0,
                        "Mainline vertex must satisfy Slope <= 30 deg: " + v + " (slope=" + slp + ")");
            }

            assertFalse(plan7.riverBridges().isEmpty(),
                    "North-south Kohora->Diphu corridor crosses major east-west basins (Kaliani, Deopani, Jamuna) and must emit planned river bridges");

            // Ensure no highway interchange is placed inside a river channel or wetland
            for (var ic : plan7.interchanges()) {
                WaterBodyClass icWater = waterRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(
                        icWater == WaterBodyClass.MAJOR_PERENNIAL_RIVER
                                || icWater == WaterBodyClass.MEDIUM_PERENNIAL_RIVER
                                || icWater == WaterBodyClass.MINOR_PERENNIAL_STREAM
                                || icWater == WaterBodyClass.INLAND_WETLAND_WATERBODY,
                        "Interchange " + ic.interchangeId() + " must never sit inside a river channel or wetland");
            }

            // Ensure all dedicated A* spurs pass vertex hard constraints & Protected Area checks and connect outside URBAN_CORE
            for (int i = 0; i < plan7.feederSpurs().size(); i++) {
                var ic = plan7.interchanges().get(i);
                var spur = plan7.feederSpurs().get(i);
                assertEquals(ic.point(), spur.path().get(0));
                assertEquals(spur.hub().bypassTieIn(), spur.path().get(spur.path().size() - 1));
                assertFalse(
                        settlementProvider.sampleClass(
                                spur.hub().bypassTieIn().lat(),
                                spur.hub().bypassTieIn().lon()) == SettlementExposureClass.URBAN_CORE);
                Map<String, Object> spurDiag = metricsService.summarize(spur.path());
                @SuppressWarnings("unchecked")
                Map<String, Object> spurHc = (Map<String, Object>) spurDiag.get("hardConstraintValidation");
                assertEquals(0, spurHc.get("protectedAreaViolationSamples"),
                        "7-Layer dedicated spur for " + spur.hub().name() + " must have 0 Protected Area violations");
                for (GeoPoint sv : spur.path()) {
                    Double pa = paProvider.sampleOrNull(sv.lat(), sv.lon());
                    Double slp = slopeProvider.sampleOrNull(sv.lat(), sv.lon());
                    assertTrue(pa != null && Math.round(pa) == 0L);
                    assertTrue(slp != null && slp <= 30.0);
                }
            }

            // Verify Multi-Feature GeoJSON output includes RIVER_BRIDGE features
            List<Map<String, Object>> geoJsonFeatures =
                    plannerService.buildGeoJsonFeatures(route7, Map.of("profile", "COMPOSITE_7_LAYER"), plan7);
            long riverBridgeFeatures = geoJsonFeatures.stream()
                    .filter(f -> "RIVER_BRIDGE".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                    .count();
            assertEquals(plan7.riverBridges().size(), riverBridgeFeatures,
                    "GeoJSON FeatureCollection must emit a RIVER_BRIDGE Point feature for every planned river crossing");
        }
    }

    private static StudyRegion corridorWindow(StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg);
        return new StudyRegion(full.name() + " / hydrology validation window", minLat, minLon, maxLat, maxLon, a, b);
    }
}
