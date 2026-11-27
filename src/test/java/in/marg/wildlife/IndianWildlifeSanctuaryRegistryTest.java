package in.marg.wildlife;

import in.marg.model.GeoPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndianWildlifeSanctuaryRegistryTest {

    @Test
    void testSanctuaryCatalogLoaded() {
        IndianWildlifeSanctuaryRegistry registry = new IndianWildlifeSanctuaryRegistry();
        assertNotNull(registry.allSanctuaries());
        assertTrue(registry.allSanctuaries().size() >= 679, "Should load all 679+ authoritative protected areas across India (found: " + registry.allSanctuaries().size() + ")");
        assertTrue(registry.allNationalParks().size() >= 106, "Should include at least 106 National Parks (found: " + registry.allNationalParks().size() + ")");
        assertTrue(registry.allWildlifeSanctuaries().size() >= 560, "Should include at least 560 Wildlife Sanctuaries (found: " + registry.allWildlifeSanctuaries().size() + ")");

        // Verify key statutory national parks & sanctuaries across India
        assertTrue(registry.allSanctuaries().stream().anyMatch(s -> s.id().equals("PA-AS-KAZIRANGA")));
        assertTrue(registry.allSanctuaries().stream().anyMatch(s -> s.id().equals("PA-AS-EKARBI")));
        assertTrue(registry.allSanctuaries().stream().anyMatch(s -> s.id().equals("PA-AS-MARAT-LONGRI")));
        assertTrue(registry.allSanctuaries().stream().anyMatch(s -> s.id().equals("PA-UK-CORBETT")));
        assertTrue(registry.allSanctuaries().stream().anyMatch(s -> s.id().equals("PA-MH-SAHYADRI")));
        assertTrue(registry.allSanctuaries().stream().anyMatch(s -> s.id().equals("PA-GJ-GIR")));
        assertTrue(registry.allSanctuaries().stream().anyMatch(s -> s.id().equals("PA-KA-BANDIPUR")));
        assertTrue(registry.allSanctuaries().stream().anyMatch(s -> s.id().equals("PA-KL-PERIYAR")));
        assertTrue(registry.allSanctuaries().stream().anyMatch(s -> s.id().equals("PA-WB-SUNDARBANS")));

        // Test spatial index fast lookups across diverse geographies
        // Kaziranga (Assam)
        var hitKaziranga = registry.findIntersectingSanctuary(26.67, 93.36);
        assertNotNull(hitKaziranga);
        assertTrue(hitKaziranga.name().contains("Kaziranga"));

        // Jim Corbett (Uttarakhand)
        var hitCorbett = registry.findIntersectingSanctuary(29.53, 78.775);
        assertNotNull(hitCorbett);
        assertTrue(hitCorbett.name().contains("Corbett"));

        // Gir (Gujarat)
        var hitGir = registry.findIntersectingSanctuary(21.124, 70.824);
        assertNotNull(hitGir);
        assertTrue(hitGir.name().contains("Gir"));
    }

    @Test
    void testEvaluatePathClearanceIntersection() {
        IndianWildlifeSanctuaryRegistry registry = new IndianWildlifeSanctuaryRegistry();

        // Path passing directly through East Karbi Anglong WLS core (~25.90, 93.38)
        List<GeoPoint> penetratingPath = List.of(
                new GeoPoint(25.85, 93.38),
                new GeoPoint(25.90, 93.38),
                new GeoPoint(25.95, 93.38)
        );

        var report = registry.evaluatePathClearances(penetratingPath);
        assertTrue(report.hasSanctuaryIntersection(), "Must flag sanctuary intersection");
        assertEquals("CRITICAL_STATUTORY_ALERT", report.severityLevel());
        assertEquals("#dc2626", report.themeColor());
        assertTrue(report.totalSanctuaryLengthKm() > 0.0);
        assertTrue(report.directSanctuaryForestTakeHectares() > 0.0);
        assertTrue(report.traversedSanctuaryNames().contains("East Karbi Anglong Wildlife Sanctuary"));
        assertNotNull(report.mandatoryClearancesChecklist());
        assertEquals(6, report.mandatoryClearancesChecklist().size());
        assertTrue(report.mandatoryClearancesChecklist().get(0).contains("National Board for Wildlife (SC-NBWL)"));
        assertEquals("https://parivesh.nic.in/", report.pariveshPortalUrl());
    }

    @Test
    void testEvaluatePathClearanceCompliantBypass() {
        IndianWildlifeSanctuaryRegistry registry = new IndianWildlifeSanctuaryRegistry();

        // Path running safely along the eastern transport corridor (93.45 to 93.55) away from sanctuaries
        List<GeoPoint> compliantPath = List.of(
                new GeoPoint(26.50, 93.48),
                new GeoPoint(26.35, 93.50),
                new GeoPoint(26.15, 93.52)
        );

        var report = registry.evaluatePathClearances(compliantPath);
        assertFalse(report.hasSanctuaryIntersection(), "Compliant eastern corridor should have zero sanctuary intersection");
        assertEquals(0.0, report.totalSanctuaryLengthKm());
        assertEquals(0.0, report.directSanctuaryForestTakeHectares());
        assertTrue(report.traversedSanctuaryNames().isEmpty());
    }

    @Test
    void testAssamDefaultCorridorRoutingAvoidsSanctuaries() {
        in.marg.region.RegionalCorridorRegistry regionalRegistry = new in.marg.region.RegionalCorridorRegistry();
        // Test with maxDeviationPercent = 15.0 (frontend default) to ensure constrained Lagrangian A*
        // never falls back to an unconstrained straight line through North Karbi Anglong WLS.
        var response = regionalRegistry.route(
                "assam", null,
                26.58970, 93.40035,
                25.84573, 93.43781,
                250.0, 0.15, 2000, 15.0,
                "EXPRESSWAY", null, null, null
        );

        assertNotNull(response);
        var meta = (java.util.Map<?, ?>) response.get("metadata");
        assertNotNull(meta);
        var clearance = (java.util.Map<?, ?>) meta.get("statutoryClearanceReport");
        assertNotNull(clearance, "Statutory clearance report must be present in response metadata");

        boolean hasSanctuaryIntersection = Boolean.TRUE.equals(clearance.get("hasSanctuaryIntersection"));
        assertFalse(hasSanctuaryIntersection, "The generated Assam route (even with maxDeviationPercent=15) must strictly bypass North Karbi Anglong, East Karbi Anglong & Marat Longri sanctuary cores");
        assertEquals(0.0, ((Number) clearance.get("totalSanctuaryLengthKm")).doubleValue());
    }

    @Test
    void test10KmDefaultEszBufferAndMultiPolygonSegmentIntersection() {
        IndianWildlifeSanctuaryRegistry registry = new IndianWildlifeSanctuaryRegistry();
        assertEquals(10.0, IndianWildlifeSanctuaryRegistry.DEFAULT_ESZ_BUFFER_KM, 1e-6);

        var nkarbi = registry.allSanctuaries().stream()
                .filter(s -> s.id().equals("PA-AS-NKARBI"))
                .findFirst().orElseThrow();

        // Segment jumping completely across North Karbi Anglong WLS from north to south
        var segHit = registry.findIntersectingSanctuaryForSegment(26.55, 93.31, 26.38, 93.31);
        assertNotNull(segHit, "Segment crossing North Karbi Anglong WLS must be detected as a core intersection");
        assertEquals("PA-AS-NKARBI", segHit.id());

        // Point ~5 km outside North Karbi Anglong WLS core must be inside the 10 km default ESZ buffer
        double distKm = nkarbi.distanceToCoreKm(26.47, 93.43);
        assertTrue(distKm > 0.0 && distKm <= 10.0, "Point should be outside core but within 10 km ESZ (actual: " + distKm + " km)");
        assertTrue(nkarbi.inEszBuffer(26.47, 93.43), "Point within 10 km of North Karbi Anglong WLS core must be in ESZ buffer");
        assertTrue(registry.isInsideAnyEszBuffer(26.47, 93.43), "Registry must flag point within 10 km as inside ESZ buffer");

        // Verify MultiPolygon rings for Kaziranga NP and Nambor-Doigrung WLS
        var kaziranga = registry.allSanctuaries().stream()
                .filter(s -> s.id().equals("PA-AS-KAZIRANGA"))
                .findFirst().orElseThrow();
        assertTrue(kaziranga.effectiveBoundaryRings().size() >= 3, "Kaziranga NP must preserve all 3 MultiPolygon outer rings from OSM");

        var nambor = registry.allSanctuaries().stream()
                .filter(s -> s.id().equals("PA-AS-NAMBOR-GARAMPANI"))
                .findFirst().orElseThrow();
        assertTrue(nambor.effectiveBoundaryRings().size() >= 3, "Nambor-Doigrung WLS must preserve all 3 MultiPolygon outer rings from OSM");
    }

    @Test
    void testCadastralMultiPolygonPrecisionAndRayCasting() {
        IndianWildlifeSanctuaryRegistry registry = new IndianWildlifeSanctuaryRegistry();

        // 1. Verify that all 690 protected areas have non-empty cadastral MultiPolygon boundaries
        for (var s : registry.allSanctuaries()) {
            assertNotNull(s.boundaryPolygon(), "boundaryPolygon must not be null for " + s.id());
            assertTrue(s.boundaryPolygon().size() >= 3, "boundaryPolygon must have at least 3 vertices for " + s.id());
            assertNotNull(s.eszPolygon(), "eszPolygon must not be null for " + s.id());
            assertTrue(s.eszPolygon().size() >= 3, "eszPolygon must have at least 3 vertices for " + s.id());
        }

        // 2. Test East Karbi Anglong & North Karbi Anglong cadastral boundary precision:
        var ekarbi = registry.allSanctuaries().stream()
                .filter(s -> s.id().equals("PA-AS-EKARBI"))
                .findFirst().orElseThrow();

        // Exact surveyed coordinate inside the core
        assertTrue(ekarbi.containsPoint(25.90, 93.38), "Centroid must be inside sanctuary");

        // A coordinate at the far edge outside the polygon must be FALSE
        assertFalse(ekarbi.containsPoint(25.80, 93.30), "Point outside polygon must return false (no false positive)");

        // 3. Test distance to core calculation:
        double distOutside = ekarbi.distanceToCoreKm(25.90, 93.55);
        assertTrue(distOutside > 0.0, "Distance to core outside sanctuary must be > 0 km");
        assertEquals(0.0, ekarbi.distanceToCoreKm(25.90, 93.38), 1e-3, "Distance to core inside sanctuary must be 0");

        var nkarbi = registry.allSanctuaries().stream()
                .filter(s -> s.id().equals("PA-AS-NKARBI"))
                .findFirst().orElseThrow();
        assertTrue(nkarbi.containsPoint(26.45, 93.30), "Point must be inside North Karbi Anglong WLS");
    }

    @Test
    void test20mToleranceCatalogFileSize() {
        java.nio.file.Path p = java.nio.file.Path.of("data/processed/wildlife/india_protected_areas.json");
        assertTrue(java.nio.file.Files.exists(p), "20m tolerance catalog must exist on disk");
        long sizeBytes = p.toFile().length();
        double sizeMb = sizeBytes / (1024.0 * 1024.0);
        // User target: ~12-15 MB
        assertTrue(sizeMb >= 10.0 && sizeMb <= 16.0, "Catalog size should be ~12-15 MB (actual: " + sizeMb + " MB)");
    }

    @Test
    void testExtremePrecisionOverlayRegistrationAndPrioritization() {
        IndianWildlifeSanctuaryRegistry registry = new IndianWildlifeSanctuaryRegistry();
        registry.clearPrecisionOverlay();

        // Create an extreme-precision polygon for a test sanctuary
        String testId = "TEST-PREC-01";
        List<double[]> bPoly = List.of(
                new double[]{25.0, 85.0},
                new double[]{25.0, 85.1},
                new double[]{25.1, 85.1},
                new double[]{25.1, 85.0},
                new double[]{25.0, 85.0}
        );
        List<double[]> eszPoly = List.of(
                new double[]{24.95, 84.95},
                new double[]{24.95, 85.15},
                new double[]{25.15, 85.15},
                new double[]{25.15, 84.95},
                new double[]{24.95, 84.95}
        );

        IndianWildlifeSanctuaryRegistry.StatutorySanctuary precSanctuary = new IndianWildlifeSanctuaryRegistry.StatutorySanctuary(
                testId, "Extreme Precision Test Sanctuary", "WILDLIFE_SANCTUARY", "Bihar", "Patna",
                "Wildlife (Protection) Act, 1972", 25.05, 85.05, 0.05, 0.10,
                24.95, 25.15, 84.95, 85.15, 120.0, "Gangetic Dolphin",
                bPoly, eszPoly
        );

        registry.registerPrecisionOverlay(List.of(precSanctuary));

        // Coordinate inside the precision polygon
        var match = registry.matchPoint(25.05, 85.05);
        assertNotNull(match, "Point inside precision overlay must match");
        assertTrue(match.isCoreIntersection());
        assertEquals("TEST-PREC-01", match.sanctuary().id());

        // Coordinate in ESZ
        var matchEsz = registry.matchPoint(24.98, 84.98);
        assertNotNull(matchEsz);
        assertTrue(matchEsz.isEszBufferIntersection());

        // Coordinate outside
        var matchOut = registry.matchPoint(26.0, 86.0);
        // Either null or matching non-test sanctuary
        if (matchOut != null) {
            assertFalse(matchOut.sanctuary().id().equals("TEST-PREC-01"));
        }

        registry.clearPrecisionOverlay();
    }

    @Test
    void testExtremePrecisionProtectedAreaService() {
        ExtremePrecisionProtectedAreaService service = new ExtremePrecisionProtectedAreaService();
        // In deep mode or test, querying an un-cached bbox when offline should cleanly return status with fallback
        var statusResult = service.fetchPrecisionSanctuariesWithStatus(26.0, 93.0, 26.5, 93.5);
        assertNotNull(statusResult);
        assertNotNull(statusResult.sanctuaries());
        assertNotNull(statusResult.precisionTier());
        assertNotNull(statusResult.notificationMessage());
        // In sandbox/offline, it falls back to 20m tolerance baseline
        assertTrue(statusResult.fallbackOccurred(), "When offline, fallbackOccurred must be true");
        assertEquals("OFFLINE_20M_FALLBACK", statusResult.precisionTier());
        assertTrue(statusResult.notificationMessage().contains("20-meter") || statusResult.notificationMessage().contains("falling back"));

        var result = service.getOrFetchPrecisionSanctuaries(26.0, 93.0, 26.5, 93.5);
        assertNotNull(result, "Service must return a non-null list (cached, fetched, or empty fallback)");
    }

    @Test
    void testGovDataAutoSyncService() {
        in.marg.sync.GovDataAutoSyncService syncService = new in.marg.sync.GovDataAutoSyncService();
        var summary = syncService.checkAndSyncAll(false);
        assertNotNull(summary);
        assertNotNull(summary.datasets());
        assertTrue(summary.datasets().containsKey("wildlife_protected_areas"));
        assertTrue(summary.datasets().containsKey("highways_network"));
        assertTrue(summary.datasets().containsKey("drainage_river_network"));
        assertTrue(summary.datasets().containsKey("settlements_urban_landuse"));

        var wildlifeInfo = summary.datasets().get("wildlife_protected_areas");
        assertEquals(690, wildlifeInfo.recordCount());
        assertTrue(wildlifeInfo.fileSizeBytes() > 10_000_000L, "Offline file size should be > 10 MB");
    }
}
