package in.marg.eia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class PublicRouteSummaryServiceTest {

    @Test
    void usesConfiguredLlmApiToReturnNaturalLanguagePublicSummary() {
        EnvironmentalLlmProperties props = new EnvironmentalLlmProperties(
                true, "https://llm.test/v1", "test-key", "gemini-2.5-flash", 30, "", 0);
        RestClient.Builder builder = RestClient.builder().baseUrl(props.baseUrl());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EnvironmentalLlmClient llmClient = new EnvironmentalLlmClient(props, builder.build());

        String llmJson = """
                {
                  "choices": [{
                    "message": {
                      "content": "{\\"headline\\":\\"A 110-kilometer expressway that stays completely outside Kaziranga National Park.\\",\\"environmentalImpact\\":\\"This route avoids all protected wildlife sanctuaries and includes elevated bridges so animals can cross safely.\\",\\"communityAndLand\\":\\"The road curves around local villages and keeps hospitals and schools completely untouched.\\",\\"travelAndConnectivity\\":\\"Nearby towns connect via short link roads, and rush-hour traffic is expected to stay smooth.\\",\\"keyTakeaway\\":\\"Strong wildlife park protection with transparent replanting commitments for forested hills.\\"}"
                    }
                  }]
                }
                """;

        server.expect(requestTo("https://llm.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(llmJson, MediaType.APPLICATION_JSON));

        PublicRouteSummaryService service = new PublicRouteSummaryService(llmClient, props, new ObjectMapper());
        Map<String, Object> result = service.summarize(Map.of(
                "corridorName", "Kaziranga–Karbi Anglong (Assam)",
                "facilityMode", "EXPRESSWAY",
                "routeLengthKm", 110.4,
                "forestHectares", 240.0,
                "sanctuaryIntersection", false
        ));

        assertEquals("AI_NATURAL_LANGUAGE", result.get("source"));
        assertEquals("gemini-2.5-flash", result.get("model"));
        assertTrue(String.valueOf(result.get("headline")).contains("110-kilometer"));
        assertFalse(String.valueOf(result.get("environmentalImpact")).isBlank());
        assertFalse(String.valueOf(result.get("communityAndLand")).isBlank());
        assertFalse(String.valueOf(result.get("travelAndConnectivity")).isBlank());
        assertFalse(String.valueOf(result.get("keyTakeaway")).isBlank());
        server.verify();
    }

    @Test
    void fallsBackToJargonFreeNaturalLanguageWhenLlmIsDisabled() {
        EnvironmentalLlmProperties props = new EnvironmentalLlmProperties(
                false, "https://generativelanguage.googleapis.com/v1beta", "", "gemini-2.5-flash", 30, "", 0);
        EnvironmentalLlmClient llmClient = new EnvironmentalLlmClient(props);
        PublicRouteSummaryService service = new PublicRouteSummaryService(llmClient, props, new ObjectMapper());

        Map<String, Object> result = service.summarize(Map.ofEntries(
                Map.entry("corridorName", "Pune–Satara (Western Ghats)"),
                Map.entry("facilityMode", "EXPRESSWAY"),
                Map.entry("routeLengthKm", 84.5),
                Map.entry("baselineLengthKm", 73.2),
                Map.entry("forestHectares", 18.4),
                Map.entry("totalLandHectares", 520.0),
                Map.entry("roadWidthMeters", 60),
                Map.entry("sanctuaryIntersection", false),
                Map.entry("wetlandHectares", 0.0),
                Map.entry("riverBridges", 3),
                Map.entry("riverBridgeSpanMeters", 240),
                Map.entry("wildlifeBridges", 2),
                Map.entry("wildlifeBridgeSpanMeters", 320),
                Map.entry("railBridges", 1),
                Map.entry("roadFlyovers", 4),
                Map.entry("drainageCulverts", 12),
                Map.entry("buildingsAffected", 0),
                Map.entry("familiesRelocated", 0),
                Map.entry("peopleRelocated", 0),
                Map.entry("peopleWithin250m", 420),
                Map.entry("connectedTowns", List.of("Wai", "Shirwal")),
                Map.entry("connectingRoadLengthKm", 9.6),
                Map.entry("trafficFlowLabel", "Smooth Flow")
        ));

        assertEquals("LOCAL_NATURAL_LANGUAGE", result.get("source"));
        String combined = String.join(" ",
                String.valueOf(result.get("headline")),
                String.valueOf(result.get("environmentalImpact")),
                String.valueOf(result.get("communityAndLand")),
                String.valueOf(result.get("travelAndConnectivity")),
                String.valueOf(result.get("keyTakeaway")));

        assertNotNull(combined);
        assertTrue(combined.contains("18.4 hectares of forested land"));
        assertTrue(combined.contains("Wai and Shirwal"));
        // Verify zero engineering acronyms or jargon leak into the citizen summary
        for (String forbidden : List.of("RoW", "LULC", "IRC", "RFCTLARR", "NBWL", "PCU", "V/C", "HVI", "ROB", "EHV", "DEM", "chainage")) {
            assertFalse(combined.contains(forbidden), "Citizen summary must not contain technical jargon: " + forbidden);
        }
    }
}
