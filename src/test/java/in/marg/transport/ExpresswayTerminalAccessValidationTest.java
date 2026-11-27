package in.marg.transport;

import in.marg.api.CompositeRoutingController;
import in.marg.model.GeoPoint;
import in.marg.region.CorridorIngestionService;
import in.marg.region.PanIndiaDynamicCostLayer;
import in.marg.region.RegionalCorridorRegistry;
import in.marg.spatial.Wgs84UtmProjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates IRC:SP:99-2013 (Expressway) and IRC:SP:84-2019 (National Highway) terminal
 * access rules:
 * <ul>
 *   <li>Expressways strictly reject LOCAL_ROAD tie-ins and prioritize NH/SH corridors.</li>
 *   <li>National Highways prefer NH &gt; SH &gt; MDR while allowing penalized LOCAL_ROAD fallback.</li>
 *   <li>Expressways snap outward from city urban cores to greenfield periphery tie-ins.</li>
 *   <li>Expressways emit TERMINAL_FEEDER_SPUR + TRUMPET_TERMINAL_INTERCHANGE + Barrier Toll Plaza
 *       metadata and features with 0 local street violations.</li>
 * </ul>
 */
class ExpresswayTerminalAccessValidationTest {

    @Test
    void terminalAccessPolicyEnforcesIrcRoadHierarchy() {
        // EXPRESSWAY: strictly forbids LOCAL_ROAD and RAILWAY
        assertFalse(TerminalAccessPolicy.isCategoryAllowedForTieIn(TransportCategory.LOCAL_ROAD, FacilityMode.EXPRESSWAY));
        assertFalse(TerminalAccessPolicy.isCategoryAllowedForTieIn(TransportCategory.RAILWAY, FacilityMode.EXPRESSWAY));
        assertTrue(TerminalAccessPolicy.isCategoryAllowedForTieIn(TransportCategory.NATIONAL_HIGHWAY, FacilityMode.EXPRESSWAY));
        assertTrue(TerminalAccessPolicy.isCategoryAllowedForTieIn(TransportCategory.STATE_HIGHWAY, FacilityMode.EXPRESSWAY));
        assertTrue(Double.isInfinite(
                TerminalAccessPolicy.weightedTieInDistanceMeters(10.0, TransportCategory.LOCAL_ROAD, FacilityMode.EXPRESSWAY)));

        // NATIONAL_HIGHWAY: prefers NH/SH/MDR over closer LOCAL_ROAD, while allowing LOCAL_ROAD fallback
        assertTrue(TerminalAccessPolicy.isCategoryAllowedForTieIn(TransportCategory.LOCAL_ROAD, FacilityMode.NATIONAL_HIGHWAY));
        double nhScore1200m = TerminalAccessPolicy.weightedTieInDistanceMeters(
                1200.0, TransportCategory.NATIONAL_HIGHWAY, FacilityMode.NATIONAL_HIGHWAY);
        double shScore1500m = TerminalAccessPolicy.weightedTieInDistanceMeters(
                1500.0, TransportCategory.STATE_HIGHWAY, FacilityMode.NATIONAL_HIGHWAY);
        double localScore50m = TerminalAccessPolicy.weightedTieInDistanceMeters(
                50.0, TransportCategory.LOCAL_ROAD, FacilityMode.NATIONAL_HIGHWAY);
        double nhScore5000m = TerminalAccessPolicy.weightedTieInDistanceMeters(
                5000.0, TransportCategory.NATIONAL_HIGHWAY, FacilityMode.NATIONAL_HIGHWAY);

        assertTrue(nhScore1200m < localScore50m, "NH at 1.2 km should beat LOCAL_ROAD at 50 m");
        assertTrue(shScore1500m < localScore50m, "SH at 1.5 km should beat LOCAL_ROAD at 50 m");
        assertTrue(localScore50m < nhScore5000m, "LOCAL_ROAD at 50 m should remain usable fallback if nearest NH is 5 km away");
    }

    @Test
    void vectorIndexNeverTiesExpresswayToLocalStreet() {
        TransportNetworkVectorIndex index = new TransportNetworkVectorIndex(
                new Wgs84UtmProjection(44),
                "data/processed/regions/gangetic_plains/gangetic_plains_transport_network.geojson",
                "data/processed/regions/gangetic_plains/gangetic_plains_regional_hubs.geojson"
        );

        // Pick a real LOCAL_ROAD vertex from Gangetic Plains dataset
        GeoPoint localStreetPoint = index.allCorridors().stream()
                .filter(c -> c.category() == TransportCategory.LOCAL_ROAD && !c.points().isEmpty())
                .map(c -> c.points().get(0))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected LOCAL_ROAD corridors in gangetic_plains dataset"));

        TransportNetworkVectorIndex.NearestRoadTieIn expTieIn =
                index.findNearestRoadTieIn(localStreetPoint, pt -> true, FacilityMode.EXPRESSWAY);
        assertNotNull(expTieIn, "Expressway tie-in must still resolve to an arterial highway");
        assertNotEquals(TransportCategory.LOCAL_ROAD, expTieIn.category(),
                "IRC:SP:99 Cl. 2.12 violation: Expressway must NEVER tie into a LOCAL_ROAD");
        assertTrue(
                expTieIn.category() == TransportCategory.NATIONAL_HIGHWAY
                        || expTieIn.category() == TransportCategory.STATE_HIGHWAY,
                "Expressway tie-in should select NH or SH, got: " + expTieIn.category());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tier2ExpresswayVsNationalHighwayTerminalConnections() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        // 1. EXPRESSWAY in Gangetic Plains: zero local street violations, Trumpet Interchanges + Feeder Spurs
        Map<String, Object> expRoute = controller.route(
                26.925, 81.185, 26.785, 82.140,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                "gangetic_plains", null, null
        );
        Map<?, ?> expMeta = (Map<?, ?>) expRoute.get("metadata");
        Map<?, ?> expTerm = (Map<?, ?>) expMeta.get("terminalRoadConnections");
        assertNotNull(expTerm, "terminalRoadConnections must be present");
        assertEquals(0, ((Number) expTerm.get("localStreetViolationsCount")).intValue(),
                "Expressway must enforce 0 local street violations");
        assertEquals(Boolean.TRUE, expTerm.get("expresswayComplianceVerified"));
        assertEquals(2, ((Number) expTerm.get("terminalInterchangesCount")).intValue());

        Map<?, ?> startConn = (Map<?, ?>) expTerm.get("startRoadConnection");
        Map<?, ?> goalConn = (Map<?, ?>) expTerm.get("goalRoadConnection");
        assertNotNull(startConn);
        assertNotNull(goalConn);
        assertNotEquals("LOCAL_ROAD", startConn.get("category"));
        assertNotEquals("LOCAL_ROAD", goalConn.get("category"));
        assertEquals(TerminalAccessPolicy.CONNECTOR_TYPE_EXPRESSWAY_SPUR, startConn.get("connectorDesignation"));
        assertEquals(TerminalAccessPolicy.JUNCTION_TYPE_EXPRESSWAY_TRUMPET, startConn.get("terminalJunctionType"));
        assertEquals(Boolean.TRUE, startConn.get("requiresTollBarrierPlaza"));
        assertEquals(Boolean.FALSE, startConn.get("localStreetTieInAllowed"));

        List<Map<String, Object>> expFeatures = (List<Map<String, Object>>) expRoute.get("features");
        long terminalTrumpets = expFeatures.stream()
                .map(f -> (Map<?, ?>) f.get("properties"))
                .filter(p -> "INTERCHANGE".equals(p.get("featureRole"))
                        && TerminalAccessPolicy.JUNCTION_TYPE_EXPRESSWAY_TRUMPET.equals(p.get("interchangeType")))
                .count();
        assertEquals(2, terminalTrumpets, "Expressway must emit 2 TRUMPET_TERMINAL_INTERCHANGE features");

        // 2. NATIONAL_HIGHWAY in Gangetic Plains: standard at-grade access connector metadata
        Map<String, Object> nhRoute = controller.route(
                26.960, 81.320, 26.740, 81.980,
                250.0, 0.15, 2000, null,
                "NATIONAL_HIGHWAY", null, null, null,
                false, 0,
                "gangetic_plains", null, null
        );
        Map<?, ?> nhMeta = (Map<?, ?>) nhRoute.get("metadata");
        Map<?, ?> nhTerm = (Map<?, ?>) nhMeta.get("terminalRoadConnections");
        assertNotNull(nhTerm);
        assertEquals(0, ((Number) nhTerm.get("terminalInterchangesCount")).intValue());
        Map<?, ?> nhStartConn = (Map<?, ?>) nhTerm.get("startRoadConnection");
        assertNotNull(nhStartConn);
        assertEquals(TerminalAccessPolicy.CONNECTOR_TYPE_HIGHWAY_ACCESS, nhStartConn.get("connectorDesignation"));
        assertEquals(TerminalAccessPolicy.JUNCTION_TYPE_HIGHWAY_AT_GRADE, nhStartConn.get("terminalJunctionType"));
        assertEquals(Boolean.FALSE, nhStartConn.get("requiresTollBarrierPlaza"));
        assertEquals(Boolean.TRUE, nhStartConn.get("localStreetTieInAllowed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tier1PanIndiaExpresswaySnapsOutsideCityCore() {
        RegionalCorridorRegistry registry = new RegionalCorridorRegistry();
        CorridorIngestionService ingestionService = new CorridorIngestionService(registry);
        CompositeRoutingController controller = new CompositeRoutingController(registry, ingestionService);

        // Start dead center inside Lucknow urban core (URB-LKO: 26.8467, 80.9462)
        assertNotNull(PanIndiaDynamicCostLayer.findContainingUrbanCore(new GeoPoint(26.8467, 80.9462)),
                "Sanity check: Lucknow center must be recognized as an urban core");

        Map<String, Object> panExpRoute = controller.route(
                26.8467, 80.9462, 26.4499, 80.3319,
                250.0, 0.15, 2000, null,
                "EXPRESSWAY", null, null, null,
                false, 0,
                null, "TIER_1_DYNAMIC_PAN_INDIA", null
        );
        Map<?, ?> meta = (Map<?, ?>) panExpRoute.get("metadata");
        Map<?, ?> endRes = (Map<?, ?>) meta.get("endpointResolution");
        assertNotNull(endRes);
        assertTrue(Boolean.TRUE.equals(endRes.get("startSnapped")),
                "Expressway starting inside Lucknow core must snap outward to greenfield periphery");
        assertTrue(Boolean.TRUE.equals(endRes.get("goalSnapped")),
                "Expressway ending inside Kanpur core must snap outward to greenfield periphery");
        assertTrue(String.valueOf(endRes.get("startSnapReason")).contains("URBAN_CORE"),
                "Start snap reason should identify urban core exclusion, was: " + endRes.get("startSnapReason"));
        assertTrue(String.valueOf(endRes.get("goalSnapReason")).contains("URBAN_CORE"),
                "Goal snap reason should identify urban core exclusion, was: " + endRes.get("goalSnapReason"));

        Map<?, ?> termConn = (Map<?, ?>) meta.get("terminalRoadConnections");
        assertNotNull(termConn);
        assertEquals(0, ((Number) termConn.get("localStreetViolationsCount")).intValue());
        assertEquals(2, ((Number) termConn.get("terminalInterchangesCount")).intValue());
    }
}
