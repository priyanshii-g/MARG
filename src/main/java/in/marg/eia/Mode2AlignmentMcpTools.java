package in.marg.eia;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/** MCP entry point for sending a documentary project's existing alignment to deterministic GIS screening. */
@Component
public class Mode2AlignmentMcpTools {

    private final Mode2AlignmentScreeningService screening;
    private final Mode2ProjectEvidenceComparisonService evidenceComparison;
    private final Mode2AlternativeRouteService alternatives;

    public Mode2AlignmentMcpTools(Mode2AlignmentScreeningService screening,
                                  Mode2ProjectEvidenceComparisonService evidenceComparison,
                                  Mode2AlternativeRouteService alternatives) {
        this.screening = screening;
        this.evidenceComparison = evidenceComparison;
        this.alternatives = alternatives;
    }

    @McpTool(name = "screen_mode2_project_alignment", title = "Screen an existing project alignment",
            description = "Run MARG's deterministic composite GIS diagnostics on an existing route alignment associated with a Mode 2 project. Supply GeoJSON LineString coordinates as [longitude, latitude] pairs in WGS84. Select a configured regional preset when needed. This screens the supplied geometry; it does not optimize or alter it. Returns GIS diagnostics, limitations, and evidence IDs suitable for evidence assembly.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult screenAlignment(CallToolRequest request) {
        try {
            var result = screening.screen(stringArg(request, "projectId"),
                    new Mode2AlignmentScreeningService.AlignmentScreeningRequest(
                            stringArg(request, "alignmentName"), nullableStringArg(request, "regionId"),
                            nullableStringArg(request, "facilityMode"), coordinates(request)));
            return CallToolResult.builder().structuredContent(result)
                    .addTextContent("Screened supplied alignment " + result.alignmentName() + " in region "
                            + result.regionId() + "; produced " + result.evidence().size()
                            + " GIS observations and marked " + result.notAssessedLayers().size() + " layer diagnostics unavailable.")
                    .build();
        } catch (Exception exception) {
            return CallToolResult.builder().isError(true)
                    .addTextContent("Mode 2 alignment GIS screening failed: " + exception.getMessage()).build();
        }
    }

    @McpTool(name = "compare_mode2_project_claims_to_gis", title = "Compare project claims with GIS screening",
            description = "Extract cited project claims, run deterministic GIS screening on the supplied WGS84 alignment, and compare only supported numeric pairs such as reported project length versus sampled GIS route length and reported water-crossing count versus GIS water-crossing count. Returns both source records, page citations, GIS evidence IDs, differences, and limitations. Potential differences are for review and are not compliance findings.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult compareClaimsToGis(CallToolRequest request) {
        try {
            String projectId = stringArg(request, "projectId");
            var alignment = new Mode2AlignmentScreeningService.AlignmentScreeningRequest(
                    stringArg(request, "alignmentName"), nullableStringArg(request, "regionId"),
                    nullableStringArg(request, "facilityMode"), coordinates(request));
            var result = evidenceComparison.compare(projectId, stringArg(request, "factQuery"),
                    intArg(request, "factLimit", 20), alignment);
            return CallToolResult.builder().structuredContent(result)
                    .addTextContent("Extracted " + result.projectFacts().facts().size()
                            + " cited project claims, screened the alignment, and produced "
                            + result.comparisons().size() + " comparable metric checks and listed "
                            + result.uncomparedProjectFacts().size() + " claims without a configured GIS equivalent.")
                    .build();
        } catch (Exception exception) {
            return CallToolResult.builder().isError(true)
                    .addTextContent("Mode 2 project-to-GIS comparison failed: " + exception.getMessage()).build();
        }
    }

    @McpTool(name = "recommend_mode2_alignment_alternative", title = "Generate and compare a GIS route candidate",
            description = "Generate one candidate route with the existing regional composite GIS routing engine between the submitted alignment's endpoints, then screen and compare the candidate against the submitted route. Returns candidate WGS84 coordinates, comparable GIS exposure indicators, evidence IDs, and limitations. A potential GIS advantage is a review prompt, not a route approval or legal/engineering determination.",
            generateOutputSchema = false,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public CallToolResult recommendAlternative(CallToolRequest request) {
        try {
            String projectId = stringArg(request, "projectId");
            var alignment = new Mode2AlignmentScreeningService.AlignmentScreeningRequest(
                    stringArg(request, "alignmentName"), nullableStringArg(request, "regionId"),
                    nullableStringArg(request, "facilityMode"), coordinates(request));
            var result = alternatives.recommend(projectId, alignment);
            return CallToolResult.builder().structuredContent(result)
                    .addTextContent("Generated one GIS-engine candidate in region " + result.existingRoute().regionId()
                            + "; comparison status: " + result.recommendationStatus() + ".")
                    .build();
        } catch (Exception exception) {
            return CallToolResult.builder().isError(true)
                    .addTextContent("Mode 2 alternative route analysis failed: " + exception.getMessage()).build();
        }
    }

    private List<List<Double>> coordinates(CallToolRequest request) {
        Object raw = request.arguments().get("coordinates");
        if (!(raw instanceof List<?> pairs)) throw new IllegalArgumentException("coordinates must be an array of [longitude, latitude] pairs.");
        List<List<Double>> result = new ArrayList<>();
        for (Object item : pairs) {
            if (!(item instanceof List<?> pair) || pair.size() < 2
                    || !(pair.get(0) instanceof Number longitude) || !(pair.get(1) instanceof Number latitude)) {
                throw new IllegalArgumentException("Each coordinate must contain numeric [longitude, latitude].");
            }
            result.add(List.of(longitude.doubleValue(), latitude.doubleValue()));
        }
        return List.copyOf(result);
    }

    private String stringArg(CallToolRequest request, String key) {
        Object value = request.arguments().get(key);
        return value == null ? "" : value.toString();
    }

    private String nullableStringArg(CallToolRequest request, String key) {
        String value = stringArg(request, key);
        return value.isBlank() ? null : value;
    }

    private int intArg(CallToolRequest request, String key, int fallback) {
        Object value = request.arguments().get(key);
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException exception) { return fallback; }
    }
}
