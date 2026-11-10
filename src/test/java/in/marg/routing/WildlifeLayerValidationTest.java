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
import in.marg.config.WildlifeRoutingProperties;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.floodraster.BhuvanFloodRasterCache;
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
import in.marg.wildlife.WildlifeCorridorTier;
import in.marg.wildlife.WildlifeCorridorVectorIndex;
import in.marg.wildlife.WildlifeRasterProvider;
import in.marg.wildlife.WildlifeZoneClass;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WildlifeLayerValidationTest {

    private final CoordinateProjection projection = new Wgs84UtmProjection(46);
    private final GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
    private final StudyRegion fullRegion = new StudyRegion(
            "Kaziranga–Karbi Anglong, Assam",
            25.75, 93.10, 26.65, 93.75,
            new GeoPoint(26.58970, 93.40035),
            new GeoPoint(25.84573, 93.43781)
    );

    @Test
    void validateIndependentWildlifeRasterSamplingElevatedViaductSkewAndCorridorBlocking() {
        WildlifeRasterProvider wildlifeRaster = new WildlifeRasterProvider(
                "data/processed/wildlife/assam_wildlife_esz_corridors_utm46.tif"
        );
        WildlifeCorridorVectorIndex wildlifeIndex = new WildlifeCorridorVectorIndex(
                projection,
                "data/processed/wildlife/assam_wildlife_corridors.geojson"
        );
        WildlifeRoutingProperties props = new WildlifeRoutingProperties();
        WildlifeCorridorCostLayer wildlifeLayer = new WildlifeCorridorCostLayer(
                wildlifeRaster, wildlifeIndex, projection, props
        );

        System.out.println("=== 1. WILDLIFE & ESZ RASTER + ELEVATED ANIMAL PASSAGE VALIDATION ===");
        assertEquals(9, wildlifeIndex.allCorridors().size());

        // Independent 30 m GeoTIFF point sampling across all wildlife & ESZ conservation classes
        assertEquals(WildlifeZoneClass.CRITICAL_MIGRATION_CORRIDOR_CORE,
                wildlifeRaster.sampleClass(26.5580, 93.4560)); // WLC-HALDIBARI Elephant/Rhino Corridor
        assertEquals(WildlifeZoneClass.CRITICAL_MIGRATION_CORRIDOR_CORE,
                wildlifeRaster.sampleClass(26.5350, 93.5350)); // WLC-PANBARI Kaziranga-Karbi Corridor
        assertEquals(WildlifeZoneClass.CRITICAL_MIGRATION_CORRIDOR_CORE,
                wildlifeRaster.sampleClass(26.4265, 93.5750)); // WLC-KALAPAHAR-DOIGRUNG Corridor
        assertEquals(WildlifeZoneClass.REGIONAL_FOREST_STEPPING_CORRIDOR,
                wildlifeRaster.sampleClass(26.2900, 93.5050)); // WLC-KALIANI-NAMBOR Stepping Corridor
        assertEquals(WildlifeZoneClass.REGIONAL_FOREST_STEPPING_CORRIDOR,
                wildlifeRaster.sampleClass(26.0380, 93.4450)); // WLC-LONGNIT-MARAT-LONGRI Corridor
        assertEquals(WildlifeZoneClass.ESZ_INNER_BUFFER_ZONE,
                wildlifeRaster.sampleClass(26.6120, 93.5070)); // 738 m from Kaziranga NP boundary (<1 km ESZ)
        assertEquals(WildlifeZoneClass.ESZ_OUTER_TRANSITION_ZONE,
                wildlifeRaster.sampleClass(fullRegion.origin().lat(), fullRegion.origin().lon())); // Kohora (1.29 km from PA)
        assertEquals(WildlifeZoneClass.NONE,
                wildlifeRaster.sampleClass(fullRegion.destination().lat(), fullRegion.destination().lon())); // Diphu (3.55 km from PA)
        assertEquals(WildlifeZoneClass.PROTECTED_SANCTUARY_HABITAT,
                wildlifeRaster.sampleClass(26.6150, 93.3600)); // Kaziranga NP core habitat

        // Verify blockCriticalCorridors toggle (soft penalty with mandatory viaduct by default vs hard block)
        GeoPoint haldibariPt = new GeoPoint(26.5580, 93.4560);
        CostAssessment softCorridor = wildlifeLayer.assess(haldibariPt);
        assertFalse(softCorridor.blocked());
        assertEquals(2.90, softCorridor.penaltyMultiplier(), 1e-6);

        WildlifeRoutingProperties strictWildlifeProps = new WildlifeRoutingProperties();
        strictWildlifeProps.setBlockCriticalCorridors(true);
        WildlifeCorridorCostLayer strictWildlifeLayer = new WildlifeCorridorCostLayer(
                wildlifeRaster, wildlifeIndex, projection, strictWildlifeProps
        );
        assertTrue(strictWildlifeLayer.assess(haldibariPt).blocked(),
                "Critical elephant/rhino corridors must be hard-blocked when blockCriticalCorridors=true");

        // Verify 90-degree perpendicular elevated wildlife viaduct vs <20-degree acute skew crossing
        // across east-west WLC-KALAPAHAR-DOIGRUNG (design width 500 m, min viaduct span 350 m)
        GeoPoint northPerp = new GeoPoint(26.4300, 93.5750);
        GeoPoint southPerp = new GeoPoint(26.4230, 93.5750);
        var perpCrossings = wildlifeIndex.findStepCrossings(northPerp, southPerp);
        assertEquals(1, perpCrossings.size());
        assertEquals(WildlifeCorridorTier.CRITICAL_ELEPHANT_RHINO_CORRIDOR, perpCrossings.get(0).corridorTier());
        assertTrue(perpCrossings.get(0).crossingAngleDegrees() > 85.0,
                "North-south highway across east-west Kalapahar-Doigrung corridor should be near-perpendicular (>85 deg)");

        GeoPoint acuteA = new GeoPoint(26.4280, 93.5650);
        GeoPoint acuteB = new GeoPoint(26.4250, 93.5850);
        var acuteCrossings = wildlifeIndex.findStepCrossings(acuteA, acuteB);
        assertEquals(1, acuteCrossings.size());
        assertTrue(acuteCrossings.get(0).crossingAngleDegrees() < 20.0,
                "Shallow diagonal crossing of Kalapahar-Doigrung corridor should have acute angle < 20 deg");

        double perpSpan = perpCrossings.get(0).recommendedPassageSpanMeters();
        double acuteSpan = acuteCrossings.get(0).recommendedPassageSpanMeters();
        double perpStepKm = projection.distanceMeters(northPerp, southPerp) / 1000.0;
        double acuteStepKm = projection.distanceMeters(acuteA, acuteB) / 1000.0;
        double perpViaductCostKmEq = wildlifeLayer.assessEdge(northPerp, southPerp).penaltyMultiplier() * perpStepKm;
        double acuteViaductCostKmEq = wildlifeLayer.assessEdge(acuteA, acuteB).penaltyMultiplier() * acuteStepKm;

        System.out.printf("Kalapahar-Doigrung 90-deg Viaduct : angle=%.1f deg | span=%.1f m | cost=%.2f km-eq%n",
                perpCrossings.get(0).crossingAngleDegrees(), perpSpan, perpViaductCostKmEq);
        System.out.printf("Kalapahar-Doigrung Acute Viaduct  : angle=%.1f deg | span=%.1f m | cost=%.2f km-eq%n",
                acuteCrossings.get(0).crossingAngleDegrees(), acuteSpan, acuteViaductCostKmEq);

        assertTrue(acuteSpan > perpSpan * 2.0,
                "Acute skew crossing must require a >2.0x longer elevated wildlife viaduct span");
        assertTrue(acuteViaductCostKmEq > perpViaductCostKmEq * 2.5,
                "Acute skew crossing must incur >2.5x higher barrier/viaduct cost km-eq");

        // Verify longitudinal parallel corridor fragmentation penalty along migration funnel without crossing
        GeoPoint parallelA = new GeoPoint(26.4275, 93.5680);
        GeoPoint parallelB = new GeoPoint(26.4278, 93.5740);
        assertTrue(wildlifeIndex.findStepCrossings(parallelA, parallelB).isEmpty());
        CostAssessment parallelEdge = wildlifeLayer.assessEdge(parallelA, parallelB);
        assertTrue(parallelEdge.penaltyMultiplier() > 1.5,
                "Running longitudinally parallel inside a critical wildlife corridor funnel must incur fragmentation penalty");
    }

    @Test
    void validateIsolatedWildlifeRoutingAndFullComposite7Vs8LayerAcrossModes() throws Exception {
        WildlifeRasterProvider wildlifeRaster = new WildlifeRasterProvider(
                "data/processed/wildlife/assam_wildlife_esz_corridors_utm46.tif"
        );
        WildlifeCorridorVectorIndex wildlifeIndex = new WildlifeCorridorVectorIndex(
                projection,
                "data/processed/wildlife/assam_wildlife_corridors.geojson"
        );
        WildlifeRoutingProperties wildlifeProps = new WildlifeRoutingProperties();
        WildlifeCorridorCostLayer wildlifeLayer = new WildlifeCorridorCostLayer(
                wildlifeRaster, wildlifeIndex, projection, wildlifeProps
        );

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

        // Load real cached LULC, Flood, Slope, Protected Area, and Settlement layers
        ObjectMapper mapper = new ObjectMapper();
        BhuvanCatalog catalog = new BhuvanCatalog();
        BhuvanFeatureInfoClient dummyLulcClient = new BhuvanFeatureInfoClient(null, null);
        BhuvanLulcSampler sampler = new BhuvanLulcSampler(
                catalog, dummyLulcClient, 0.01, 0.004, 1, "data/cache/bhuvan-lulc-samples.json", mapper
        );
        Method loadLulc = BhuvanLulcSampler.class.getDeclaredMethod("loadPersistentCache");
        loadLulc.setAccessible(true);
        loadLulc.invoke(sampler);
        LulcProvider lulcProvider = new BhuvanLulcProvider(sampler);

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
                null, "data/cache/bhuvan-flood-wms", 512, 1000, 28, 1, mapper
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
                waterRaster, riverIndex, waterProps,
                wildlifeRaster, wildlifeIndex, wildlifeProps
        );

        StudyRegion corridor = corridorWindow(fullRegion, fullRegion.origin(), fullRegion.destination(), 0.15);

        // === 2. ISOLATED WILDLIFE & ESZ LAYER ROUTING (DISTANCE_BASELINE vs WILDLIFE_ONLY) ===
        System.out.println("\n=== 2. ISOLATED WILDLIFE ROUTING (DISTANCE_BASELINE vs WILDLIFE_ONLY) ===");
        List<GeoPoint> distBaseline = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new DistanceOnlyCostSurface(), 250.0, corridor
        );
        List<GeoPoint> wildlifeOnly = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(wildlifeLayer)), 250.0, corridor
        );

        Map<String, Object> distDiag = metricsService.summarize(distBaseline);
        Map<String, Object> wildOnlyDiag = metricsService.summarize(wildlifeOnly);
        @SuppressWarnings("unchecked")
        double distWildlifePen = ((Map<String, Double>) distDiag.get("approximatePenaltyContributionKm"))
                .get("WILDLIFE_CORRIDORS");
        @SuppressWarnings("unchecked")
        double isolatedWildlifePen = ((Map<String, Double>) wildOnlyDiag.get("approximatePenaltyContributionKm"))
                .get("WILDLIFE_CORRIDORS");

        System.out.println("DISTANCE_BASELINE Wildlife: len=" + distDiag.get("routeLengthKm")
                + " km | pen=" + distWildlifePen + " km-eq | passages=" + distDiag.get("wildlifePassageCrossingSummary")
                + " | exposure=" + distDiag.get("wildlifeZoneExposureKm"));
        System.out.println("WILDLIFE_ONLY     Wildlife: len=" + wildOnlyDiag.get("routeLengthKm")
                + " km | pen=" + isolatedWildlifePen + " km-eq | passages=" + wildOnlyDiag.get("wildlifePassageCrossingSummary")
                + " | exposure=" + wildOnlyDiag.get("wildlifeZoneExposureKm"));

        assertTrue(isolatedWildlifePen < distWildlifePen,
                "Isolated wildlife-aware routing must reduce total wildlife corridor fragmentation & ESZ penalty");

        // === 3. FULL COMPOSITE 7-LAYER (BEFORE) vs 8-LAYER (AFTER: EXPRESSWAY & NATIONAL_HIGHWAY) ===
        System.out.println("\n=== 3. COMPOSITE 7-LAYER (BEFORE) vs 8-LAYER (AFTER) ===");
        HubEvaluationService hubService = new HubEvaluationService(transportIndex, projection, transportProps);
        HighwayNetworkPlannerService plannerService = new HighwayNetworkPlannerService(
                transportIndex, transportRaster, slopeProvider, paProvider,
                router, projection, transportProps,
                waterRaster, riverIndex, waterProps,
                wildlifeRaster, wildlifeIndex, wildlifeProps
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

            // 7-Layer (Before Wildlife Corridors & ESZ)
            List<GeoPoint> route7 = router.route(
                    fullRegion, fullRegion.origin(), fullRegion.destination(),
                    new CompositeCostSurface(List.of(
                            lulcLayer, floodLayer, slopeLayer, paLayer,
                            settlementLayer, modeTransportLayer, waterLayer)),
                    250.0, corridor
            );
            Map<String, Object> diag7 = metricsService.summarize(route7);

            // 8-Layer (After Wildlife Corridors & ESZ + Elevated Animal Passages)
            List<CostLayer> spurAll8TerrainSocialHydroWildlifeLayers = List.of(
                    lulcLayer, floodLayer, slopeLayer, paLayer,
                    settlementLayer, waterLayer, wildlifeLayer
            );
            List<GeoPoint> route8 = router.route(
                    fullRegion, fullRegion.origin(), fullRegion.destination(),
                    new CompositeCostSurface(List.of(
                            lulcLayer, floodLayer, slopeLayer, paLayer,
                            settlementLayer, modeTransportLayer, waterLayer, wildlifeLayer)),
                    250.0, corridor
            );
            Map<String, Object> diag8 = metricsService.summarize(route8);
            var plan8 = plannerService.planNetwork(
                    fullRegion, route8, hubEval, spurAll8TerrainSocialHydroWildlifeLayers
            );

            @SuppressWarnings("unchecked")
            double pen7 = ((Map<String, Double>) diag7.get("approximatePenaltyContributionKm"))
                    .get("WILDLIFE_CORRIDORS");
            @SuppressWarnings("unchecked")
            double pen8 = ((Map<String, Double>) diag8.get("approximatePenaltyContributionKm"))
                    .get("WILDLIFE_CORRIDORS");

            System.out.println("\n--- MODE: " + mode.name() + " ---");
            System.out.println("7-LAYER (BEFORE) : len=" + diag7.get("routeLengthKm")
                    + " km | wildPen=" + pen7 + " km-eq | passages=" + diag7.get("wildlifePassageCrossingSummary")
                    + " | wildExposure=" + diag7.get("wildlifeZoneExposureKm")
                    + " | hc=" + diag7.get("hardConstraintValidation")
                    + " | allPenalties=" + diag7.get("approximatePenaltyContributionKm"));
            System.out.println("8-LAYER (AFTER)  : len=" + diag8.get("routeLengthKm")
                    + " km | wildPen=" + pen8 + " km-eq | passages=" + diag8.get("wildlifePassageCrossingSummary")
                    + " | wildExposure=" + diag8.get("wildlifeZoneExposureKm")
                    + " | hc=" + diag8.get("hardConstraintValidation")
                    + " | allPenalties=" + diag8.get("approximatePenaltyContributionKm"));
            System.out.println("8-LAYER PASSAGES : " + plan8.wildlifePassages().stream()
                    .map(p -> p.corridorName() + " (" + p.structureType() + ", span=" + p.recommendedClearSpanMeters()
                            + "m, h=" + p.minimumClearHeightMeters() + "m, angle=" + p.crossingAngleDegrees()
                            + "deg @ ch=" + p.chainageKm() + "km)")
                    .toList());
            System.out.println("8-LAYER SPURS    : " + plan8.feederSpurs().stream()
                    .map(s -> s.hub().name() + ":" + s.spurLengthKm() + "km->" + s.hub().tieInRoadRef()).toList()
                    + " | V/C=" + plan8.projectedVcRatio() + " (" + plan8.levelOfService() + ")");

            assertTrue(pen8 <= pen7,
                    "8-Layer composite route in " + mode + " mode must reduce or match 7-Layer wildlife corridor & ESZ penalty");

            @SuppressWarnings("unchecked")
            Map<String, Object> hc7 = (Map<String, Object>) diag7.get("hardConstraintValidation");
            @SuppressWarnings("unchecked")
            Map<String, Object> hc8 = (Map<String, Object>) diag8.get("hardConstraintValidation");
            assertTrue(
                    ((Number) hc8.get("protectedAreaViolationSamples")).intValue()
                            <= ((Number) hc7.get("protectedAreaViolationSamples")).intValue(),
                    "8-Layer mainline must not increase sub-grid Protected Area boundary samples vs 7-Layer");
            assertEquals(0, hc8.get("protectedAreaNoDataSamples"));
            assertEquals(0, hc8.get("slopeNoDataViolationSamples"));

            // Every 250 m routing vertex on the 8-Layer mainline must strictly satisfy PA==0 and Slope<=30 deg
            for (GeoPoint v : route8) {
                Double pa = paProvider.sampleOrNull(v.lat(), v.lon());
                Double slp = slopeProvider.sampleOrNull(v.lat(), v.lon());
                assertTrue(pa != null && Math.round(pa) == 0L,
                        "Mainline vertex must not enter Protected Area: " + v);
                assertTrue(slp != null && slp <= 30.0,
                        "Mainline vertex must satisfy Slope <= 30 deg: " + v + " (slope=" + slp + ")");
            }

            // Ensure no highway interchange is placed inside an ESZ inner buffer, wildlife corridor, or water channel/wetland
            for (var ic : plan8.interchanges()) {
                WildlifeZoneClass icWild = wildlifeRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(icWild.isInterchangeExcludedZone(),
                        "Interchange " + ic.interchangeId() + " must never sit inside ESZ inner buffer or wildlife corridor: " + icWild);

                WaterBodyClass icWater = waterRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(
                        icWater == WaterBodyClass.MAJOR_PERENNIAL_RIVER
                                || icWater == WaterBodyClass.MEDIUM_PERENNIAL_RIVER
                                || icWater == WaterBodyClass.MINOR_PERENNIAL_STREAM
                                || icWater == WaterBodyClass.INLAND_WETLAND_WATERBODY,
                        "Interchange " + ic.interchangeId() + " must never sit inside a river channel or wetland");
            }

            // Ensure all dedicated A* spurs pass vertex hard constraints & Protected Area checks and connect outside URBAN_CORE
            for (int i = 0; i < plan8.feederSpurs().size(); i++) {
                var ic = plan8.interchanges().get(i);
                var spur = plan8.feederSpurs().get(i);
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
                        "8-Layer dedicated spur for " + spur.hub().name() + " must have 0 Protected Area violations");
                for (GeoPoint sv : spur.path()) {
                    Double pa = paProvider.sampleOrNull(sv.lat(), sv.lon());
                    Double slp = slopeProvider.sampleOrNull(sv.lat(), sv.lon());
                    assertTrue(pa != null && Math.round(pa) == 0L);
                    assertTrue(slp != null && slp <= 30.0);
                }
            }

            // Verify Multi-Feature GeoJSON output includes WILDLIFE_PASSAGE features matching planned passages
            List<Map<String, Object>> geoJsonFeatures =
                    plannerService.buildGeoJsonFeatures(route8, Map.of("profile", "COMPOSITE_8_LAYER"), plan8);
            long wildlifePassageFeatures = geoJsonFeatures.stream()
                    .filter(f -> "WILDLIFE_PASSAGE".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                    .count();
            assertEquals(plan8.wildlifePassages().size(), wildlifePassageFeatures,
                    "GeoJSON FeatureCollection must emit a WILDLIFE_PASSAGE Point feature for every planned wildlife crossing");
        }
    }

    private static StudyRegion corridorWindow(StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg);
        return new StudyRegion(full.name() + " / wildlife validation window", minLat, minLon, maxLat, maxLon, a, b);
    }
}
