package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reviews the complete validated report draft before it is allowed into the document renderer. */
@Component
public class EnvironmentalReportQualityReviewer {

    private static final String REVIEW_PROMPT = """
            You are the final coherence reviewer for a preliminary environmental assessment report.
            Review the complete section-by-section draft and its cited evidence. Do not write or rewrite report content.
            Reject only material problems: sections contain content for the wrong environmental topic; the executive summary or findings contradict detailed sections; the same raw data or paragraph is dumped into multiple sections; text is incoherent or mostly internal field names; a material factual conclusion is not supported by its cited evidence; or the report makes an unsupported approval, compliance, impact, species, survey, or mitigation assertion.
            A section with a careful data-gap note is acceptable when its evidence is missing. Do not treat missing evidence as proof that an impact or receptor is absent. Do not reject a preliminary desktop report because it identifies fieldwork, expert review, or legal applicability as unresolved. GIS observations are screening evidence, not field-verified conditions.
            The evidence IDs and numeric support were checked separately. Focus on whether the prose is understandable, logically placed, non-duplicative, and consistent with the supplied evidence. Do not demand invented detail to make a section look complete.
            Return JSON only: {"passed":boolean,"summary":string,"blockingIssues":[{"sectionToken":string,"issue":string}],"warnings":[{"sectionToken":string,"issue":string}]}. Set passed=false only for one or more material blocking issues. Keep the summary and issues concise; do not include private reasoning.
            """;

    private static final Set<String> SYSTEM_TOKENS = Set.of("{{project_name}}", "{{project_proponent}}",
            "{{route_alternative_name}}", "{{study_area}}", "{{report_version}}", "{{assessment_date}}",
            "{{assessment_status}}", "{{assessment_scope_statement}}", "{{generated_table_of_contents}}",
            "{{environmental_evidence_register}}", "{{legal_and_knowledge_references}}",
            "{{project_and_gis_references}}", "{{applicability_status}}", "{{official_source_version}}",
            "{{legal_evidence_action}}", "{{legal_applicability_register}}",
            "{{environmental_clearance_applicability}}");
    private static final Set<String> TABLE_TOKENS = Set.of("{{phase_component}}", "{{pathway_basis}}",
            "{{significance_uncertainty}}", "{{mitigation_consideration}}", "{{evidence_ids}}",
            "{{impact_evidence_id}}", "{{action}}", "{{phase_location}}", "{{responsible_party}}",
            "{{timing_indicator_cost}}", "{{parameter_receptor}}", "{{location_method}}",
            "{{frequency_phase}}", "{{standard_trigger}}", "{{responsibility_reporting}}",
            "{{evidence_id}}", "{{evidence_type}}", "{{claim}}", "{{value_excerpt}}",
            "{{source_locator}}", "{{method_units_limits}}");

    private final EnvironmentalLlmClient llm;
    private final ObjectMapper objectMapper;

    public EnvironmentalReportQualityReviewer(EnvironmentalLlmClient llm, ObjectMapper objectMapper) {
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    public ReviewResult review(GenerateReportRequest request, Set<String> templateTokens) throws Exception {
        Map<String, EvidenceItem> evidenceById = new LinkedHashMap<>();
        request.evidence().forEach(item -> evidenceById.put(item.evidenceId(), item));
        Map<String, List<ReportClaim>> claimsByToken = new LinkedHashMap<>();
        request.claims().forEach(claim -> claimsByToken.computeIfAbsent(claim.templateToken(), ignored -> new ArrayList<>()).add(claim));

        List<Map<String, Object>> sections = new ArrayList<>();
        for (String token : templateTokens.stream().sorted().toList()) {
            if (SYSTEM_TOKENS.contains(token) || TABLE_TOKENS.contains(token)) continue;
            List<ReportClaim> claims = claimsByToken.getOrDefault(token, List.of());
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("templateToken", token);
            if (claims.isEmpty()) {
                section.put("content", "No evidence-backed narrative claim; the report template will show a topic-specific evidence-gap note.");
            } else {
                List<Map<String, Object>> paragraphs = new ArrayList<>();
                for (ReportClaim claim : claims) {
                    Map<String, Object> paragraph = new LinkedHashMap<>();
                    paragraph.put("sectionId", claim.sectionId());
                    paragraph.put("text", claim.statement());
                    paragraph.put("evidence", claim.evidenceIds().stream().map(evidenceById::get)
                            .filter(java.util.Objects::nonNull).map(this::reviewEvidence).toList());
                    paragraphs.add(paragraph);
                }
                section.put("claims", paragraphs);
            }
            sections.add(section);
        }

        List<Map<String, Object>> tables = request.tableRows().stream().map(row -> {
            Map<String, Object> table = new LinkedHashMap<>();
            table.put("tableId", row.tableId());
            table.put("cells", row.cells());
            table.put("evidence", row.evidenceIds().stream().map(evidenceById::get)
                    .filter(java.util.Objects::nonNull).map(this::reviewEvidence).toList());
            return table;
        }).toList();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("projectName", request.projectName());
        report.put("studyArea", request.studyArea());
        report.put("sections", sections);
        report.put("repeatableTables", tables);

        String response = llm.completeJson("environmental-report-review", REVIEW_PROMPT,
                "Review this complete draft after evidence and numeric validation. Return only the requested JSON review.\n"
                        + objectMapper.writeValueAsString(report));
        JsonNode root = objectMapper.readTree(response == null ? "" : response.trim());
        if (root == null || !root.isObject() || !root.has("passed") || !root.get("passed").isBoolean()
                || !root.has("summary") || !root.get("summary").isTextual()
                || root.get("summary").asString().isBlank()) {
            throw new IllegalStateException("LLM content review returned an invalid response; no report was rendered.");
        }
        List<ValidationIssue> blockers = parseIssues(root.get("blockingIssues"), "REPORT_CONTENT_COHERENCE");
        List<ValidationIssue> warnings = parseIssues(root.get("warnings"), "REPORT_CONTENT_REVIEW_WARNING");
        boolean passed = root.get("passed").asBoolean() && blockers.isEmpty();
        return new ReviewResult(passed, root.get("summary").asString().trim(), blockers, warnings);
    }

    private Map<String, Object> reviewEvidence(EvidenceItem item) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evidenceId", item.evidenceId());
        evidence.put("type", item.type() == null ? "" : item.type().name());
        if (item.type() == EvidenceType.GIS_OBSERVATION) {
            evidence.put("layer", item.gisLayer());
            evidence.put("metric", item.metric());
            evidence.put("value", item.value());
            evidence.put("units", item.units());
            evidence.put("method", item.operation());
            evidence.put("limitations", item.limitations());
        } else {
            evidence.put("sourceTitle", item.sourceTitle());
            evidence.put("fileName", item.sourceFileName());
            evidence.put("pageNumber", item.pageNumber());
            evidence.put("chunkId", item.chunkId());
            evidence.put("claim", item.claim());
            evidence.put("excerpt", compact(item.excerpt(), 600));
            evidence.put("applicability", item.applicability());
        }
        return evidence;
    }

    private List<ValidationIssue> parseIssues(JsonNode issues, String code) {
        if (issues == null || !issues.isArray()) return List.of();
        List<ValidationIssue> result = new ArrayList<>();
        for (JsonNode issue : issues) {
            if (result.size() >= 12) break;
            if (!issue.isObject() || !issue.has("issue") || !issue.get("issue").isTextual()) continue;
            String message = issue.get("issue").asString().trim();
            if (message.isBlank()) continue;
            String token = issue.has("sectionToken") && issue.get("sectionToken").isTextual()
                    ? issue.get("sectionToken").asString().trim() : null;
            result.add(new ValidationIssue(code, message, null, token));
        }
        return List.copyOf(result);
    }

    private String compact(String value, int limit) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit).stripTrailing() + " …";
    }

    public record ReviewResult(boolean passed, String summary, List<ValidationIssue> blockingIssues,
                               List<ValidationIssue> warnings) {
        public ReviewResult {
            blockingIssues = List.copyOf(blockingIssues);
            warnings = List.copyOf(warnings);
        }
    }
}
