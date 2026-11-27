package in.marg.eia;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Generates a warm, zero-jargon natural-language public summary card for a planned highway route,
 * emphasizing environmental impact first and then all other crucial facts citizens need to know.
 *
 * <p>Uses the shared {@link EnvironmentalLlmClient} (configured via {@code marg.eia.llm.*}) when
 * enabled and configured, and falls back gracefully to a rich natural-language narrative builder
 * if the LLM API is disabled, unconfigured, or temporarily unavailable.
 */
@Service
public class PublicRouteSummaryService {

    private static final Logger log = LoggerFactory.getLogger(PublicRouteSummaryService.class);

    private static final String SYSTEM_PROMPT = """
            You are writing a clear, honest, easy-to-read public information brief for ordinary citizens
            who have NO background in highway engineering, geography, or law.

            Strict rules:
            1. Write ONLY in warm, natural, everyday English prose — like a trusted public guide or newspaper explainer.
            2. NEVER use technical jargon, acronyms, or codes. Strictly forbid words/codes like:
               RoW, 2D RoW, LULC, DEM, V/C, LOS, PCU, HVI, ROB, EHV, ESZ, IRC, RFCTLARR, WPA, NBWL, SC-NBWL,
               FCA, MoEFCC, WII, GSI, PGCIL, CEA, Pareto, chainage, skew span, subgrade, scarp, riparian,
               raster, vector, GIS, buffer, deviation %, greenfield, mainline, feeder spur, arterial, tie-in,
               mitigation, km-eq, CRS, UTM, EPSG, Tier 1, Tier 2, A*.
               Instead use everyday words: "land needed for the road", "forest area", "wildlife sanctuary",
               "river bridge", "railway bridge", "road flyover", "connecting town road", "traffic comfort",
               "hill steepness", "rainwater drainage tunnel", "homes affected", "people living nearby".
            3. Emphasize ENVIRONMENTAL IMPACT FIRST and most thoroughly: forests and trees, national parks
               and wildlife sanctuaries, wildlife crossing bridges, rivers, wetlands, flood safety, steep hills,
               and landslide safety.
            4. Cover ALL other crucial public facts: how long the road is and why it curves around obstacles,
               how much total land is needed, whether any homes or families need relocation and how nearby
               residents are protected, planned bridges/flyovers, which nearby towns get connected, expected
               traffic comfort, and whether any special government wildlife or forest permissions are required.
            5. Use ONLY the exact facts and numbers supplied in the input JSON. Never invent numbers, towns,
               rivers, or claims. If a number is zero, state clearly and reassuringly that none are affected.
            6. Return ONLY a valid JSON object with exactly these five string keys (no bullet lists, no markdown):
               {
                 "headline": "One clear, natural sentence (14-24 words) capturing the route and its biggest environmental story.",
                 "environmentalImpact": "3-4 smooth natural-language sentences focusing deeply on forests, national parks, wildlife crossings, rivers, wetlands, flood safety, and hills.",
                 "communityAndLand": "2-3 smooth natural-language sentences explaining total land needed, farmland vs open land, whether any homes or families are affected, and nearby residents.",
                 "travelAndConnectivity": "2-3 smooth natural-language sentences explaining road length, towns connected, river/rail/road bridges planned, and how smooth traffic will feel.",
                 "keyTakeaway": "1-2 honest, plain-English sentences summarizing the biggest strength and the most important environmental or social point citizens should keep in mind."
               }
            """;

    private final EnvironmentalLlmClient llmClient;
    private final EnvironmentalLlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    @Autowired
    public PublicRouteSummaryService(
            EnvironmentalLlmClient llmClient,
            EnvironmentalLlmProperties llmProperties,
            ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> summarize(Map<String, Object> routeFacts) {
        Map<String, Object> safeFacts = routeFacts != null ? routeFacts : Map.of();

        if (llmClient != null && llmClient.isConfigured()) {
            try {
                String factsJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(safeFacts);
                String userPrompt = "Write the plain-English public summary card for this planned road based strictly on these facts:\n\n"
                        + factsJson;

                String rawResponse = llmClient.completeJson("public-route-summary", SYSTEM_PROMPT, userPrompt);
                Map<String, Object> parsed = parseLlmSummary(rawResponse);
                if (parsed != null) {
                    parsed.put("source", "AI_NATURAL_LANGUAGE");
                    parsed.put("model", llmProperties != null ? llmProperties.model() : "configured-llm");
                    return parsed;
                }
                log.warn("Public route summary LLM response was missing required fields; using local plain-language builder.");
            } catch (Exception ex) {
                log.warn("Public route summary LLM call failed ({}); falling back to local plain-language builder.",
                        ex.getMessage());
            }
        }

        Map<String, Object> fallback = buildNaturalLanguageFallback(safeFacts);
        fallback.put("source", "LOCAL_NATURAL_LANGUAGE");
        fallback.put("model", "built-in-plain-language");
        return fallback;
    }

    private Map<String, Object> parseLlmSummary(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return null;
        }
        String cleaned = stripCodeFences(rawResponse.trim());
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            String headline = text(root, "headline");
            String env = text(root, "environmentalImpact");
            String community = text(root, "communityAndLand");
            String travel = text(root, "travelAndConnectivity");
            String takeaway = text(root, "keyTakeaway");

            if (headline.isBlank() || env.isBlank() || community.isBlank() || travel.isBlank() || takeaway.isBlank()) {
                return null;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("headline", headline);
            result.put("environmentalImpact", env);
            result.put("communityAndLand", community);
            result.put("travelAndConnectivity", travel);
            result.put("keyTakeaway", takeaway);
            return result;
        } catch (Exception ex) {
            log.warn("Could not parse public summary JSON from LLM: {}", ex.getMessage());
            return null;
        }
    }

    private static String stripCodeFences(String text) {
        String s = text;
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.trim();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "";
        }
        return node.get(field).asString("").trim();
    }

    /**
     * Deterministic, warm, 100% natural-language fallback when the LLM API key is not configured or offline.
     */
    Map<String, Object> buildNaturalLanguageFallback(Map<String, Object> facts) {
        String roadType = friendlyRoadType(str(facts.get("facilityMode")));
        double routeKm = num(facts.get("routeLengthKm"));
        double baselineKm = num(facts.get("baselineLengthKm"));
        double extraKm = Math.max(0.0, routeKm - baselineKm);
        double forestHa = num(facts.get("forestHectares"));
        double totalLandHa = num(facts.get("totalLandHectares"));
        double roadWidthM = num(facts.get("roadWidthMeters"));
        if (roadWidthM <= 0) {
            roadWidthM = "expressway".equals(roadType) ? 60.0 : 45.0;
        }
        boolean sanctuaryCrossed = bool(facts.get("sanctuaryIntersection"));
        double sanctuaryKm = num(facts.get("sanctuaryLengthKm"));
        List<String> sanctuaryNames = strList(facts.get("sanctuaryNames"));
        double wetlandHa = num(facts.get("wetlandHectares"));
        int riverBridges = intNum(facts.get("riverBridges"));
        double riverSpanM = num(facts.get("riverBridgeSpanMeters"));
        int wildlifeBridges = intNum(facts.get("wildlifeBridges"));
        double wildlifeSpanM = num(facts.get("wildlifeBridgeSpanMeters"));
        int railBridges = intNum(facts.get("railBridges"));
        int roadFlyovers = intNum(facts.get("roadFlyovers"));
        int culverts = intNum(facts.get("drainageCulverts"));
        int buildings = intNum(facts.get("buildingsAffected"));
        int families = intNum(facts.get("familiesRelocated"));
        int peopleRelocated = intNum(facts.get("peopleRelocated"));
        int people250 = intNum(facts.get("peopleWithin250m"));
        List<String> connectedTowns = strList(facts.get("connectedTowns"));
        double linkRoadKm = num(facts.get("connectingRoadLengthKm"));
        String trafficFlow = str(facts.get("trafficFlowLabel"));
        if (trafficFlow.isBlank()) {
            trafficFlow = "smooth and comfortable";
        }
        String regionName = str(facts.get("corridorName"));

        // 1. Headline (environment-first)
        String headline;
        if (sanctuaryCrossed) {
            headline = String.format(Locale.ROOT,
                    "A %.0f-kilometer %s that passes near protected wildlife habitat and will need special national wildlife approval before construction.",
                    routeKm, roadType);
        } else if (forestHa <= 0.5) {
            headline = String.format(Locale.ROOT,
                    "A %.0f-kilometer %s designed to completely avoid national parks, wildlife sanctuaries, and forest land.",
                    routeKm, roadType);
        } else {
            headline = String.format(Locale.ROOT,
                    "A %.0f-kilometer %s that stays completely outside national parks and wildlife sanctuaries while passing through about %.0f hectares of tree cover.",
                    routeKm, roadType, forestHa);
        }

        // 2. Environmental Impact (primary focus)
        StringBuilder env = new StringBuilder();
        if (sanctuaryCrossed) {
            String parkText = sanctuaryNames.isEmpty() ? "a protected wildlife sanctuary" : String.join(" and ", sanctuaryNames);
            env.append(String.format(Locale.ROOT,
                    "The most important environmental point is that roughly %.1f kilometers of this route crosses %s, so strict permission from India's national wildlife authorities and forest officials would be mandatory before any work begins. ",
                    sanctuaryKm, parkText));
        } else {
            env.append("Protecting nature was a top priority when shaping this route: it stays completely outside every notified national park and wildlife sanctuary in the area. ");
        }

        if (forestHa > 0.5) {
            env.append(String.format(Locale.ROOT,
                    "About %.1f hectares of forested land fall within the road's path, which means official forest permission and large-scale tree replanting would be needed to offset the tree loss. ",
                    forestHa));
        } else {
            env.append("The path avoids forest cover almost entirely, keeping local trees and woodland intact. ");
        }

        if (wildlifeBridges > 0) {
            env.append(String.format(Locale.ROOT,
                    "To let animals move safely across their natural habitats without crossing traffic, the plan includes %d elevated wildlife %s stretching about %.0f meters in total. ",
                    wildlifeBridges, wildlifeBridges == 1 ? "bridge" : "bridges", wildlifeSpanM));
        }

        if (wetlandHa > 0.1) {
            env.append(String.format(Locale.ROOT,
                    "Roughly %.1f hectares of wetland area lie near the route and will require special drainage protection, ",
                    wetlandHa));
        } else {
            env.append("Natural wetlands are kept untouched, ");
        }

        if (culverts > 0) {
            env.append(String.format(Locale.ROOT,
                    "and %d rainwater drainage tunnels are planned underneath the road so monsoon water and seasonal streams can flow freely without causing local flooding.",
                    culverts));
        } else {
            env.append("and the path favors higher, drier ground to reduce monsoon flooding and avoid steep, landslide-prone slopes.");
        }

        // 3. Community & Land
        StringBuilder comm = new StringBuilder();
        if (totalLandHa > 0) {
            comm.append(String.format(Locale.ROOT,
                    "Building this %.0f-meter-wide road and its side connections will require roughly %.0f hectares of land in total, favoring open countryside and curving around busy town centers wherever possible. ",
                    roadWidthM, totalLandHa));
        } else {
            comm.append(String.format(Locale.ROOT,
                    "The planned %.0f-meter-wide road curves around busy town centers to keep disruption to local neighborhoods as low as possible. ",
                    roadWidthM));
        }

        if (buildings == 0 && peopleRelocated == 0) {
            comm.append("No homes, schools, hospitals, or places of worship fall directly in the road's path, meaning zero families would need to relocate. ");
        } else {
            comm.append(String.format(Locale.ROOT,
                    "An estimated %d %s fall inside the road's width, affecting roughly %d %s (about %d people) who would be entitled to fair compensation and full relocation support under national law, while hospitals, schools, and places of worship remain strictly protected. ",
                    buildings, buildings == 1 ? "building" : "buildings",
                    Math.max(1, families), families == 1 ? "family" : "families",
                    peopleRelocated));
        }

        if (people250 > 0) {
            comm.append(String.format(Locale.ROOT,
                    "About %,d residents live within 250 meters of the corridor, so noise barriers and safe pedestrian crossings are important along populated stretches.",
                    people250));
        }

        // 4. Travel, Bridges & Nearby Towns
        StringBuilder travel = new StringBuilder();
        if (extraKm >= 1.0 && baselineKm > 0) {
            travel.append(String.format(Locale.ROOT,
                    "In total, the %s stretches %.1f kilometers — about %.1f kilometers longer than a straight line because it deliberately bends around forests, steep hills, flood zones, and villages. ",
                    roadType, routeKm, extraKm));
        } else {
            travel.append(String.format(Locale.ROOT,
                    "The planned %s covers %.1f kilometers along a smooth, gentle path suitable for everyday travel and goods movement. ",
                    roadType, routeKm));
        }

        List<String> structurePhrases = new ArrayList<>();
        if (riverBridges > 0) {
            structurePhrases.add(riverBridges + " river " + (riverBridges == 1 ? "bridge" : "bridges")
                    + (riverSpanM > 0 ? String.format(Locale.ROOT, " (%.0f meters combined)", riverSpanM) : ""));
        }
        if (railBridges > 0) {
            structurePhrases.add(railBridges + " railway " + (railBridges == 1 ? "overbridge" : "overbridges"));
        }
        if (roadFlyovers > 0) {
            structurePhrases.add(roadFlyovers + " road " + (roadFlyovers == 1 ? "flyover" : "flyovers"));
        }
        if (!structurePhrases.isEmpty()) {
            travel.append("To keep journeys safe and non-stop, the plan includes ")
                    .append(joinNatural(structurePhrases))
                    .append(". ");
        }

        if (!connectedTowns.isEmpty()) {
            travel.append("Nearby ")
                    .append(connectedTowns.size() == 1 ? "town " : "towns ")
                    .append(joinNatural(connectedTowns))
                    .append(linkRoadKm > 0.1
                            ? String.format(Locale.ROOT, " will connect via %.1f kilometers of dedicated link roads, ", linkRoadKm)
                            : " will connect via dedicated link roads, ")
                    .append("and rush-hour traffic is expected to remain ")
                    .append(trafficFlow.toLowerCase(Locale.ROOT))
                    .append(".");
        } else {
            travel.append("Rush-hour traffic along the highway is expected to remain ")
                    .append(trafficFlow.toLowerCase(Locale.ROOT))
                    .append(".");
        }

        // 5. Key Takeaway
        String takeaway;
        if (sanctuaryCrossed) {
            takeaway = "Before this route could ever move forward, independent field surveys and formal permission from national wildlife authorities would be mandatory — citizens may also want to compare the 'Nature First' option below to see if a safer bypass exists.";
        } else if (forestHa > 50.0) {
            takeaway = String.format(Locale.ROOT,
                    "This route successfully protects national parks and keeps disruption to homes low, but because it still affects %.0f hectares of forested land, strong tree-replanting commitments and careful on-ground environmental review are essential.",
                    forestHa);
        } else if (buildings > 0) {
            takeaway = String.format(Locale.ROOT,
                    "Overall, this route achieves strong protection for wildlife parks and natural waterways while offering %s travel; local discussions should focus on fair support for the %d affected %s.",
                    trafficFlow.toLowerCase(Locale.ROOT), buildings, buildings == 1 ? "building" : "buildings");
        } else {
            String regionBit = regionName.isBlank() ? "" : " for " + regionName;
            takeaway = String.format(Locale.ROOT,
                    "Overall, this route%s strikes a thoughtful balance — staying clear of protected parks and local homes while keeping travel smooth, flood-safe, and well-connected for nearby communities.",
                    regionBit);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("headline", headline.trim());
        result.put("environmentalImpact", env.toString().trim());
        result.put("communityAndLand", comm.toString().trim());
        result.put("travelAndConnectivity", travel.toString().trim());
        result.put("keyTakeaway", takeaway.trim());
        return result;
    }

    private static String friendlyRoadType(String mode) {
        if (mode == null) return "highway";
        String m = mode.toUpperCase(Locale.ROOT);
        if (m.contains("EXPRESSWAY")) return "expressway";
        if (m.contains("STATE")) return "state highway";
        return "national highway";
    }

    private static String joinNatural(List<String> items) {
        if (items.isEmpty()) return "";
        if (items.size() == 1) return items.getFirst();
        if (items.size() == 2) return items.get(0) + " and " + items.get(1);
        return String.join(", ", items.subList(0, items.size() - 1)) + ", and " + items.getLast();
    }

    private static String str(Object obj) {
        return obj == null ? "" : String.valueOf(obj).trim();
    }

    private static double num(Object obj) {
        if (obj instanceof Number n) return n.doubleValue();
        if (obj instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private static int intNum(Object obj) {
        return (int) Math.round(num(obj));
    }

    private static boolean bool(Object obj) {
        if (obj instanceof Boolean b) return b;
        return obj != null && Boolean.parseBoolean(String.valueOf(obj));
    }

    private static List<String> strList(Object obj) {
        if (!(obj instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String s = str(item);
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }
}
