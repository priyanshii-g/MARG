package in.marg.routing;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeOffEvaluationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void testDirectExpressShortestPathRouteImprovements() {
        Map<String, Object> netSummary = Map.of(
                "totalWaterCrossingsCount", 2,
                "riverBridgesCount", 1,
                "totalEstimatedRiverBridgeSpanMeters", 80.0,
                "estimatedDirectlyDisplacedPopulation", 15,
                "buildingsInDirectRowCount", 4,
                "plannedInterchangesCount", 2
        );

        Map<String, Object> profile = TradeOffEvaluationService.evaluateTradeOffs(
                Map.of("lulcKmBreakdown", Map.of("FOREST", 1.8)),
                netSummary,
                60.0, // baseline
                63.5, // route length (tight tracking)
                5.8,  // deviation %
                AlignmentPreference.SHORTEST_PATH
        );

        assertNotNull(profile);
        Map<String, Object> impr = (Map<String, Object>) profile.get("routeImprovements");
        assertNotNull(impr, "Route improvements object must be populated");
        assertEquals("SHORTEST_PATH", impr.get("archetype"));
        assertNotNull(impr.get("primaryGain"));
        assertNotNull(impr.get("engineeringAdvantage"));
        assertNotNull(impr.get("tradeOffAccepted"));

        String headline = (String) impr.get("headline");
        assertTrue(headline.contains("Direct Express"), "Headline must reference Direct Express");
        assertTrue(headline.contains("IRC:SP:30") || headline.contains("operating costs"), "Must quantify IRC:SP:30 VOC savings");

        List<Map<String, Object>> benefits = (List<Map<String, Object>>) impr.get("quantifiedBenefits");
        assertFalse(benefits.isEmpty(), "Must provide quantified benefit cards");
        assertTrue(benefits.stream().anyMatch(b -> "TRAVEL_TIME".equals(b.get("key"))));
        assertTrue(benefits.stream().anyMatch(b -> "FLEET_VOC".equals(b.get("key"))));

        double voc = ((Number) impr.get("vocSavingsYearlyLakh")).doubleValue();
        assertTrue(voc >= 0.0, "VOC savings must be calculated");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testEcoShieldRouteImprovements() {
        Map<String, Object> netSummary = Map.of(
                "totalWaterCrossingsCount", 4,
                "riverBridgesCount", 2,
                "totalEstimatedRiverBridgeSpanMeters", 150.0,
                "wildlifePassagesCount", 3,
                "wetlandAreaInDirectRowHectares", 0.0,
                "estimatedDirectlyDisplacedPopulation", 5
        );

        Map<String, Object> profile = TradeOffEvaluationService.evaluateTradeOffs(
                Map.of("lulcKmBreakdown", Map.of("FOREST", 0.2)),
                netSummary,
                55.0,
                68.0,
                23.6,
                AlignmentPreference.ECO_SHIELD
        );

        Map<String, Object> impr = (Map<String, Object>) profile.get("routeImprovements");
        assertNotNull(impr);
        assertEquals("ECO_SHIELD", impr.get("archetype"));

        String headline = (String) impr.get("headline");
        assertTrue(headline.contains("Eco-Shield"), "Headline must reference Eco-Shield");
        assertTrue(headline.contains("forest diversion"), "Must reference forest diversion");

        List<Map<String, Object>> benefits = (List<Map<String, Object>>) impr.get("quantifiedBenefits");
        assertTrue(benefits.stream().anyMatch(b -> "FOREST_SPARED".equals(b.get("key"))));
        assertTrue(benefits.stream().anyMatch(b -> "RIPARIAN_BUFFER".equals(b.get("key"))));
        assertTrue(benefits.stream().anyMatch(b -> "WETLAND_PROTECTION".equals(b.get("key"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCommunitySafeguardRouteImprovements() {
        Map<String, Object> netSummary = Map.of(
                "estimatedDirectlyDisplacedPopulation", 0,
                "buildingsInDirectRowCount", 0,
                "settlementsIntersectingDirectRowCount", 0
        );

        Map<String, Object> profile = TradeOffEvaluationService.evaluateTradeOffs(
                Map.of(),
                netSummary,
                50.0,
                58.0,
                16.0,
                AlignmentPreference.COMMUNITY_SAFEGUARD
        );

        Map<String, Object> impr = (Map<String, Object>) profile.get("routeImprovements");
        assertNotNull(impr);
        assertEquals("COMMUNITY_SAFEGUARD", impr.get("archetype"));

        String headline = (String) impr.get("headline");
        assertTrue(headline.contains("Community Safeguard"), "Headline must reference Community Safeguard");
        assertTrue(headline.contains("demolitions") || headline.contains("RFCTLARR"), "Must reference demolition or RFCTLARR protection");

        List<Map<String, Object>> benefits = (List<Map<String, Object>>) impr.get("quantifiedBenefits");
        assertTrue(benefits.stream().anyMatch(b -> "HOMES_SPARED".equals(b.get("key"))));
        assertTrue(benefits.stream().anyMatch(b -> "DISPLACEMENT_SPARED".equals(b.get("key"))));
        assertTrue(benefits.stream().anyMatch(b -> "LAND_POSSESSION".equals(b.get("key"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testBalancedRouteImprovements() {
        Map<String, Object> netSummary = Map.of(
                "dedicatedFeederSpursCount", 2,
                "totalDedicatedFeederSpurLengthKm", 12.4,
                "totalCatchmentPopulationServed", 320000,
                "estimatedDirectlyDisplacedPopulation", 12,
                "buildingsInDirectRowCount", 3
        );

        Map<String, Object> profile = TradeOffEvaluationService.evaluateTradeOffs(
                Map.of("lulcKmBreakdown", Map.of("FOREST", 3.2)),
                netSummary,
                65.0,
                72.5,
                11.5,
                AlignmentPreference.BALANCED
        );

        Map<String, Object> impr = (Map<String, Object>) profile.get("routeImprovements");
        assertNotNull(impr);
        assertEquals("BALANCED", impr.get("archetype"));

        String headline = (String) impr.get("headline");
        assertTrue(headline.contains("Balanced Multi-Objective"), "Headline must reference Balanced Multi-Objective");
        assertTrue(headline.contains("Pareto-equilibrium"), "Must explain Pareto-equilibrium");

        List<Map<String, Object>> benefits = (List<Map<String, Object>>) impr.get("quantifiedBenefits");
        assertTrue(benefits.stream().anyMatch(b -> "PARETO_INDEX".equals(b.get("key"))));
        assertTrue(benefits.stream().anyMatch(b -> "REGIONAL_CONNECTIVITY".equals(b.get("key"))));
    }
}
