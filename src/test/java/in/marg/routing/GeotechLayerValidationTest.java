package in.marg.routing;

import in.marg.bhuvan.BhuvanCatalog;
import in.marg.bhuvan.BhuvanFeatureInfoClient;
import in.marg.bhuvan.BhuvanLulcSampler;
import in.marg.config.FloodRoutingProperties;
import in.marg.config.GeotechRoutingProperties;
import in.marg.config.LulcRoutingProperties;
import in.marg.config.SettlementRoutingProperties;
import in.marg.config.SlopeRoutingProperties;
import in.marg.config.TransportRoutingProperties;
import in.marg.config.WaterBodyRoutingProperties;
import in.marg.config.WildlifeRoutingProperties;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.floodraster.BhuvanFloodRasterCache;
import in.marg.geotech.GeotechHazardClass;
import in.marg.geotech.GeotechHazardTier;
import in.marg.geotech.GeotechLineamentVectorIndex;
import in.marg.geotech.GeotechRasterProvider;
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

class GeotechLayerValidationTest {

    private final CoordinateProjection projection = new Wgs84UtmProjection(46);
    private final GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
    private final StudyRegion fullRegion = new StudyRegion(
            "Kaziranga–Karbi Anglong, Assam",
            25.75, 93.10, 26.65, 93.75,
            new GeoPoint(26.58970, 93.40035),
            new GeoPoint(25.84573, 93.43781)
    );

    @Test
    void validateIndependentGeotechRasterSamplingFaultScarpSkewAndActiveLandslideBlocking() {
        GeotechRasterProvider geotechRaster = new GeotechRasterProvider(
                "data/processed/geotech/assam_geotech_landslide_utm46.tif"
        );
        GeotechLineamentVectorIndex geotechIndex = new GeotechLineamentVectorIndex(
                projection,
                "data/processed/geotech/assam_fault_landslide_zones.geojson"
        );
        GeotechRoutingProperties props = new GeotechRoutingProperties();
        GeotechLandslideCostLayer geotechLayer = new GeotechLandslideCostLayer(
                geotechRaster, geotechIndex, projection, props
        );

        System.out.println("=== 1. GEOTECH & LANDSLIDE RASTER + FAULT SCARP SKEW VALIDATION ===");
        assertEquals(9, geotechIndex.allLineaments().size());

        // Independent 30 m GeoTIFF point sampling across all 7 soil-bearing & landslide hazard classes (0..6)
        assertEquals(GeotechHazardClass.COMPETENT_BEDROCK_STABLE_SUBGRADE,
                geotechRaster.sampleClass(25.84573, 93.43781)); // Diphu Competent Gneiss Plateau
        assertEquals(GeotechHazardClass.WEATHERED_RESIDUAL_MODERATE_SOIL,
                geotechRaster.sampleClass(26.31000, 93.43000)); // Karbi Weathered Residual Slope
        assertEquals(GeotechHazardClass.SOFT_ALLUVIAL_COMPRESSIBLE_CLAY,
                geotechRaster.sampleClass(26.58970, 93.40035)); // Kohora Quaternary Alluvial Plain
        assertEquals(GeotechHazardClass.LIQUEFIABLE_ORGANIC_MARSH_SUBGRADE,
                geotechRaster.sampleClass(26.57400, 93.41000)); // Kaziranga-Diphlu Liquefiable Silt Paleotrough
        assertEquals(GeotechHazardClass.MODERATE_COLLUVIAL_LANDSLIDE_SUSCEPTIBILITY,
                geotechRaster.sampleClass(26.15200, 93.49500)); // Santipur Dissected Colluvial Slope
        assertEquals(GeotechHazardClass.HIGH_LANDSLIDE_SUSCEPTIBILITY_SCARP,
                geotechRaster.sampleClass(26.22000, 93.41000)); // Chokihola High Landslide Scarp
        assertEquals(GeotechHazardClass.ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE,
                geotechRaster.sampleClass(26.55000, 93.33000)); // Northern Karbi Boundary Fault Active Core

        // Verify blockActiveLandslideZones toggle (soft penalty by default vs hard constraint when enabled)
        GeoPoint activeFaultSlidePt = new GeoPoint(26.55000, 93.33000);
        CostAssessment softSlide = geotechLayer.assess(activeFaultSlidePt);
        assertFalse(softSlide.blocked());
        assertEquals(4.20, softSlide.penaltyMultiplier(), 1e-6);

        GeotechRoutingProperties strictGeotechProps = new GeotechRoutingProperties();
        strictGeotechProps.setBlockActiveLandslideZones(true);
        GeotechLandslideCostLayer strictGeotechLayer = new GeotechLandslideCostLayer(
                geotechRaster, geotechIndex, projection, strictGeotechProps
        );
        assertTrue(strictGeotechLayer.assess(activeFaultSlidePt).blocked(),
                "Active tectonic landslide shear cores must be hard-blocked when blockActiveLandslideZones=true");

        // Verify 90-degree perpendicular traverse vs <20-degree acute side-hill cut across Kaliani Shear Zone
        GeoPoint northPerp = new GeoPoint(26.3950, 93.4250);
        GeoPoint southPerp = new GeoPoint(26.3880, 93.4250);
        var perpCrossings = geotechIndex.findStepCrossings(northPerp, southPerp);
        assertEquals(1, perpCrossings.size());
        assertEquals(GeotechHazardTier.ACTIVE_FAULT_SHEAR_ZONE, perpCrossings.get(0).hazardTier());
        assertTrue(perpCrossings.get(0).crossingAngleDegrees() > 85.0,
                "North-south traverse across east-west Kaliani Shear Zone should be near-perpendicular (>85 deg)");

        GeoPoint acuteA = new GeoPoint(26.3930, 93.4150);
        GeoPoint acuteB = new GeoPoint(26.3900, 93.4350);
        var acuteCrossings = geotechIndex.findStepCrossings(acuteA, acuteB);
        assertEquals(1, acuteCrossings.size());
        assertTrue(acuteCrossings.get(0).crossingAngleDegrees() < 20.0,
                "Shallow side-hill cut across Kaliani Shear Zone should have acute angle < 20 deg");

        double perpStabLen = perpCrossings.get(0).recommendedStabilizationLengthMeters();
        double acuteStabLen = acuteCrossings.get(0).recommendedStabilizationLengthMeters();
        double perpStepKm = projection.distanceMeters(northPerp, southPerp) / 1000.0;
        double acuteStepKm = projection.distanceMeters(acuteA, acuteB) / 1000.0;
        double perpMitigationCostKmEq =
                geotechLayer.assessEdge(northPerp, southPerp).penaltyMultiplier() * perpStepKm;
        double acuteMitigationCostKmEq =
                geotechLayer.assessEdge(acuteA, acuteB).penaltyMultiplier() * acuteStepKm;

        System.out.printf("Kaliani Shear 90-deg Traverse : angle=%.1f deg | stabLen=%.1f m | cost=%.2f km-eq%n",
                perpCrossings.get(0).crossingAngleDegrees(), perpStabLen, perpMitigationCostKmEq);
        System.out.printf("Kaliani Shear Acute Cut       : angle=%.1f deg | stabLen=%.1f m | cost=%.2f km-eq%n",
                acuteCrossings.get(0).crossingAngleDegrees(), acuteStabLen, acuteMitigationCostKmEq);

        assertTrue(acuteStabLen > perpStabLen * 2.5,
                "Acute side-hill fault cut must require >2.5x longer stabilization/retaining structure");
        assertTrue(acuteMitigationCostKmEq > perpMitigationCostKmEq * 2.5,
                "Acute side-hill fault cut must incur >2.5x higher geotechnical mitigation cost km-eq");

        // Verify longitudinal side-hill toe-undercutting penalty parallel to Kaliani Shear Zone without crossing
        GeoPoint parallelA = new GeoPoint(26.3928, 93.4180);
        GeoPoint parallelB = new GeoPoint(26.3925, 93.4240);
        assertTrue(geotechIndex.findStepCrossings(parallelA, parallelB).isEmpty());
        CostAssessment parallelEdge = geotechLayer.assessEdge(parallelA, parallelB);
        assertTrue(parallelEdge.penaltyMultiplier() > 1.5,
                "Running longitudinally parallel along an active shear/landslide scarp must incur toe-undercutting penalty");
    }

    @Test
    void validateIsolatedGeotechRoutingAndFullComposite8Vs9LayerAcrossModes() throws Exception {
        GeotechRasterProvider geotechRaster = new GeotechRasterProvider(
                "data/processed/geotech/assam_geotech_landslide_utm46.tif"
        );
        GeotechLineamentVectorIndex geotechIndex = new GeotechLineamentVectorIndex(
                projection,
                "data/processed/geotech/assam_fault_landslide_zones.geojson"
        );
        GeotechRoutingProperties geotechProps = new GeotechRoutingProperties();
        GeotechLandslideCostLayer geotechLayer = new GeotechLandslideCostLayer(
                geotechRaster, geotechIndex, projection, geotechProps
        );

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
                wildlifeRaster, wildlifeIndex, wildlifeProps,
                geotechRaster, geotechIndex, geotechProps
        );

        StudyRegion corridor = corridorWindow(fullRegion, fullRegion.origin(), fullRegion.destination(), 0.15);

        // === 2. ISOLATED GEOTECH & LANDSLIDE ROUTING (DISTANCE_BASELINE vs GEOTECH_ONLY + STRICT BLOCKING) ===
        System.out.println("\n=== 2. ISOLATED GEOTECH ROUTING (DISTANCE_BASELINE vs GEOTECH_ONLY) ===");
        List<GeoPoint> distBaseline = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new DistanceOnlyCostSurface(), 250.0, corridor
        );
        List<GeoPoint> geotechOnly = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(geotechLayer)), 250.0, corridor
        );

        Map<String, Object> distDiag = metricsService.summarize(distBaseline);
        Map<String, Object> geoOnlyDiag = metricsService.summarize(geotechOnly);
        @SuppressWarnings("unchecked")
        double distGeoPen = ((Map<String, Double>) distDiag.get("approximatePenaltyContributionKm"))
                .get("GEOTECH_LANDSLIDE");
        @SuppressWarnings("unchecked")
        double isolatedGeoPen = ((Map<String, Double>) geoOnlyDiag.get("approximatePenaltyContributionKm"))
                .get("GEOTECH_LANDSLIDE");

        System.out.println("DISTANCE_BASELINE Geotech: len=" + distDiag.get("routeLengthKm")
                + " km | pen=" + distGeoPen + " km-eq | mitigations=" + distDiag.get("geotechMitigationCrossingSummary")
                + " | exposure=" + distDiag.get("geotechHazardExposureKm"));
        System.out.println("GEOTECH_ONLY      Geotech: len=" + geoOnlyDiag.get("routeLengthKm")
                + " km | pen=" + isolatedGeoPen + " km-eq | mitigations=" + geoOnlyDiag.get("geotechMitigationCrossingSummary")
                + " | exposure=" + geoOnlyDiag.get("geotechHazardExposureKm"));

        assertTrue(isolatedGeoPen < distGeoPen,
                "Isolated geotechnical-aware routing must reduce weak-soil, landslide & fault-skew penalty");

        // Verify strict blockActiveLandslideZones=true threads cleanly through low-slope geological saddles
        GeotechRoutingProperties strictProps = new GeotechRoutingProperties();
        strictProps.setBlockActiveLandslideZones(true);
        GeotechLandslideCostLayer strictLayer = new GeotechLandslideCostLayer(
                geotechRaster, geotechIndex, projection, strictProps
        );
        List<GeoPoint> strictSaddleRoute = router.route(
                fullRegion, fullRegion.origin(), fullRegion.destination(),
                new CompositeCostSurface(List.of(strictLayer)), 250.0, corridor
        );
        for (GeoPoint v : strictSaddleRoute) {
            assertFalse(
                    geotechRaster.sampleClass(v.lat(), v.lon())
                            == GeotechHazardClass.ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE,
                    "Strict active-landslide-blocked route must have 0 vertices on ACTIVE_TECTONIC_LANDSLIDE_SHEAR_CORE");
        }

        // === 3. FULL COMPOSITE 8-LAYER (BEFORE) vs 9-LAYER (AFTER: EXPRESSWAY & NATIONAL_HIGHWAY) ===
        System.out.println("\n=== 3. COMPOSITE 8-LAYER (BEFORE) vs 9-LAYER (AFTER) ===");
        HubEvaluationService hubService = new HubEvaluationService(transportIndex, projection, transportProps);
        HighwayNetworkPlannerService plannerService = new HighwayNetworkPlannerService(
                transportIndex, transportRaster, slopeProvider, paProvider,
                router, projection, transportProps,
                waterRaster, riverIndex, waterProps,
                wildlifeRaster, wildlifeIndex, wildlifeProps,
                geotechRaster, geotechIndex, geotechProps
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

            // 8-Layer (Before Geotechnical & Landslide Susceptibility)
            List<GeoPoint> route8 = router.route(
                    fullRegion, fullRegion.origin(), fullRegion.destination(),
                    new CompositeCostSurface(List.of(
                            lulcLayer, floodLayer, slopeLayer, paLayer,
                            settlementLayer, modeTransportLayer, waterLayer, wildlifeLayer)).withDenseColonyLastResortMode(),
                    250.0, corridor
            );
            Map<String, Object> diag8 = metricsService.summarize(route8);

            // 9-Layer (After Geotechnical, Soil Bearing Capacity & Landslide Susceptibility)
            List<CostLayer> spurAll9NonTransportLayers = List.of(
                    lulcLayer, floodLayer, slopeLayer, paLayer,
                    settlementLayer, waterLayer, wildlifeLayer, geotechLayer
            );
            List<GeoPoint> route9 = router.route(
                    fullRegion, fullRegion.origin(), fullRegion.destination(),
                    new CompositeCostSurface(List.of(
                            lulcLayer, floodLayer, slopeLayer, paLayer,
                            settlementLayer, modeTransportLayer, waterLayer, wildlifeLayer, geotechLayer)).withDenseColonyLastResortMode(),
                    250.0, corridor
            );
            Map<String, Object> diag9 = metricsService.summarize(route9);
            var plan9 = plannerService.planNetwork(
                    fullRegion, route9, hubEval, spurAll9NonTransportLayers
            );

            @SuppressWarnings("unchecked")
            double pen8 = ((Map<String, Double>) diag8.get("approximatePenaltyContributionKm"))
                    .get("GEOTECH_LANDSLIDE");
            @SuppressWarnings("unchecked")
            double pen9 = ((Map<String, Double>) diag9.get("approximatePenaltyContributionKm"))
                    .get("GEOTECH_LANDSLIDE");

            System.out.println("\n--- MODE: " + mode.name() + " ---");
            System.out.println("8-LAYER (BEFORE) : len=" + diag8.get("routeLengthKm")
                    + " km | geoPen=" + pen8 + " km-eq | mitigations=" + diag8.get("geotechMitigationCrossingSummary")
                    + " | geoExposure=" + diag8.get("geotechHazardExposureKm")
                    + " | hc=" + diag8.get("hardConstraintValidation")
                    + " | allPenalties=" + diag8.get("approximatePenaltyContributionKm"));
            System.out.println("9-LAYER (AFTER)  : len=" + diag9.get("routeLengthKm")
                    + " km | geoPen=" + pen9 + " km-eq | mitigations=" + diag9.get("geotechMitigationCrossingSummary")
                    + " | geoExposure=" + diag9.get("geotechHazardExposureKm")
                    + " | hc=" + diag9.get("hardConstraintValidation")
                    + " | allPenalties=" + diag9.get("approximatePenaltyContributionKm"));
            System.out.println("9-LAYER GEOTECH  : " + plan9.geotechMitigations().stream()
                    .map(m -> m.lineamentName() + " (" + m.structureType() + ", len="
                            + m.recommendedStabilizationLengthMeters() + "m, angle="
                            + m.crossingAngleDegrees() + "deg @ ch=" + m.chainageKm() + "km)")
                    .toList());
            System.out.println("9-LAYER SPURS    : " + plan9.feederSpurs().stream()
                    .map(s -> s.hub().name() + ":" + s.spurLengthKm() + "km->" + s.hub().tieInRoadRef()).toList()
                    + " | V/C=" + plan9.projectedVcRatio() + " (" + plan9.levelOfService() + ")");

            assertTrue(pen9 <= pen8,
                    "9-Layer composite route in " + mode + " mode must reduce or match 8-Layer geotechnical penalty");

            @SuppressWarnings("unchecked")
            Map<String, Object> hc9 = (Map<String, Object>) diag9.get("hardConstraintValidation");
            assertTrue(
                    ((Number) hc9.get("protectedAreaViolationSamples")).intValue() <= 1,
                    "9-Layer mainline sub-grid 125 m boundary diagnostic must not exceed the 1 corner pixel baseline");
            assertEquals(0, hc9.get("protectedAreaNoDataSamples"));
            assertEquals(0, hc9.get("slopeNoDataViolationSamples"));

            // Every 250 m routing vertex on the 9-Layer mainline must strictly satisfy PA==0 and Slope<=30 deg
            for (GeoPoint v : route9) {
                Double pa = paProvider.sampleOrNull(v.lat(), v.lon());
                Double slp = slopeProvider.sampleOrNull(v.lat(), v.lon());
                assertTrue(pa != null && Math.round(pa) == 0L,
                        "Mainline vertex must not enter Protected Area: " + v);
                assertTrue(slp != null && slp <= 30.0,
                        "Mainline vertex must satisfy Slope <= 30 deg: " + v + " (slope=" + slp + ")");
            }

            // Ensure no highway interchange sits on unsafe geotechnical subgrade, ESZ/wildlife exclusion zone, or river channel/wetland
            for (var ic : plan9.interchanges()) {
                GeotechHazardClass icGeo = geotechRaster.sampleClass(ic.point().lat(), ic.point().lon());
                assertFalse(icGeo.isInterchangeUnsafeSubgrade(),
                        "Interchange " + ic.interchangeId() + " must never sit on liquefiable marsh or active/high landslide scarp: " + icGeo);

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
            for (int i = 0; i < plan9.feederSpurs().size(); i++) {
                var ic = plan9.interchanges().get(i);
                var spur = plan9.feederSpurs().get(i);
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
                        "9-Layer dedicated spur for " + spur.hub().name() + " must have 0 Protected Area violations");
                for (GeoPoint sv : spur.path()) {
                    Double pa = paProvider.sampleOrNull(sv.lat(), sv.lon());
                    Double slp = slopeProvider.sampleOrNull(sv.lat(), sv.lon());
                    assertTrue(pa != null && Math.round(pa) == 0L);
                    assertTrue(slp != null && slp <= 30.0);
                }
            }

            // Verify Multi-Feature GeoJSON output includes GEOTECH_MITIGATION_ZONE features matching planned mitigations
            List<Map<String, Object>> geoJsonFeatures =
                    plannerService.buildGeoJsonFeatures(route9, Map.of("profile", "COMPOSITE_9_LAYER"), plan9);
            long geotechFeatures = geoJsonFeatures.stream()
                    .filter(f -> "GEOTECH_MITIGATION_ZONE".equals(((Map<?, ?>) f.get("properties")).get("featureRole")))
                    .count();
            assertEquals(plan9.geotechMitigations().size(), geotechFeatures,
                    "GeoJSON FeatureCollection must emit a GEOTECH_MITIGATION_ZONE Point feature for every planned geotechnical crossing");
        }
    }

    private static StudyRegion corridorWindow(StudyRegion full, GeoPoint a, GeoPoint b, double bufferDeg) {
        double minLat = Math.max(full.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg);
        double maxLat = Math.min(full.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg);
        double minLon = Math.max(full.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg);
        double maxLon = Math.min(full.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg);
        return new StudyRegion(full.name() + " / geotech validation window", minLat, minLon, maxLat, maxLon, a, b);
    }
}
