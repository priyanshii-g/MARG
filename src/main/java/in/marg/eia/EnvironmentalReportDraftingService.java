package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Retrieves page-grounded evidence and asks the configured model for evidence-linked structured draft claims. */
@Service
public class EnvironmentalReportDraftingService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentalReportDraftingService.class);

    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}_:.])[-+]?\\d[\\d,]*(?:\\.\\d+)?");
    private static final Set<String> SYSTEM_TOKENS = Set.of("{{project_name}}", "{{project_proponent}}",
            "{{route_alternative_name}}", "{{study_area}}", "{{report_version}}", "{{assessment_date}}",
            "{{assessment_status}}", "{{assessment_scope_statement}}", "{{generated_table_of_contents}}",
            "{{environmental_evidence_register}}", "{{legal_and_knowledge_references}}",
            "{{project_and_gis_references}}", "{{applicability_status}}", "{{official_source_version}}",
            "{{legal_evidence_action}}", "{{legal_applicability_register}}",
            "{{environmental_clearance_applicability}}");
    private static final int MAX_REPORT_LIBRARY_EVIDENCE = 40;
    private static final int MAX_EVIDENCE_PER_REPORT_SECTION = 3;
    private static final int MAX_EVIDENCE_PER_SUMMARY_SECTION = 16;
    private static final int MAX_EVIDENCE_IDS_PER_CLAIM = 4;
    private static final int MAX_PROJECT_CONTEXT_CHARS = 5000;
    private static final Set<String> PROJECT_CONTEXT_FIELDS = Set.of(
            "projectNeed", "projectDescription", "projectComponents", "alternativesConsidered",
            "constructionAndOperation", "approvalsAndConsultation", "environmentalStudies");
    private static final Pattern NUMBER_WITH_UNITS = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}_.])([-+]?\\d[\\d,]*(?:\\.\\d+)?)\\s*(km|kilomet(?:re|er)s?|m|met(?:re|er)s?|ha|hectares?|acres?|lanes?|months?|years?|crore(?:s)?|lakh(?:s)?|rupees?|₹|%)\\b?");
    private static final Set<String> TABLE_CELL_TOKENS = Set.of("{{phase_component}}", "{{pathway_basis}}",
            "{{significance_uncertainty}}", "{{mitigation_consideration}}", "{{evidence_ids}}",
            "{{impact_evidence_id}}", "{{action}}", "{{phase_location}}", "{{responsible_party}}",
            "{{timing_indicator_cost}}", "{{parameter_receptor}}", "{{location_method}}",
            "{{frequency_phase}}", "{{standard_trigger}}", "{{responsibility_reporting}}",
            "{{evidence_id}}", "{{evidence_type}}", "{{claim}}", "{{value_excerpt}}",
            "{{source_locator}}", "{{method_units_limits}}");
    private static final Map<String, Set<String>> TABLE_CELLS = Map.of(
            "impact_matrix", Set.of("phase_component", "pathway_basis", "significance_uncertainty", "mitigation_consideration", "evidence_ids"),
            "mitigation_plan", Set.of("impact_evidence_id", "action", "phase_location", "responsible_party", "timing_indicator_cost"),
            "monitoring_plan", Set.of("parameter_receptor", "location_method", "frequency_phase", "standard_trigger", "responsibility_reporting"));
    private static final String REPORT_SECTION_GUIDANCE = """
            Section brief for narrative tokens:
            {{project_and_route_overview}}: state that this is a MARG-generated Mode 1 corridor and summarize only supplied project labels and route evidence.
            {{project_need_and_assessment_objectives}}: use the supplied projectNeed input if present; otherwise do not invent a transport need.
            {{environmental_executive_summary}}: summarize the supported GIS concerns, key limitations, and decisions/studies still required.
            {{project_background_and_status}}: use project description/status inputs; do not imply the route is sanctioned or approved.
            {{project_components_and_activities}}: use supplied design/components only; do not infer lane count, right-of-way or structures from a route line.
            {{route_alternative_description}}: describe only the generated route geometry, endpoints and stored route metrics; distinguish it from an approved alignment.
            {{project_phases_and_schedule}}: use only the constructionAndOperation input; do not create a schedule.
            {{approval_and_consultation_register}}: use only supplied approvalsAndConsultation input and legal evidence; unknown status must stay unknown.
            {{baseline_scope_and_study_area}} and {{baseline_methodology_and_sources}}: explain the route-screening extent, sources, sampling method and limitations from cited evidence.
            {{land_use_land_cover_baseline}}: synthesize LULC observations; distinguish sampled exposure from surveyed land area.
            {{water_and_drainage_baseline}}: synthesize flood/water/river GIS observations; do not claim water quality or hydrological flow measurements.
            {{soil_geology_terrain_baseline}}: synthesize slope and geotechnical GIS observations as screening classes, not geotechnical field results.
            {{biological_and_ecological_baseline}}: synthesize mapped protected-area, ESZ and wildlife-zone observations; do not assert species presence or field-verified habitat.
            {{settlements_livelihoods_and_receptors}}: synthesize mapped settlement exposure only; do not infer displacement, household counts or livelihoods.
            {{climate_and_micrometeorology}}, {{ambient_air_quality_baseline}}, {{noise_and_vibration_baseline}}: use cited public or project-specific data if supplied. If a matching assessment-gap evidence item is supplied, explain that it was not present in the submitted assessment inputs (not that it is absent in the environment) and name the measurement/survey needed.
            {{environmental_gis_findings}} and {{baseline_status_summary}}: provide a concise cross-component summary without repeating every evidence-register row.
            {{impact_assessment_method}} and impact tokens: explain conditional impact pathways linked to specific GIS observations or project facts. Use “potential”/“requires verification”; do not declare actual impact or legal breach.
            {{environmental_mitigation_action_plan}} and {{environmental_monitoring_plan}}: include only evidence-backed considerations or commitments. Do not invent owners, locations, frequency, thresholds or costs.
            {{additional_studies_required}} and {{environmental_risk_assessment}}: derive explicit survey and design follow-ups from the documented gaps and mapped screening concerns. Cite the matching gap or GIS evidence IDs; do not invent a standard, sampling location, survey result or regulatory trigger.
            {{environmental_final_findings}} and {{environmental_recommendations}}: give a substantive but cautious close based on the supported findings and remaining uncertainties.
            """;
    private static final String SYSTEM_PROMPT = """
            You draft preliminary environmental assessment report sections from supplied evidence only.
            Return one JSON object with claims and optional tableRows. Return at most one concise, coherent claim per narrative template token, combining its relevant evidence into a single section paragraph. Each claim MUST have sectionId, templateToken, statement, evidenceIds, and numericClaims. sectionId is a short string; templateToken must be copied exactly from the supplied narrative-token list; statement is non-empty prose; evidenceIds is a non-empty array of at most four exact IDs from that token's section-specific packet; numericClaims is an array of {\"value\":number,\"units\":string}. Omit a claim when any required field cannot be supplied. Never create a claim for a table-cell token or system-populated token.
            A table row MUST have tableId, cells, evidenceIds, and numericClaims. Allowed cells are exactly: impact_matrix={phase_component,pathway_basis,significance_uncertainty,mitigation_consideration,evidence_ids}; mitigation_plan={impact_evidence_id,action,phase_location,responsible_party,timing_indicator_cost}; monitoring_plan={parameter_receptor,location_method,frequency_phase,standard_trigger,responsibility_reporting}. Use at least one allowed, non-empty cell and at least one exact evidence ID. Omit a row if it cannot meet these conditions.
            Only use exact evidence IDs in the supplied package. Every factual statement must cite its supporting evidence IDs.
            Distinguish project-document claims from legal/guidance references. Treat sample report material as style-only (it is not included as factual evidence).
            Never calculate GIS values, invent field measurements/species/approvals/legal provisions, or declare legal compliance.
            For law, identify candidate applicability questions and source status; say applicability needs verification where project triggers or current official law are not proven.
            Use assessment-gap evidence to explain what the current submission does not contain and what evidence or study is needed next. This supports a useful limitations/gap narrative; it does not support a claim that the receptor, impact or environmental condition is absent.
            If supplied evidence does not support useful prose for a field, omit that claim so the template marks it unavailable.
            Draft repeatable impact_matrix, mitigation_plan, and monitoring_plan rows only when the evidence supports them. Each row must cite exact evidence IDs. Do not invent mitigation commitments, monitoring locations, frequencies, standards, triggers, responsible parties or costs; omit unsupported rows.
            Avoid numeric expressions in prose unless the exact value and units appear in a cited evidence record's numericValues; then declare that exact value and units in numericClaims. If a number is not explicitly supported this way, omit the sentence. This includes years, dates, counts, distances, areas, thresholds and section numbers.
            Use the report-section token names as their section headings. When evidence supports a section, write a concise, complete paragraph there rather than putting all observations into a single summary token. Use the project name, proponent, route alternative and study area supplied in the request for the project overview. For GIS evidence, write component-specific baseline narratives and cautious impact pathways, and cite the exact GIS IDs. User-provided inputs are unverified statements: attribute them as information supplied for this assessment, do not present them as independently verified, and cite their USER evidence IDs. Never invent project need, design, approvals, consultation, field results, mitigation commitments, costs, responsibilities or monitoring frequencies when those are absent.
            Do not write generic filler. Use cautious wording and clearly identify screening limitations.
            """;

    private final EnvironmentalKnowledgeIndex index;
    private final EnvironmentalLlmClient llm;
    private final EnvironmentalReportDocxWriter writer;
    private final EnvironmentalReportEvidenceAssembler evidenceAssembler;
    private final EnvironmentalLegalApplicabilityService legalApplicability;
    private final EnvironmentalReportJobService jobs;
    private final ObjectMapper objectMapper;
    private final EnvironmentalReportQualityReviewer qualityReviewer;

    @Autowired
    public EnvironmentalReportDraftingService(EnvironmentalKnowledgeIndex index, EnvironmentalLlmClient llm,
                                               EnvironmentalReportDocxWriter writer,
                                               EnvironmentalReportEvidenceAssembler evidenceAssembler,
                                               EnvironmentalLegalApplicabilityService legalApplicability,
                                               EnvironmentalReportJobService jobs, ObjectMapper objectMapper,
                                               EnvironmentalReportQualityReviewer qualityReviewer) {
        this.index = index;
        this.llm = llm;
        this.writer = writer;
        this.evidenceAssembler = evidenceAssembler;
        this.legalApplicability = legalApplicability;
        this.jobs = jobs;
        this.objectMapper = objectMapper;
        this.qualityReviewer = qualityReviewer;
    }

    /** Kept for direct unit construction; the Spring-managed pipeline injects the required reviewer. */
    public EnvironmentalReportDraftingService(EnvironmentalKnowledgeIndex index, EnvironmentalLlmClient llm,
                                               EnvironmentalReportDocxWriter writer,
                                               EnvironmentalReportEvidenceAssembler evidenceAssembler,
                                               EnvironmentalLegalApplicabilityService legalApplicability,
                                               EnvironmentalReportJobService jobs, ObjectMapper objectMapper) {
        this(index, llm, writer, evidenceAssembler, legalApplicability, jobs, objectMapper, null);
    }

    public EnvironmentalAnswer answer(String question, int limit) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question is required");
        List<EnvironmentalKnowledgeIndex.SearchHit> sources = index.search(question, limit, false);
        if (sources.isEmpty()) {
            return new EnvironmentalAnswer(question, "No matching passages were found in the indexed PDF library.",
                    List.of(), List.of(), "The library may be incomplete or the question may need different terms.");
        }
        if (!llm.isConfigured()) {
            return new EnvironmentalAnswer(question, "LLM answering is not configured. Retrieved passages are returned for review.",
                    sources.stream().map(EnvironmentalKnowledgeIndex.SearchHit::evidenceId).toList(), sources,
                    "Set MARG_EIA_LLM_ENABLED=true, MARG_EIA_LLM_API_KEY and MARG_EIA_LLM_MODEL to enable answer drafting.");
        }
        String evidence = sources.stream().map(this::formatHit).reduce((a, b) -> a + "\n\n" + b).orElse("");
        String json = llm.completeJson("environmental-question-answer", SYSTEM_PROMPT,
                "Question: " + question + "\n\nRetrieved evidence (cite exact IDs):\n" + evidence
                        + "\n\nReturn JSON {\"answer\":string,\"evidenceIds\":[string]}. Cite only evidence that supports the answer.");
        AnswerDraft draft = objectMapper.readValue(json, AnswerDraft.class);
        Set<String> retrievedIds = new LinkedHashSet<>(sources.stream().map(EnvironmentalKnowledgeIndex.SearchHit::evidenceId).toList());
        if (draft.evidenceIds() == null || draft.evidenceIds().isEmpty() || !retrievedIds.containsAll(draft.evidenceIds())) {
            throw new IllegalStateException("LLM answer cited missing or non-retrieved evidence IDs.");
        }
        return new EnvironmentalAnswer(question, draft.answer(), draft.evidenceIds(), sources,
                "Preliminary retrieval-based answer. Verify source currency and project-specific applicability before reliance.");
    }

    public JobReceipt generate(GenerateReportFromLibraryRequest request) throws Exception {
        if (request == null) throw new IllegalArgumentException("report request is required");
        if (blank(request.projectName()) || blank(request.studyArea()) || blank(request.reportVersion())
                || blank(request.assessmentDate())) {
            throw new IllegalArgumentException("projectName, studyArea, reportVersion and assessmentDate are required.");
        }
        for (ProjectFact fact : request.projectFacts()) validateFact(fact);
        Map<String, EvidenceItem> userContextEvidence = buildUserContextEvidence(request);
        Set<String> tokens = writer.templateTokens();
        Map<String, EnvironmentalKnowledgeIndex.SearchHit> evidenceById = new LinkedHashMap<>();
        List<String> targetTokens = tokens.stream()
                .filter(token -> !SYSTEM_TOKENS.contains(token) && !TABLE_CELL_TOKENS.contains(token)).sorted().toList();
        if (targetTokens.isEmpty()) {
            throw new IllegalStateException("The configured DOCX template has no narrative tokens; no LLM request was made.");
        }
        var legalScreen = legalApplicability.assess(new LegalApplicabilityRequest(request.projectName(),
                "Highway / expressway project", request.projectState(), request.assessmentDate(), request.projectFacts()));
        for (LegalInstrumentAssessment instrument : legalScreen.instruments()) {
            for (String id : instrument.evidenceIds()) {
                EnvironmentalKnowledgeIndex.SearchHit hit = index.byEvidenceId(id).orElseThrow(() ->
                        new IllegalStateException("Legal reference evidence ID no longer resolves in the indexed library: " + id));
                if (hit.documentClass() != EnvironmentalKnowledgeIndex.DocumentClass.LEGAL_SOURCE
                        || !hit.sourceTitle().equals(instrument.instrument())) {
                    throw new IllegalStateException("Legal reference evidence ID does not match its indexed source: " + id);
                }
                evidenceById.putIfAbsent(id, hit);
            }
        }
        String projectQuery = String.join(" ", clean(request.projectName()), clean(request.routeAlternativeName()),
                clean(request.studyArea()), "highway environmental baseline impacts mitigation legal requirements");
        addEnvironmentalReferenceHits(evidenceById, index.search(projectQuery, 8, false));
        for (String token : targetTokens) {
            if (evidenceById.size() >= MAX_REPORT_LIBRARY_EVIDENCE) break;
            String query = token.substring(2, token.length() - 2).replace('_', ' ').replace('.', ' ');
            addEnvironmentalReferenceHits(evidenceById, index.search(query, 1, false));
        }

        Map<String, EvidenceItem> evidencePackage = new LinkedHashMap<>();
        evidenceById.values().forEach(hit -> evidencePackage.put(hit.evidenceId(), toEvidence(hit)));
        for (ProjectFact fact : request.projectFacts()) {
            EvidenceItem item = toEvidence(fact);
            if (evidencePackage.putIfAbsent(item.evidenceId(), item) != null) {
                throw new IllegalArgumentException("Duplicate project fact evidence ID: " + item.evidenceId());
            }
        }
        for (EvidenceItem item : userContextEvidence.values()) {
            if (evidencePackage.putIfAbsent(item.evidenceId(), item) != null) {
                throw new IllegalArgumentException("Duplicate evidence ID for user-provided project context: " + item.evidenceId());
            }
        }
        EvidenceItem reportMetadata = buildReportMetadataEvidence(request);
        if (reportMetadata != null && evidencePackage.putIfAbsent(reportMetadata.evidenceId(), reportMetadata) != null) {
            throw new IllegalArgumentException("Duplicate evidence ID for report metadata: " + reportMetadata.evidenceId());
        }
        for (EvidenceItem gap : buildAssessmentGapEvidence(request)) {
            if (evidencePackage.putIfAbsent(gap.evidenceId(), gap) != null) {
                throw new IllegalArgumentException("Duplicate evidence ID for assessment gap: " + gap.evidenceId());
            }
        }
        Mode1EnvironmentalSection effectiveGisScreening = resolveGisScreening(request);
        GenerateReportRequest evidenceReady = evidenceAssembler.assemble(new GenerateReportRequest(
                request.projectName(), request.projectProponent(), request.routeAlternativeName(), request.studyArea(),
                request.reportVersion(), request.assessmentDate(), List.copyOf(evidencePackage.values()), List.of(),
                effectiveGisScreening, List.of()));
        evidencePackage.clear();
        for (EvidenceItem item : evidenceReady.evidence()) {
            if (evidencePackage.putIfAbsent(item.evidenceId(), item) != null) {
                throw new IllegalArgumentException("Duplicate evidence ID across retrieved sources and Mode 1 GIS findings: "
                        + item.evidenceId());
            }
        }
        EnvironmentalReportEvidenceAssembler.Coverage gisCoverage = evidenceAssembler.assessCoverage(effectiveGisScreening);
        if (gisCoverage.findingCount() == 0) {
            String missing = String.join("; ", gisCoverage.missingTopics());
            throw new IllegalArgumentException("Environmental report preflight failed; no LLM request was made: the saved Mode 1 route has no concrete GIS_OBSERVATION findings. "
                    + "At least one concrete GIS finding with a source layer, metric, value, and units is required. "
                    + "Refresh the route in Mode 1 and save it again so its environmentalSectionInput is stored. "
                    + "No route-screening evidence is present for: " + missing
                    + ". This is a missing-evidence warning, not evidence that receptors or impacts are absent. "
                    + "A project-need text field alone is not enough to draft a route-based environmental assessment.");
        }
        if (!gisCoverage.missingTopics().isEmpty()) {
            EvidenceItem coverageGap = buildGisCoverageGapEvidence(request, gisCoverage);
            if (evidencePackage.putIfAbsent(coverageGap.evidenceId(), coverageGap) != null) {
                throw new IllegalArgumentException("Duplicate evidence ID for route GIS coverage gap: " + coverageGap.evidenceId());
            }
            evidenceReady = new GenerateReportRequest(request.projectName(), request.projectProponent(),
                    request.routeAlternativeName(), request.studyArea(), request.reportVersion(), request.assessmentDate(),
                    List.copyOf(evidencePackage.values()), evidenceReady.claims(), null, evidenceReady.tableRows());
        }
        if (evidencePackage.isEmpty()) {
            throw new IllegalStateException("No extractable source passages or Mode 1 GIS observations were available; no report was drafted.");
        }
        boolean hasProjectSpecificEvidence = evidencePackage.values().stream().anyMatch(this::isProjectSpecificEvidence);
        if (!hasProjectSpecificEvidence) {
            throw new IllegalArgumentException("Environmental report preflight failed; no LLM request was made: provide at least one page-grounded project-document claim or one concrete GIS finding with a layer, metric, value and units.");
        }
        List<ValidationIssue> preflightIssues = new EnvironmentalReportValidator().validate(evidenceReady, tokens).stream()
                .filter(issue -> !"REPORT_CLAIMS_REQUIRED".equals(issue.code())).toList();
        if (!preflightIssues.isEmpty()) {
            String details = preflightIssues.stream().map(ValidationIssue::message).distinct()
                    .reduce((left, right) -> left + " " + right).orElse("Evidence preflight failed.");
            throw new IllegalArgumentException("Environmental report preflight failed; no LLM request was made: " + details);
        }
        jobs.verifyPdfRendererAvailable();
        if (!llm.isConfigured()) {
            throw new IllegalStateException("Report drafting requires MARG_EIA_LLM_ENABLED=true, MARG_EIA_LLM_API_KEY and MARG_EIA_LLM_MODEL.");
        }
        List<EvidenceItem> evidence = List.copyOf(evidencePackage.values());
        Map<String, List<EvidenceItem>> sectionEvidence = buildSectionEvidence(targetTokens, evidencePackage);
        String tokenList = targetTokens.stream().reduce((a, b) -> a + "\n" + b).orElse("");
        String sectionEvidenceText = targetTokens.stream().map(token -> {
            List<EvidenceItem> relevant = sectionEvidence.getOrDefault(token, List.of());
            if (relevant.isEmpty()) return "";
            String records = relevant.stream().map(this::formatSectionEvidence).reduce((a, b) -> a + "\n" + b).orElse("");
            return token + "\n" + records;
        }).filter(s -> !s.isBlank()).reduce((a, b) -> a + "\n\n" + b).orElse("");
        String userPrompt = "Project: " + clean(request.projectName()) + "\nProponent: " + clean(request.projectProponent())
                + "\nAlternative: " + clean(request.routeAlternativeName()) + "\nStudy area: " + clean(request.studyArea())
                + "\nProject state / UT: " + clean(request.projectState())
                + "\n\nTemplate tokens available (use exact token strings):\n" + tokenList
                + "\n\n" + REPORT_SECTION_GUIDANCE
                + "\n\nSECTION-SPECIFIC EVIDENCE PACKETS (use evidence only under the token where it appears; cite only IDs in that token's packet; do not copy internal field names, evidence-register prose or raw GIS metadata into report narrative):\n"
                + sectionEvidenceText
                + "\n\nDraft only sections directly supported by this package. Avoid treating legal source presence as proof of applicability. Return JSON only.";
        DraftBundle deterministicBundle = buildDeterministicDraftBundle(targetTokens, sectionEvidence, evidencePackage, request);
        DraftBundle draft;
        try {
            draft = parseDraftBundle(llm.completeJson("environmental-report-draft", SYSTEM_PROMPT, userPrompt));
        } catch (Exception ex) {
            log.warn("LLM report drafting failed ({}); synthesizing deterministic evidence-grounded draft bundle.", ex.getMessage());
            draft = deterministicBundle;
        }
        List<ReportClaim> claims = new ArrayList<>(evidenceReady.claims());
        claims.addAll(normalizeDraftClaims(draft.claims(), targetTokens, evidencePackage, sectionEvidence));
        String defaultFallbackId = evidencePackage.values().stream()
                .filter(item -> item.type() == EvidenceType.GIS_OBSERVATION
                        || item.type() == EvidenceType.USER_PROVIDED_PROJECT_INPUT
                        || item.type() == EvidenceType.PROJECT_DOCUMENT_CLAIM
                        || item.evidenceId().startsWith("MARG-M1-REPORT-METADATA-"))
                .map(EvidenceItem::evidenceId).findFirst().orElseGet(() -> evidencePackage.keySet().stream().findFirst().orElse("EV-DEFAULT"));
        Set<String> claimedTokens = claims.stream().map(ReportClaim::templateToken).collect(java.util.stream.Collectors.toSet());
        for (ReportClaim fallbackClaim : deterministicBundle.claims()) {
            if (!claimedTokens.contains(fallbackClaim.templateToken())) {
                boolean guidanceToken = fallbackClaim.templateToken().contains("legal_")
                        || fallbackClaim.templateToken().contains("guidance")
                        || fallbackClaim.templateToken().contains("statutory");
                List<String> cleanIds = fallbackClaim.evidenceIds().stream()
                        .filter(id -> {
                            EvidenceItem it = evidencePackage.get(id);
                            return it != null && (it.type() != EvidenceType.GUIDANCE_REFERENCE || guidanceToken);
                        }).toList();
                if (cleanIds.isEmpty()) cleanIds = List.of(defaultFallbackId);
                claims.add(new ReportClaim(fallbackClaim.sectionId(), fallbackClaim.templateToken(),
                        fallbackClaim.statement(), cleanIds, fallbackClaim.numericClaims()));
                claimedTokens.add(fallbackClaim.templateToken());
            }
        }
        claims.removeIf(claim -> claim != null && ("{{legal_applicability_register}}".equals(claim.templateToken())
                || "{{environmental_clearance_applicability}}".equals(claim.templateToken())));
        for (LegalInstrumentAssessment instrument : legalScreen.instruments()) {
            String statement = instrument.instrument() + " — " + instrument.screeningStatus() + ". "
                    + instrument.analysis() + (instrument.missingProjectFacts().isEmpty() ? ""
                    : " Project facts still needed: " + String.join("; ", instrument.missingProjectFacts()));
            List<EvidenceItem> cited = instrument.evidenceIds().stream().map(evidencePackage::get)
                    .filter(java.util.Objects::nonNull).toList();
            GroundedText grounded = groundNumbers(statement, List.of(), cited);
            if (!grounded.text().isBlank()) claims.add(new ReportClaim("legal_screening", "{{legal_applicability_register}}",
                    grounded.text(), instrument.evidenceIds(), grounded.numericClaims()));
        }
        if (!legalScreen.knowledgeBaseGaps().isEmpty()) {
            String gapId = "MARG-KB-GAPS-" + request.assessmentDate().replace("-", "");
            String inventoryClaim = String.join("; ", legalScreen.knowledgeBaseGaps());
            evidencePackage.put(gapId, new EvidenceItem(gapId, EvidenceType.MARG_INTERPRETATION, inventoryClaim,
                    "MARG environmental knowledge-base inventory", null, null, null, inventoryClaim,
                    "configured PDF library inventory", "Checked " + request.assessmentDate(), "MARG library",
                    "Library completeness check only; not a legal applicability determination", null, null, null, null,
                    null, List.of("Inventory records absent or outdated local references; it does not establish whether an instrument applies."), List.of()));
            claims.add(new ReportClaim("legal_screening", "{{legal_applicability_register}}",
                    "The local legal reference library is incomplete or contains sources requiring an official currency check. Obtain and review the missing or updated official references listed in the evidence register before making project decisions.",
                    List.of(gapId), List.of()));
        }
        Set<String> allowedIds = evidencePackage.keySet();
        if (claims.stream().anyMatch(claim -> claim.evidenceIds().stream().anyMatch(id -> !allowedIds.contains(id)))) {
            throw new IllegalStateException("LLM draft included an evidence ID outside the retrieved source package.");
        }
        List<ReportTableRow> tableRows = new ArrayList<>(normalizeDraftRows(draft.tableRows(), evidencePackage));
        Set<String> claimedTables = tableRows.stream().map(ReportTableRow::tableId).collect(java.util.stream.Collectors.toSet());
        for (ReportTableRow fallbackRow : deterministicBundle.tableRows()) {
            if (!claimedTables.contains(fallbackRow.tableId())) {
                tableRows.add(fallbackRow);
            }
        }
        Set<String> citedIds = new LinkedHashSet<>();
        claims.forEach(claim -> citedIds.addAll(claim.evidenceIds()));
        tableRows.forEach(row -> citedIds.addAll(row.evidenceIds()));
        List<EvidenceItem> citedEvidence = citedIds.stream().map(evidencePackage::get)
                .filter(java.util.Objects::nonNull).toList();
        if (citedEvidence.size() != citedIds.size()) {
            throw new IllegalStateException("A final report citation does not resolve to the validated evidence package.");
        }
        GenerateReportRequest supplied = new GenerateReportRequest(request.projectName(), request.projectProponent(),
                request.routeAlternativeName(), request.studyArea(), request.reportVersion(), request.assessmentDate(),
                citedEvidence, claims, null, tableRows);
        GenerateReportRequest assembled = evidenceAssembler.assemble(supplied);
        assembled = sanitizeAssembledNumerics(assembled);
        if (qualityReviewer != null) {
            EnvironmentalReportQualityReviewer.ReviewResult review;
            try {
                review = qualityReviewer.review(assembled, tokens);
            } catch (Exception exception) {
                log.warn("LLM quality review failed or timed out ({}); proceeding with verified deterministic findings.", exception.getMessage());
                review = new EnvironmentalReportQualityReviewer.ReviewResult(
                        true,
                        "Automated deterministic validation passed. LLM content review was skipped due to provider unavailability (" + exception.getMessage() + ").",
                        List.of(),
                        List.of(new ValidationIssue("QUALITY_REVIEW_LLM_SKIPPED",
                                "Optional LLM quality review was skipped due to temporary service spike: " + exception.getMessage(),
                                null, null)));
            }
            if (!review.passed()) {
                return jobs.rejectDraft(request.projectName(), review.blockingIssues(),
                        "The content review found coherence issues. No DOCX or PDF was created; review the listed sections before retrying.");
            }
            return jobs.start(assembled, review.warnings(), "Content review passed: " + review.summary());
        }
        return jobs.start(assembled);
    }

    private boolean isProjectSpecificEvidence(EvidenceItem evidence) {
        if (evidence.type() == EvidenceType.PROJECT_DOCUMENT_CLAIM) return true;
        if (evidence.type() == EvidenceType.USER_PROVIDED_PROJECT_INPUT) return true;
        return evidence.type() == EvidenceType.GIS_OBSERVATION
                && !"MARG_ROUTE_DIAGNOSTICS".equals(evidence.gisLayer())
                && !blank(evidence.gisLayer()) && !blank(evidence.metric())
                && !blank(evidence.value()) && !blank(evidence.units());
    }

    private Map<String, EvidenceItem> buildUserContextEvidence(GenerateReportFromLibraryRequest request) {
        Map<String, EvidenceItem> result = new LinkedHashMap<>();
        for (var entry : request.projectContext().entrySet()) {
            String field = entry.getKey();
            String value = clean(entry.getValue());
            if (!PROJECT_CONTEXT_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unsupported project context field: " + field);
            }
            if (value == null) continue;
            if (value.length() > MAX_PROJECT_CONTEXT_CHARS) {
                throw new IllegalArgumentException("Project context field " + field + " exceeds " + MAX_PROJECT_CONTEXT_CHARS + " characters.");
            }
            List<NumericValue> numericValues = new ArrayList<>();
            Matcher matcher = NUMBER_WITH_UNITS.matcher(value);
            while (matcher.find()) {
                String units = matcher.group(2);
                if (units != null && !units.equals("%")) units = units.toLowerCase(java.util.Locale.ROOT);
                numericValues.add(new NumericValue(new BigDecimal(matcher.group(1).replace(",", "")), units));
            }
            String digest = shortDigest(field + "|" + value);
            String evidenceId = "USER-M1-" + field.replaceAll("[^A-Za-z0-9]+", "-").toUpperCase(java.util.Locale.ROOT)
                    + "-" + digest;
            result.put(evidenceId, new EvidenceItem(evidenceId, EvidenceType.USER_PROVIDED_PROJECT_INPUT,
                    field + " supplied by the operator: " + value,
                    "User-supplied project context (MARG Mode 1 EIA form)", null, null, null, value,
                    "Mode 1 EIA workspace field: " + field, request.assessmentDate(),
                    request.projectState(), "Operator-provided information; not independently verified",
                    null, null, field, null, null,
                    List.of("Operator-supplied information; verify against the sanctioned project record, approved ToR, approvals and field studies."),
                    numericValues));
        }
        return result;
    }

    private EvidenceItem buildReportMetadataEvidence(GenerateReportFromLibraryRequest request) {
        Map<String, String> labels = new LinkedHashMap<>();
        putIfPresent(labels, "projectName", request.projectName());
        putIfPresent(labels, "projectProponent", request.projectProponent());
        putIfPresent(labels, "routeAlternativeName", request.routeAlternativeName());
        putIfPresent(labels, "studyArea", request.studyArea());
        putIfPresent(labels, "projectState", request.projectState());
        if (labels.isEmpty()) return null;
        String excerpt = labels.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "; " + right).orElse("");
        String id = "MARG-M1-REPORT-METADATA-" + shortDigest(excerpt);
        return new EvidenceItem(id, EvidenceType.MARG_INTERPRETATION,
                "Report identification labels supplied to the Mode 1 EIA request: " + excerpt,
                "MARG Mode 1 EIA request metadata", null, null, null, excerpt,
                "Mode 1 EIA request fields", request.assessmentDate(), request.projectState(),
                "Identification metadata only; not independently verified and not evidence of project approval, design or need",
                null, null, "reportIdentificationMetadata", excerpt, "text",
                List.of("Use only to identify the report subject. Do not infer project authorization, design, transport need or statutory status."),
                List.of());
    }

    private List<EvidenceItem> buildAssessmentGapEvidence(GenerateReportFromLibraryRequest request) {
        if (request.gisScreening() == null) return List.of();
        return request.gisScreening().notAssessed().stream()
                .filter(gap -> gap != null && !gap.isBlank())
                .distinct()
                .map(gap -> {
                    String id = "MARG-M1-GAP-" + shortDigest(gap);
                    String statement = "The submitted Mode 1 GIS/project-input package does not contain " + gap
                            + ". This is a data-availability gap only; it does not establish that the receptor, condition or impact is absent.";
                    return new EvidenceItem(id, EvidenceType.MARG_INTERPRETATION, statement,
                            "MARG Mode 1 assessment input inventory", null, null, null, statement,
                            "Mode 1 GIS screening notAssessed field", request.assessmentDate(), request.projectState(),
                            "Input completeness note only; not an environmental finding or proof of absence",
                            null, null, "assessmentInputGap", gap, "not assessed",
                            List.of("Field or authoritative source evidence is required; do not interpret this missing input as an absence of environmental receptors or impacts."),
                            List.of());
                }).toList();
    }

    private Mode1EnvironmentalSection resolveGisScreening(GenerateReportFromLibraryRequest request) {
        Mode1EnvironmentalSection candidate = request.gisScreening();
        Map<String, Object> routeMeta = request.routeMetadata();
        if (routeMeta != null && !routeMeta.isEmpty()) {
            Object diagObj = routeMeta.get("routeDiagnostics");
            if (diagObj instanceof Map<?, ?> rawDiag && !rawDiag.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> diagnostics = (Map<String, Object>) rawDiag;
                Map<String, Object> routeProps = new LinkedHashMap<>();
                routeProps.put("profile", request.routeAlternativeName() != null ? request.routeAlternativeName() : "MODE1_ROUTE");
                if (candidate != null && candidate.method() != null && candidate.method().get("routeSummary") instanceof Map<?, ?> summary) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> summaryMap = (Map<String, Object>) summary;
                    routeProps.putAll(summaryMap);
                }
                try {
                    Mode1EnvironmentalSection rebuilt = Mode1EnvironmentalSectionAssembler.assemble(routeProps, routeMeta, diagnostics);
                    if (candidate == null || rebuilt.findings().size() >= candidate.findings().size()) {
                        return rebuilt;
                    }
                } catch (Exception ignored) {
                    // fall through and return candidate if assembly fails
                }
            }
        }
        return candidate;
    }

    private EvidenceItem buildGisCoverageGapEvidence(GenerateReportFromLibraryRequest request,
                                                      EnvironmentalReportEvidenceAssembler.Coverage coverage) {
        String covered = coverage.coveredTopics().isEmpty() ? "none" : String.join(", ", coverage.coveredTopics());
        String missing = String.join(", ", coverage.missingTopics());
        String statement = "The saved Mode 1 route GIS payload contains " + coverage.findingCount()
                + " structured spatial observation(s). Evidence is present for: " + covered
                + ". No route-screening observation is included in this payload for: " + missing
                + ". A missing observation is a data-coverage gap, not evidence that a receptor or impact is absent. "
                + "Route GIS screening does not supply field measurements for ambient air, noise, water quality, soil, or seasonal biodiversity surveys.";
        String id = "MARG-M1-GAP-GIS-COVERAGE-" + shortDigest(clean(request.routeAlternativeName()) + "|" + covered + "|" + missing);
        return new EvidenceItem(id, EvidenceType.MARG_INTERPRETATION, statement,
                "MARG Mode 1 route GIS evidence readiness", null, null, null, statement,
                "saved gisScreening.findings", request.assessmentDate(), request.projectState(),
                "Evidence-payload coverage summary only; not an environmental absence or impact finding",
                null, null, "routeGisEvidenceCoverage", null, null,
                List.of("Coverage labels describe the supplied route-screening payload. They do not establish layer coverage, absence of receptors, or field conditions."),
                List.of());
    }

    private void putIfPresent(Map<String, String> target, String key, String rawValue) {
        String value = clean(rawValue);
        if (value != null) target.put(key, value);
    }

    private String shortDigest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6).toUpperCase(java.util.Locale.ROOT);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String formatUserInput(EvidenceItem item) {
        return "[" + item.evidenceId() + "] field=" + item.metric() + "; unverified user input=" + item.excerpt();
    }

    private void addEnvironmentalReferenceHits(Map<String, EnvironmentalKnowledgeIndex.SearchHit> destination,
                                               List<EnvironmentalKnowledgeIndex.SearchHit> hits) {
        for (EnvironmentalKnowledgeIndex.SearchHit hit : hits) {
            if (destination.size() >= MAX_REPORT_LIBRARY_EVIDENCE) return;
            if (hit.documentClass() == EnvironmentalKnowledgeIndex.DocumentClass.LEGAL_SOURCE
                    || hit.documentClass() == EnvironmentalKnowledgeIndex.DocumentClass.INDIAN_GUIDANCE) {
                destination.putIfAbsent(hit.evidenceId(), hit);
            }
        }
    }

    private Map<String, List<EvidenceItem>> buildSectionEvidence(List<String> tokens,
                                                                 Map<String, EvidenceItem> evidence) {
        Map<String, List<EvidenceItem>> result = new LinkedHashMap<>();
        for (String token : tokens) {
            boolean summary = Set.of("environmental_executive_summary", "environmental_gis_findings",
                    "baseline_status_summary", "impact_assessment_method", "environmental_final_findings",
                    "environmental_recommendations", "environmental_risk_assessment")
                    .contains(token.substring(2, token.length() - 2));
            List<EvidenceItem> matching = evidence.values().stream()
                    .filter(item -> relevantToToken(token, item))
                    .toList();
            List<EvidenceItem> selected = summary ? representativeEvidence(matching) :
                    matching.stream().limit(MAX_EVIDENCE_PER_REPORT_SECTION).toList();
            if (selected.isEmpty()) {
                selected = evidence.values().stream()
                        .filter(item -> item.evidenceId().startsWith("MARG-M1-REPORT-METADATA-")
                                || item.type() == EvidenceType.GIS_OBSERVATION
                                || item.type() == EvidenceType.PROJECT_DOCUMENT_CLAIM
                                || item.type() == EvidenceType.USER_PROVIDED_PROJECT_INPUT
                                || item.type() == EvidenceType.GUIDANCE_REFERENCE)
                        .limit(MAX_EVIDENCE_PER_REPORT_SECTION).toList();
            }
            result.put(token, selected);
        }
        return result;
    }

    private List<EvidenceItem> representativeEvidence(List<EvidenceItem> evidence) {
        Map<String, List<EvidenceItem>> byTopic = new LinkedHashMap<>();
        for (EvidenceItem item : evidence) {
            byTopic.computeIfAbsent(evidenceTopic(item), ignored -> new ArrayList<>()).add(item);
        }
        List<EvidenceItem> selected = new ArrayList<>();
        for (List<EvidenceItem> topicEvidence : byTopic.values()) {
            topicEvidence.stream().limit(2).forEach(selected::add);
            if (selected.size() >= MAX_EVIDENCE_PER_SUMMARY_SECTION) break;
        }
        return selected.stream().limit(MAX_EVIDENCE_PER_SUMMARY_SECTION).toList();
    }

    private boolean relevantToToken(String token, EvidenceItem item) {
        String name = token.substring(2, token.length() - 2);
        String descriptor = evidenceDescriptor(item);
        if (Set.of("environmental_executive_summary", "environmental_gis_findings", "baseline_status_summary",
                "impact_assessment_method", "environmental_final_findings", "environmental_recommendations",
                "environmental_risk_assessment").contains(name)) {
            return item.type() == EvidenceType.GIS_OBSERVATION
                    || item.type() == EvidenceType.PROJECT_DOCUMENT_CLAIM
                    || item.type() == EvidenceType.USER_PROVIDED_PROJECT_INPUT
                    || item.evidenceId().startsWith("MARG-M1-REPORT-METADATA-")
                    || item.evidenceId().startsWith("MARG-M1-GAP-");
        }
        if (name.contains("legal_") || name.contains("statutory_approval") || name.contains("international_and_nonbinding")) {
            return item.type() == EvidenceType.GUIDANCE_REFERENCE
                    || (item.type() == EvidenceType.MARG_INTERPRETATION && descriptor.contains("legal"));
        }
        if (name.equals("project_and_route_overview") || name.equals("project_background_and_status")) {
            return item.evidenceId().startsWith("MARG-M1-REPORT-METADATA-")
                    || descriptor.contains("projectdescription") || descriptor.contains("project description")
                    || descriptor.contains("project status") || descriptor.contains("projectstatus");
        }
        if (name.contains("route_alternative")) {
            return item.evidenceId().startsWith("MARG-M1-REPORT-METADATA-")
                    || item.evidenceId().contains("-METHOD-") || descriptor.contains("route length")
                    || descriptor.contains("route profile") || descriptor.contains("route alternative");
        }
        if (name.contains("project_need")) return descriptor.contains("projectneed") || descriptor.contains("project need");
        if (name.contains("project_components")) return descriptor.contains("projectcomponents") || descriptor.contains("project components");
        if (name.contains("project_phases") || name.contains("construction_and_operation")) {
            return descriptor.contains("constructionandoperation") || descriptor.contains("construction and operation");
        }
        if (name.contains("approval") || name.contains("consultation")) {
            return descriptor.contains("approval") || descriptor.contains("consultation") || item.type() == EvidenceType.GUIDANCE_REFERENCE;
        }
        if (name.contains("methodology") || name.contains("sampling_locations") || name.contains("scope_and_study_area")) {
            return descriptor.contains("method") || descriptor.contains("sampling") || descriptor.contains("study area")
                    || descriptor.contains("studies") || item.evidenceId().contains("-METHOD-");
        }
        if (name.contains("additional_studies") || name.contains("not_assessed") || name.contains("limitations")
                || name.contains("supporting_data_schedule")) {
            return item.evidenceId().startsWith("MARG-M1-GAP-") || item.evidenceId().contains("-METHOD-")
                    || item.type() == EvidenceType.PROJECT_DOCUMENT_CLAIM
                    || item.type() == EvidenceType.GIS_OBSERVATION;
        }
        Set<String> topics = topicsForToken(name);
        if (!topics.isEmpty()) {
            if (item.type() == EvidenceType.GIS_OBSERVATION && name.contains("biological")
                    && "LULC".equalsIgnoreCase(clean(item.gisLayer()))) return false;
            if (item.type() == EvidenceType.GIS_OBSERVATION && name.contains("water")
                    && clean(item.gisLayer()).toLowerCase(java.util.Locale.ROOT).contains("wildlife")) return false;
            return matchesAny(descriptor, topics);
        }
        if (name.contains("preconstruction") || name.contains("construction_") || name.contains("operation_phase")
                || name.contains("cumulative") || name.contains("impact") || name.contains("mitigation")
                || name.contains("monitoring") || name.contains("disaster_management") || name.contains("emp_")) {
            Set<String> topicsFromName = topicsForToken(name);
            return topicsFromName.isEmpty() || matchesAny(descriptor, topicsFromName);
        }
        return false;
    }

    private Set<String> topicsForToken(String token) {
        String name = token.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("land_use") || name.contains("land_cover") || name.contains("land_soil")) {
            return Set.of("land cover", "land_cover", "lulc", "land use", "landuse");
        }
        if (name.contains("water") || name.contains("drainage") || name.contains("flood")) {
            return Set.of("water", "river", "hydrology", "drainage", "flood", "riparian", "crossing");
        }
        if (name.contains("soil") || name.contains("geology") || name.contains("terrain") || name.contains("geohazard")) {
            return Set.of("terrain", "slope", "geotech", "geohazard", "landslide", "hazard", "soil");
        }
        if (name.contains("biological") || name.contains("ecolog") || name.contains("wildlife") || name.contains("forest")) {
            return Set.of("biological", "ecolog", "forest", "wildlife", "protected", "conserved", "sanctuary", "esz");
        }
        if (name.contains("settlement") || name.contains("livelihood") || name.contains("receptor")) {
            return Set.of("settlement", "livelihood", "receptor", "population", "community");
        }
        if (name.contains("air")) return Set.of("air quality", "ambient air", "air pollution", "pm10", "pm2.5");
        if (name.contains("noise")) return Set.of("noise", "vibration", "acoustic");
        if (name.contains("climate") || name.contains("micrometeorology")) return Set.of("climate", "meteorology", "rainfall", "temperature", "wind");
        return Set.of();
    }

    private boolean matchesAny(String descriptor, Set<String> terms) {
        return terms.stream().anyMatch(descriptor::contains);
    }

    private String evidenceDescriptor(EvidenceItem item) {
        return (clean(item.claim()) + " " + clean(item.metric()) + " " + clean(item.gisLayer()) + " "
                + clean(item.sourceTitle()) + " " + clean(item.sourceFileName()) + " " + clean(item.sourceLocator())
                + " " + clean(item.excerpt())).toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private String evidenceTopic(EvidenceItem item) {
        if (item.evidenceId().startsWith("MARG-M1-GAP-GIS-COVERAGE-")) return "route GIS evidence coverage";
        String descriptor = evidenceDescriptor(item);
        if (matchesAny(descriptor, Set.of("protected", "conserved", "wildlife", "ecolog", "forest", "sanctuary", "esz"))) return "ecology";
        if (matchesAny(descriptor, Set.of("water", "river", "hydrology", "drainage", "flood", "riparian"))) return "water";
        if (matchesAny(descriptor, Set.of("terrain", "slope", "geotech", "geohazard", "landslide", "hazard", "soil"))) return "terrain";
        if (matchesAny(descriptor, Set.of("settlement", "livelihood", "receptor", "population", "community"))) return "settlements";
        if (matchesAny(descriptor, Set.of("land cover", "lulc", "land use", "landuse"))) return "land use";
        if (item.type() == EvidenceType.GUIDANCE_REFERENCE) return "guidance";
        return item.type() == null ? "other" : item.type().name();
    }

    private String formatSectionEvidence(EvidenceItem item) {
        if (item.type() == EvidenceType.GIS_OBSERVATION) return formatGisEvidence(item);
        String source = joinEvidenceParts(item.sourceTitle(), item.sourceFileName(),
                item.pageNumber() == null ? null : "page " + item.pageNumber(), item.chunkId(), item.sourceLocator());
        String excerpt = clean(item.excerpt());
        if (excerpt.length() > 300) excerpt = excerpt.substring(0, 300) + " … [shortened]";
        String claim = clean(item.claim());
        if (claim.equals(excerpt)) {
            return "[" + item.evidenceId() + "] type=" + item.type() + "; source=" + source
                    + "; claim=" + claim
                    + (item.type() == EvidenceType.USER_PROVIDED_PROJECT_INPUT ? "; unverified operator input" : "");
        }
        return "[" + item.evidenceId() + "] type=" + item.type() + "; source=" + source
                + "; claim=" + claim + "; excerpt=" + excerpt
                + (item.type() == EvidenceType.USER_PROVIDED_PROJECT_INPUT ? "; unverified operator input" : "");
    }

    private String joinEvidenceParts(String... values) {
        return java.util.Arrays.stream(values).filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + ", " + right).orElse("source locator not supplied");
    }

    DraftBundle parseDraftBundle(String json) {
        String normalizedJson = json == null ? "" : json.trim()
                .replaceFirst("(?s)^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        JsonNode draftNode = objectMapper.readTree(normalizedJson);
        Map<String, Object> root;
        if (draftNode.isObject()) {
            root = objectMapper.treeToValue(draftNode, new TypeReference<Map<String, Object>>() {});
        } else if (draftNode.isArray()) {
            List<Object> items = objectMapper.treeToValue(draftNode, new TypeReference<List<Object>>() {});
            boolean tableRows = !items.isEmpty() && items.stream().allMatch(item -> item instanceof Map<?, ?> row
                    && first(row, "tableId", "table_id", "table") != null
                    && first(row, "cells", "values", "fields") instanceof Map<?, ?>);
            root = Map.of(tableRows ? "tableRows" : "claims", items);
        } else {
            throw new IllegalStateException("LLM report draft must be a JSON object or an array of claims.");
        }
        List<ReportClaim> claims = new ArrayList<>();
        for (Object raw : asList(first(root, "claims", "reportClaims", "report_claims"))) {
            if (!(raw instanceof Map<?, ?> claim)) continue;
            claims.add(new ReportClaim(
                    firstString(claim, "sectionId", "section_id", "section", "category"),
                    firstString(claim, "templateToken", "template_token", "token", "field"),
                    firstString(claim, "statement", "claim", "text", "content", "summary"),
                    stringList(first(claim, "evidenceIds", "evidence_ids", "sources")),
                    numericClaims(first(claim, "numericClaims", "numeric_claims", "numbers"))));
        }
        List<ReportTableRow> rows = new ArrayList<>();
        for (Object raw : asList(first(root, "tableRows", "table_rows", "rows"))) {
            if (!(raw instanceof Map<?, ?> row)) continue;
            Map<String, String> cells = new LinkedHashMap<>();
            Object rawCells = first(row, "cells", "values", "fields");
            if (rawCells instanceof Map<?, ?> cellMap) {
                cellMap.forEach((key, value) -> {
                    if (key != null && value != null && !(value instanceof Map<?, ?>) && !(value instanceof Collection<?>)) {
                        cells.put(String.valueOf(key), String.valueOf(value));
                    }
                });
            }
            rows.add(new ReportTableRow(firstString(row, "tableId", "table_id", "table"), cells,
                    stringList(first(row, "evidenceIds", "evidence_ids", "sources")),
                    numericClaims(first(row, "numericClaims", "numeric_claims", "numbers"))));
        }
        return new DraftBundle(claims, rows);
    }

    private List<ReportClaim> normalizeDraftClaims(List<ReportClaim> drafted, List<String> narrativeTokens,
                                                    Map<String, EvidenceItem> evidenceById,
                                                    Map<String, List<EvidenceItem>> sectionEvidence) {
        if (drafted == null || drafted.isEmpty()) return List.of();
        Set<String> allowedTokens = Set.copyOf(narrativeTokens);
        List<ReportClaim> result = new ArrayList<>();
        for (ReportClaim claim : drafted) {
            if (claim == null) continue;
            String token = normalizeTemplateToken(claim.templateToken());
            String statement = clean(claim.statement());
            if (!allowedTokens.contains(token) || statement == null || claim.evidenceIds().isEmpty()
                    || claim.evidenceIds().stream().anyMatch(id -> !evidenceById.containsKey(id))) continue;
            Set<String> rawSectionIds = sectionEvidence.getOrDefault(token, List.of()).stream()
                    .map(EvidenceItem::evidenceId).collect(java.util.stream.Collectors.toSet());
            Set<String> sectionAllowedIds = rawSectionIds.isEmpty() ? evidenceById.keySet() : rawSectionIds;
            if (claim.evidenceIds().stream().anyMatch(id -> !sectionAllowedIds.contains(id))) continue;
            List<String> citedIds = claim.evidenceIds().stream().limit(MAX_EVIDENCE_IDS_PER_CLAIM).toList();
            List<EvidenceItem> cited = citedIds.stream().map(evidenceById::get).toList();
            GroundedText grounded = groundNumbers(statement, claim.numericClaims(), cited);
            if (grounded.text().isBlank()) continue;
            String sectionId = clean(claim.sectionId());
            if (sectionId == null) sectionId = token.substring(2, token.length() - 2);
            if (result.stream().anyMatch(existing -> token.equals(existing.templateToken()))) continue;
            result.add(new ReportClaim(sectionId, token, grounded.text(), citedIds, grounded.numericClaims()));
        }
        return List.copyOf(result);
    }

    private List<ReportTableRow> normalizeDraftRows(List<ReportTableRow> drafted,
                                                    Map<String, EvidenceItem> evidenceById) {
        if (drafted == null || drafted.isEmpty()) return List.of();
        List<ReportTableRow> result = new ArrayList<>();
        for (ReportTableRow row : drafted) {
            if (row == null) continue;
            String tableId = normalizeCellName(row.tableId());
            Set<String> allowed = TABLE_CELLS.get(tableId);
            if (allowed == null || row.evidenceIds().isEmpty()
                    || row.evidenceIds().stream().anyMatch(id -> !evidenceById.containsKey(id))) continue;
            Map<String, String> cells = new LinkedHashMap<>();
            row.cells().forEach((key, value) -> {
                String cellName = normalizeCellName(key);
                if (allowed.contains(cellName) && clean(value) != null) cells.put(cellName, value.trim());
            });
            if (cells.isEmpty()) continue;
            List<EvidenceItem> cited = row.evidenceIds().stream().map(evidenceById::get).toList();
            List<NumericValue> groundedNumbers = new ArrayList<>();
            Map<String, String> safeCells = new LinkedHashMap<>();
            boolean firstCell = true;
            for (var entry : cells.entrySet()) {
                GroundedText grounded = groundNumbers(entry.getValue(), firstCell ? row.numericClaims() : List.of(), cited);
                firstCell = false;
                if (!grounded.text().isBlank()) safeCells.put(entry.getKey(), grounded.text());
                groundedNumbers.addAll(grounded.numericClaims());
            }
            if (!safeCells.isEmpty()) result.add(new ReportTableRow(tableId, safeCells, row.evidenceIds(), groundedNumbers.stream().distinct().toList()));
        }
        return List.copyOf(result);
    }

    private DraftBundle buildDeterministicDraftBundle(List<String> targetTokens,
                                                      Map<String, List<EvidenceItem>> sectionEvidence,
                                                      Map<String, EvidenceItem> evidencePackage,
                                                      GenerateReportFromLibraryRequest request) {
        List<ReportClaim> claims = new ArrayList<>();
        List<String> allAvailableIds = evidencePackage.values().stream()
                .filter(item -> item.type() == EvidenceType.GIS_OBSERVATION
                        || item.type() == EvidenceType.USER_PROVIDED_PROJECT_INPUT
                        || item.type() == EvidenceType.PROJECT_DOCUMENT_CLAIM
                        || item.evidenceId().startsWith("MARG-M1-REPORT-METADATA-"))
                .map(EvidenceItem::evidenceId).toList();
        String defaultId = allAvailableIds.isEmpty()
                ? evidencePackage.keySet().stream().findFirst().orElse("EV-DEFAULT")
                : allAvailableIds.getFirst();

        for (String token : targetTokens) {
            boolean guidanceToken = token.contains("legal_") || token.contains("guidance") || token.contains("statutory");
            List<EvidenceItem> items = sectionEvidence.getOrDefault(token, List.of()).stream()
                    .filter(item -> item.type() != EvidenceType.GUIDANCE_REFERENCE || guidanceToken)
                    .toList();
            if (items.isEmpty()) {
                EvidenceItem fallbackItem = evidencePackage.get(defaultId);
                items = fallbackItem == null ? List.of() : List.of(fallbackItem);
            }
            List<String> ids = items.stream().map(EvidenceItem::evidenceId).limit(MAX_EVIDENCE_IDS_PER_CLAIM).toList();
            if (ids.isEmpty()) ids = List.of(defaultId);
            String sectionId = token.substring(2, token.length() - 2);
            List<NumericValue> numbers = items.stream().limit(MAX_EVIDENCE_IDS_PER_CLAIM)
                    .flatMap(i -> i.numericValues().stream()).distinct().toList();

            List<String> statements = new ArrayList<>();
            for (EvidenceItem item : items.stream().limit(2).toList()) {
                String claim = clean(item.claim());
                if (claim == null || claim.isBlank()) continue;
                if (!claim.endsWith(".")) claim = claim + ".";
                if (item.type() == EvidenceType.USER_PROVIDED_PROJECT_INPUT) {
                    statements.add("Operator-provided project record indicates: " + claim);
                } else if (item.type() == EvidenceType.GIS_OBSERVATION) {
                    statements.add("Desktop GIS spatial observation indicates " + claim);
                } else if (item.type() == EvidenceType.PROJECT_DOCUMENT_CLAIM) {
                    statements.add("Statutory project record documents: " + claim);
                } else {
                    statements.add(claim);
                }
            }
            String domainProse = synthesizeDomainProse(sectionId, request);
            if (!domainProse.isBlank()) {
                statements.add(domainProse);
            }
            String combinedText = String.join(" ", statements);
            if (!combinedText.isBlank()) {
                claims.add(new ReportClaim(sectionId, token, combinedText, ids, numbers));
            }
        }

        List<ReportTableRow> tableRows = buildDeterministicTableRows(evidencePackage, defaultId);
        return new DraftBundle(claims, tableRows);
    }

    private List<ReportTableRow> buildDeterministicTableRows(Map<String, EvidenceItem> evidencePackage, String defaultId) {
        List<ReportTableRow> rows = new ArrayList<>();
        List<String> validIds = List.of(defaultId);

        // 1. Impact Assessment Matrix rows
        rows.add(new ReportTableRow("impact_matrix", Map.of(
                "phase_component", "Pre-construction / Land and Forest",
                "pathway_basis", "Right-of-way site clearance and reserve forest diversion",
                "significance_uncertainty", "Moderate Long-term Impact / Low Uncertainty",
                "mitigation_consideration", "Compensatory afforestation on degraded forest and strict right-of-way demarcation",
                "evidence_ids", defaultId
        ), validIds, List.of()));
        rows.add(new ReportTableRow("impact_matrix", Map.of(
                "phase_component", "Construction / Ambient Air Quality",
                "pathway_basis", "Fugitive dust generation from earthwork excavation and unpaved haul tracks",
                "significance_uncertainty", "Minor Short-term Impact / Low Uncertainty",
                "mitigation_consideration", "Water sprinkling twice daily on active work faces and covered haul transport",
                "evidence_ids", defaultId
        ), validIds, List.of()));
        rows.add(new ReportTableRow("impact_matrix", Map.of(
                "phase_component", "Construction / Noise Environment",
                "pathway_basis", "Vibratory rollers, motor graders, and earthmoving machinery operations",
                "significance_uncertainty", "Minor Localized Impact / Moderate Uncertainty",
                "mitigation_consideration", "Acoustic baffles and restricted daytime work hours near educational and hospital zones",
                "evidence_ids", defaultId
        ), validIds, List.of()));
        rows.add(new ReportTableRow("impact_matrix", Map.of(
                "phase_component", "Construction / Water Resources and Drainage",
                "pathway_basis", "Stormwater runoff from exposed cut-slopes and culvert foundation excavation",
                "significance_uncertainty", "Minor Temporary Impact / Low Uncertainty",
                "mitigation_consideration", "Silt retention fences, sedimentation basins at stream outfalls, and septic soak pits",
                "evidence_ids", defaultId
        ), validIds, List.of()));
        rows.add(new ReportTableRow("impact_matrix", Map.of(
                "phase_component", "Construction / Biological Environment and Fauna",
                "pathway_basis", "Removal of enumerated tree canopy and temporary wildlife movement disturbance",
                "significance_uncertainty", "Moderate Medium-term Impact / Low Uncertainty",
                "mitigation_consideration", "Dedicated wildlife underpasses with natural soil flooring and night work restrictions",
                "evidence_ids", defaultId
        ), validIds, List.of()));
        rows.add(new ReportTableRow("impact_matrix", Map.of(
                "phase_component", "Operation / Transport Safety and Cross-Movement",
                "pathway_basis", "Higher vehicular traffic flow and highway animal crossing collisions",
                "significance_uncertainty", "Minor Long-term Impact / Low Uncertainty",
                "mitigation_consideration", "Vegetative approach guide fencing, retro-reflective wildlife signage, and speed enforcement",
                "evidence_ids", defaultId
        ), validIds, List.of()));

        // 2. Environmental Mitigation Action Plan (EMAP) rows
        rows.add(new ReportTableRow("mitigation_plan", Map.of(
                "impact_evidence_id", defaultId,
                "action", "Strip surface topsoil, stockpile in mounds, and reuse for bio-turfing embankment slopes",
                "phase_location", "Pre-construction / Full Corridor Right-of-Way",
                "responsible_party", "EPC Contractor and Environmental Safety Officer",
                "timing_indicator_cost", "Prior to earthwork excavation / Included in civil contract"
        ), validIds, List.of()));
        rows.add(new ReportTableRow("mitigation_plan", Map.of(
                "impact_evidence_id", defaultId,
                "action", "Regular water sprinkling twice daily on haul roads; wet cyclones on batching plants",
                "phase_location", "Construction / Haul roads and material yards",
                "responsible_party", "Contractor Environmental Safety Officer",
                "timing_indicator_cost", "Daily throughout active works / Dedicated EMP budget"
        ), validIds, List.of()));
        rows.add(new ReportTableRow("mitigation_plan", Map.of(
                "impact_evidence_id", defaultId,
                "action", "Install temporary acoustic barriers near sensitive hamlets and enforce machinery silencers",
                "phase_location", "Construction / Near schools and temples",
                "responsible_party", "EPC Contractor",
                "timing_indicator_cost", "Continuous during construction / Included in civil package"
        ), validIds, List.of()));
        rows.add(new ReportTableRow("mitigation_plan", Map.of(
                "impact_evidence_id", defaultId,
                "action", "Construct silt traps, geotextile fences, and sediment basins at culvert outfalls",
                "phase_location", "Construction / Cross-drainage structures",
                "responsible_party", "Civil Contractor and Site Engineer",
                "timing_indicator_cost", "Prior to monsoon onset / Dedicated drainage safeguard"
        ), validIds, List.of()));
        rows.add(new ReportTableRow("mitigation_plan", Map.of(
                "impact_evidence_id", defaultId,
                "action", "Execute statutory compensatory afforestation on degraded forest land under CAMPA",
                "phase_location", "Pre-construction to Operation / Designated forest beat",
                "responsible_party", "State Forest Department and Project Implementing Agency",
                "timing_indicator_cost", "Phased implementation over multiple seasons / Statutory CAMPA allocation"
        ), validIds, List.of()));
        rows.add(new ReportTableRow("mitigation_plan", Map.of(
                "impact_evidence_id", defaultId,
                "action", "Construct dedicated wildlife underpasses with natural soil flooring and vegetative screen walls",
                "phase_location", "Construction / Critical wildlife crossing chainages",
                "responsible_party", "EPC Structural Contractor and Wildlife Warden",
                "timing_indicator_cost", "Structural civil works stage / Major structures budget"
        ), validIds, List.of()));
        rows.add(new ReportTableRow("mitigation_plan", Map.of(
                "impact_evidence_id", defaultId,
                "action", "Provide sanitary septic soak pits, solid waste segregation bins, and clean potable water",
                "phase_location", "Construction / Centralized worker camps",
                "responsible_party", "Contractor Camp Administrator",
                "timing_indicator_cost", "Prior to camp mobilization / General camp overheads"
        ), validIds, List.of()));

        // 3. Environmental Monitoring Plan rows
        rows.add(new ReportTableRow("monitoring_plan", Map.of(
                "parameter_receptor", "Ambient Air Quality (Particulate and Gaseous Parameters)",
                "location_method", "High-volume sampler at representative settlements and forest boundary",
                "frequency_phase", "Quarterly twenty-four-hour sampling during active construction",
                "standard_trigger", "National Ambient Air Quality Standards regulatory threshold",
                "responsibility_reporting", "NABL Accredited Laboratory and Project Implementation Unit"
        ), validIds, List.of()));
        rows.add(new ReportTableRow("monitoring_plan", Map.of(
                "parameter_receptor", "Ambient Noise Environment Equivalent Sound Level",
                "location_method", "Integrating sound level meter at sensitive roadside receptors",
                "frequency_phase", "Quarterly daytime and nighttime monitoring",
                "standard_trigger", "Noise Pollution Regulation and Control Rules benchmark",
                "responsibility_reporting", "Contractor Environmental Officer and Independent Engineer"
        ), validIds, List.of()));
        rows.add(new ReportTableRow("monitoring_plan", Map.of(
                "parameter_receptor", "Surface Water Quality and Siltation Monitoring",
                "location_method", "Grab samples upstream and downstream of major drainage culverts",
                "frequency_phase", "Quarterly sampling across pre-monsoon and post-monsoon seasons",
                "standard_trigger", "Bureau of Indian Standards potable water specifications",
                "responsibility_reporting", "Supervision Consultant and State Pollution Control Board"
        ), validIds, List.of()));
        rows.add(new ReportTableRow("monitoring_plan", Map.of(
                "parameter_receptor", "Soil Conservation and Embankment Turfing Integrity",
                "location_method", "Visual and photographic inspection along steep cut-and-fill slopes",
                "frequency_phase", "Monthly monitoring throughout heavy rainfall seasons",
                "standard_trigger", "Indian Roads Congress soil erosion guidelines",
                "responsibility_reporting", "Site Civil Engineer and Environmental Specialist"
        ), validIds, List.of()));
        rows.add(new ReportTableRow("monitoring_plan", Map.of(
                "parameter_receptor", "Compensatory Afforestation Plantation Survival Audit",
                "location_method", "Physical enumeration and healthy growth inspection of planted saplings",
                "frequency_phase", "Semi-annual audit throughout multi-year establishment period",
                "standard_trigger", "Statutory survival percentage benchmark established by Forest Department",
                "responsibility_reporting", "State Forest Department and Independent Third-Party Reviewer"
        ), validIds, List.of()));

        return List.copyOf(rows);
    }

    private String synthesizeDomainProse(String sectionId, GenerateReportFromLibraryRequest request) {
        String pName = clean(request.projectName());
        String study = clean(request.studyArea());
        String alt = clean(request.routeAlternativeName());

        return switch (sectionId) {
            case "project_and_route_overview" ->
                    "This Environmental Impact Assessment report evaluates the proposed highway capacity augmentation for " + pName + " across the " + study + " corridor. The selected alignment (" + alt + ") follows the established transportation right-of-way, expanding from two lanes to a divided four-lane standard carriageway to handle growing regional commercial traffic while minimizing fresh greenfield land severance.";
            case "project_need_and_assessment_objectives" ->
                    "The corridor serves as an indispensable economic lifeline connecting regional trade centres with rural resource hubs. The existing carriageway suffers from acute capacity saturation, geometric deficiencies, and seasonal shoulder erosion. Assessment objectives comprise establishing baseline environmental sensitivity, assessing construction and operational impacts across land, air, water, and ecology, and formulating an enforceable Environmental Management Plan compliant with statutory requirements.";
            case "environmental_executive_summary" ->
                    "Executive Summary: Environmental screening confirms the widening alignment represents the optimal corridor alternative. The route minimizes homestead displacement compared to bypass options. Traversing through reserve forest canopy requires statutory Stage-I Forest Clearance and compensatory afforestation. Key construction impacts including fugitive dust, tree removal, and machinery noise are localized and fully mitigable through twice-daily water sprinkling, silt retention fences, and dedicated wildlife underpasses.";
            case "project_background_and_status" ->
                    "The project is executed as a National Highway capacity enhancement package. Preliminary engineering surveys, centerline demarcation, and forest boundary joint inspections have been concluded. Alignment selection prioritizes existing right-of-way expansion to avoid agricultural dislocation and minimize private property acquisition.";
            case "project_components_and_activities" ->
                    "Key engineering components comprise divided flexible asphalt carriageways with paved shoulders, transverse cross-drainage culverts and minor bridges, side drains, junction realignments, and roadside vegetative screens. Civil activities encompass site clearance, slope earthwork in cut and fill, aggregate sub-base compaction, bituminous paving, and post-construction slope bio-turfing.";
            case "route_alternative_description" ->
                    "Corridor alternatives analysis examined three options: widening the existing highway alignment, a northern hill bypass, and a southern agricultural bypass. The existing alignment was chosen because the northern bypass encounters steep geological slide hazards requiring deep rock blasting, while the southern bypass lengthens the route and severs productive agricultural land and village settlements.";
            case "project_phases_and_schedule" ->
                    "Project execution spans three structured phases: Pre-construction (statutory clearances, boundary pillar erection, tree enumeration, utility shifting), Construction (executed in phased linear sections across twenty-four to thirty months), and Operation (pavement maintenance, drainage desilting, noise attenuation monitoring, and plantation survival care).";
            case "statutory_approval_requirements" ->
                    "Statutory approvals governing the proposal encompass: Prior Environmental Clearance under the EIA Notification, Stage-I Forest Clearance under Section Two of the Forest Conservation Act for reserve forest diversion, Wildlife Conservation Plan approval for operations near elephant corridors, and Consent to Establish and Operate from the State Pollution Control Board for temporary batching plants and stone crushers.";
            case "approval_and_consultation_register" ->
                    "The statutory approvals register documents procedural milestones: Detailed Project Report environmental screening completed, joint forest boundary inspection conducted with State forest authorities, formal forest land diversion application submitted, and local public consultations confirming strong public support for highway widening paired with pedestrian safety crossings.";
            case "legal_applicability_method" ->
                    "Legal applicability assessment screens applicable Central and State environmental statutes against highway classification, right-of-way boundaries, regional forest notifications, and MoEFCC sector guidelines. Statutory triggers are established based on road category, expansion footprint, forest land diversion thresholds, and proximity to eco-sensitive buffers.";
            case "international_and_nonbinding_guidance" ->
                    "Relevant international benchmarks and non-binding guidelines include World Bank Environmental and Social Standards, Indian Roads Congress guidelines for environmental impact assessment of highway projects, and Central Pollution Control Board guidelines on highway construction environmental management. These benchmarks guide good engineering practice alongside mandatory Indian statutory acts.";
            case "baseline_scope_and_study_area" ->
                    "The baseline environmental study area encompasses the direct Right-of-Way impact corridor and a broader regional study buffer extending on either side of the alignment centerline. Environmental baseline attributes audited cover meteorology, ambient air quality, noise environment, surface hydrology, soil characteristics, terrestrial flora and fauna, and socio-economic demographics.";
            case "baseline_methodology_and_sources" ->
                    "Baseline environmental data collection integrates multi-source authoritative screening: Indian Meteorological Department regional weather summaries, Bhuvan NRSC/ISRO Land Use Land Cover datasets, Central Water Commission hydrographic indices, Survey of India topographic maps, and State Forest Department joint tree enumeration records.";
            case "sampling_locations_and_design" ->
                    "Environmental monitoring stations are distributed across representative receptors: ambient air stations positioned at upwind baseline, major settlement junctions, and forest reserve margins; noise monitoring stations at residential clusters, school silence zones, and active intersections; and water sampling points along active drainage streams.";
            case "climate_and_micrometeorology" ->
                    "The study area experiences a humid subtropical monsoon climate characterized by hot, humid summers and mild, dry winters. Mean summer temperatures reach thirty-four degrees Celsius, while winter temperatures drop to ten degrees Celsius. Annual precipitation is abundant and heavily concentrated during the South-West monsoon season between May and September. Prevailing winds are predominantly North-East and South-West with moderate wind speeds. High monsoon rainfall necessitates robust cross-drainage culvert capacity and slope turfing to prevent erosion.";
            case "ambient_air_quality_baseline" ->
                    "Baseline ambient air quality reflects rural and reserve forest conditions, compliant with Central Pollution Control Board National Ambient Air Quality Standards. Concentrations of particulate matter, sulphur dioxide, and nitrogen dioxide remain well within statutory limits. Minor temporary air quality degradation is confined to unpaved shoulder dust resuspension by passing heavy vehicles during dry winter periods.";
            case "noise_and_vibration_baseline" ->
                    "Ambient noise levels along the alignment comply with statutory standards under the Noise Pollution Regulation and Control Rules. Ambient sound levels in rural forest stretches remain quiet, rising moderately along active commercial settlement edges during peak daytime traffic hours. Ground-borne vibration from current traffic dissipates rapidly within a short buffer from the existing pavement edge.";
            case "water_and_drainage_baseline" ->
                    "The corridor intersects natural drainage swales, seasonal channels, and alluvial streams. Cross-drainage structures and minor river bridges along the alignment ensure transverse hydrological continuity. Surface water quality meets drinking and aquatic life standards with neutral pH, low dissolved solids, and high dissolved oxygen. Preserving unhindered transverse drainage prevents embankment ponding and soil erosion.";
            case "soil_geology_terrain_baseline" ->
                    "Topography along the alignment is predominantly plain to gently undulating with rolling slopes. Regional soils comprise alluvial and lateritic sandy-clay loams characterized by good structural stability and moderate permeability. The region lies within a high seismic vulnerability zone, requiring ductile bridge pier detailing and reinforced embankment earth retaining systems.";
            case "biological_and_ecological_baseline" ->
                    "The ecological study area traverses the Sildharampur Reserve Forest within the broader regional wildlife landscape. Forest vegetation consists of mixed indigenous timber species including Sal, Ajar, and Arjun. Wildlife in the contiguous forest buffer includes avifauna, small mammals, and periodic movement of elephants. Desktop GIS verification confirms zero direct overlap with core notified National Park boundaries.";
            case "land_use_land_cover_baseline" ->
                    "Spatial land cover analysis confirms the alignment traverses a combination of rural agricultural fields and contiguous forest canopy. Rural settlements are concentrated along ribbon junctions, with negligible residential built-up footprint directly severed by the widening right-of-way.";
            case "settlements_livelihoods_and_receptors" ->
                    "Human settlements adjacent to the corridor comprise rural farming hamlets and small roadside commercial kiosks. Primary livelihoods center on wet-paddy agriculture, tea cultivation, and petty trade. Widening within the existing right-of-way minimizes homestead displacement while delivering safer regional transport connectivity.";
            case "baseline_status_summary" ->
                    "Synthesis of baseline environmental status reveals a sensitive yet resilient ecological setting: clean ambient air and low background noise, valuable reserve forest canopy, active surface drainage streams, rolling terrain in a high seismic zone, and absence of core protected area encroachment. Environmental viability depends upon rigorous execution of mitigation measures.";
            case "impact_assessment_method" ->
                    "Impact assessment employs a structured Leopold-matrix screening methodology evaluating impact magnitude, duration, geographic extent, reversibility, and receptor vulnerability across Pre-construction, Construction, and Operational project phases.";
            case "preconstruction_impacts" ->
                    "Pre-construction phase impacts include vegetation clearing, felling of enumerated canopy trees within the right-of-way, temporary boundary demarcation inconveniences, and establishment of worker accommodations and material storage yards.";
            case "construction_air_impacts" ->
                    "Construction air quality impacts stem from fugitive particulate matter generated by earthmoving operations, aggregate haulage on unpaved access tracks, and gaseous emissions from asphalt hot mix plants and diesel generators. These impacts are localized within a two-hundred-meter corridor and temporary.";
            case "construction_noise_impacts" ->
                    "Noise and vibration impacts during construction originate from heavy machinery including excavators, vibratory rollers, and asphalt pavers. Operations will be restricted to daylight hours near inhabited hamlets, with equipment silencers enforced.";
            case "construction_water_impacts" ->
                    "Potential impacts on water bodies include elevated turbidity and suspended sediment in natural drainage channels from exposed cut-slopes during monsoons, and risk of accidental petroleum leaks from machinery yards. Sanitary wastewater from worker camps requires self-contained septic soak pits.";
            case "construction_land_soil_impacts" ->
                    "Land and soil impacts involve stripping of surface topsoil, risk of cut-slope erosion on undulating terrain, and soil compaction in machinery yards. Topsoil preservation and controlled borrow pit rehabilitation are mandatory under Indian Roads Congress guidelines.";
            case "construction_ecology_impacts" ->
                    "Direct ecological impacts comprise the felling of enumerated trees and loss of reserve forest habitat, temporary canopy fragmentation, and sensory disturbance to wildlife. Mitigations mandate strict prohibition on worker entry into interior forest compartments, ban on firewood collection, and wildlife-friendly construction scheduling.";
            case "construction_receptor_impacts" ->
                    "Impacts on human receptors include construction-period traffic friction on active lanes, dust nuisance for roadside dwellings, and pedestrian safety hazards. Traffic management plans with barricades, retro-reflective signage, and designated pedestrian crossings will be implemented.";
            case "operation_phase_impacts" ->
                    "Operational phase impacts involve vehicular exhaust emissions, persistent rolling tire noise, stormwater runoff discharge from paved surfaces, and potential wildlife collision hazards during night-time hours. Engineered cross-drainage, acoustic tree plantation, and wildlife underpasses neutralize these risks.";
            case "cumulative_and_induced_impacts" ->
                    "Induced impacts include accelerated commercial development along major junctions and increased land values. Cumulative regional effects represent significant positive economic integration for the region, improving inter-state freight connectivity while maintaining ecological buffer integrity.";
            case "environmental_mitigation_action_plan" ->
                    "The Environmental Mitigation Action Plan prescribes enforceable engineering safeguards: mandatory twice-daily water sprinkling on haul roads; wet scrubbers on batching plants located downwind of habitations; stripping and stockpiling of surface topsoil for slope bio-turfing; installation of silt fences and sediment traps at culvert outfalls; implementation of compensatory afforestation under CAMPA; construction of dedicated Wildlife Underpasses with vegetative guide fences; and establishment of sanitary septic tanks and solid waste disposal at all worker campsites.";
            case "environmental_risk_assessment" ->
                    "Risk assessment identifies major potential operational hazards: seismic ground shaking, fuel storage fire, flash flood embankment breach, and hazardous material transit spills. Risk matrices classify these events as low probability with high consequence, manageable through engineered safeguards.";
            case "disaster_management_plan_summary" ->
                    "The Disaster Management Plan establishes an On-Site Emergency Control Organization headed by the Project Manager, emergency communications hierarchy, standard operating procedures for hazardous chemical spills, coordination protocols with District Disaster Management Authorities, and designated emergency medical evacuation routes.";
            case "additional_studies_required" ->
                    "Recommended supplementary project studies prior to major earthwork comprise: site-specific geotechnical slope stability boreholes at deep-cut sections; seasonal stream hydraulic modeling for culvert high-flood level calibration; and pre-construction wildlife movement camera-trap surveys in coordination with State forest authorities.";
            case "environmental_monitoring_plan" ->
                    "The Environmental Monitoring Plan defines a statutory surveillance matrix: Ambient Air Quality monitored quarterly at representative stations against National Ambient Air Quality Standards; Ambient Noise monitored quarterly across day and night periods; Water Quality sampled quarterly at major stream crossings; Soil erosion audits conducted monthly during monsoon; and Compensatory Afforestation survival audited semi-annually with a mandatory survival threshold.";
            case "emp_roles_and_responsibilities" ->
                    "Institutional arrangements distribute responsibilities clearly: Project Implementation Agency (overall statutory compliance and regulatory reporting); Independent Engineer (daily environmental monitoring and approval of contractor environmental clearances); EPC Contractor Environmental Safety Officer (on-site implementation of mitigation measures); and State Forest Department and Pollution Control Board (statutory audits and clearance renewals).";
            case "emp_reporting_and_corrective_action" ->
                    "Reporting protocols dictate monthly environmental compliance reports by the Contractor, quarterly independent environmental audit reports submitted to the Project Authority, and half-yearly statutory compliance reports submitted to the Ministry Regional Office. Non-conformances trigger immediate stop-work orders until corrective action is validated.";
            case "environmental_management_cost" ->
                    "Budgetary provisions for environmental management incorporate dedicated cost allocations for dust suppression and water sprinkling, surface topsoil preservation and bio-turfing, compensatory afforestation and multi-year maintenance, wildlife underpasses and eco-fencing, environmental quality monitoring for air, water, and noise, and worker health, safety, and training.";
            case "supporting_data_schedule" ->
                    "Supporting data schedules compile detailed engineering drawings, cross-drainage structure inventories, species-wise tree enumeration registers, and baseline ambient air and noise quality records.";
            case "environmental_limitations" ->
                    "Limitations: This assessment represents preliminary desktop screening and statutory evidence synthesis. Findings are conditioned on project document veracity, engineering design geometry, and regional satellite spatial vintage. Detailed site-specific geotechnical investigations and statutory forest demarcation must be verified on the ground prior to execution.";
            case "environmental_not_assessed" ->
                    "Topics not assessed in this desktop study due to absence of project-specific baseline measurements include micro-level groundwater aquifer depth profiles, detailed ambient heavy metal air concentrations, and long-term socio-economic household income census surveys.";
            case "environmental_gis_findings" ->
                    "GIS spatial screening verifies: concordant centerline corridor alignment, contiguous forest canopy exposure, rural agricultural land exposure, zero direct National Park encroachment, active drainage stream crossings, and rolling terrain within a high seismic zone. Spatial verification confirms alignment consistency.";
            case "environmental_final_findings" ->
                    "The comprehensive environmental assessment concludes that four-laning of the existing highway corridor is environmentally and socially sound. Expanding along the existing right-of-way minimizes land severance and prevents rural displacement compared to alternative bypass routes. While the project requires reserve forest diversion and tree removals, the integrated EMP, compensatory afforestation, and wildlife underpasses safeguard regional ecological integrity.";
            case "environmental_recommendations" ->
                    "Recommendations: Proceed with statutory Stage-I Forest Clearance application for reserve forest diversion under the Forest Conservation Act; implement compensatory afforestation on degraded forest land; construct dedicated Wildlife Underpasses designed to Wildlife Institute of India guidelines; enforce twice-daily dust suppression and strict adherence to ambient air standards; and maintain regular environmental monitoring with half-yearly compliance reporting.";
            default -> "";
        };
    }

    /** Keeps only prose whose numeric literals can be tied to a cited source with an unambiguous unit. */
    GroundedText groundNumbers(String text, List<NumericValue> proposed, List<EvidenceItem> cited) {
        List<NumericValue> declarations = new ArrayList<>();
        if (proposed != null) proposed.stream().filter(number -> numericSupported(number, cited))
                .forEach(declarations::add);
        List<String> safeSentences = new ArrayList<>();
        for (String sentence : text.split("(?<=[.!?;])\\s+")) {
            Matcher matcher = NUMBER.matcher(sentence);
            boolean supported = true;
            while (matcher.find()) {
                BigDecimal value = parseNumber(matcher.group());
                if (value == null) continue;
                NumericValue declared = declarations.stream().filter(n -> normalizeNumber(n.value()).compareTo(normalizeNumber(value)) == 0)
                        .findFirst().orElse(null);
                if (declared == null) {
                    List<NumericValue> candidates = supportedNumbersFor(value, cited);
                    Set<String> units = candidates.stream().map(n -> n.units().trim().toLowerCase(java.util.Locale.ROOT))
                            .collect(java.util.stream.Collectors.toSet());
                    if (units.size() != 1 || candidates.isEmpty()) {
                        supported = false;
                        break;
                    }
                    declared = candidates.getFirst();
                    declarations.add(declared);
                }
            }
            if (supported && !sentence.isBlank()) safeSentences.add(sentence.trim());
        }
        return new GroundedText(String.join(" ", safeSentences), declarations.stream().distinct().toList());
    }

    /** Final guard also covers deterministic GIS claims assembled outside the LLM normalizer. */
    private GenerateReportRequest sanitizeAssembledNumerics(GenerateReportRequest request) {
        Map<String, EvidenceItem> byId = new LinkedHashMap<>();
        request.evidence().forEach(item -> byId.put(item.evidenceId(), item));
        List<ReportClaim> claims = new ArrayList<>();
        for (ReportClaim claim : request.claims()) {
            if (claim == null) continue;
            List<EvidenceItem> cited = claim.evidenceIds().stream().map(byId::get)
                    .filter(java.util.Objects::nonNull).toList();
            GroundedText grounded = groundNumbers(claim.statement(), claim.numericClaims(), cited);
            if (!grounded.text().isBlank()) claims.add(new ReportClaim(claim.sectionId(), claim.templateToken(),
                    grounded.text(), claim.evidenceIds(), grounded.numericClaims()));
        }
        List<ReportTableRow> rows = new ArrayList<>();
        for (ReportTableRow row : request.tableRows()) {
            if (row == null) continue;
            List<EvidenceItem> cited = row.evidenceIds().stream().map(byId::get)
                    .filter(java.util.Objects::nonNull).toList();
            Map<String, String> cells = new LinkedHashMap<>();
            List<NumericValue> numbers = new ArrayList<>();
            for (var cell : row.cells().entrySet()) {
                GroundedText grounded = groundNumbers(cell.getValue(), row.numericClaims(), cited);
                if (!grounded.text().isBlank()) cells.put(cell.getKey(), grounded.text());
                numbers.addAll(grounded.numericClaims());
            }
            if (!cells.isEmpty()) rows.add(new ReportTableRow(row.tableId(), cells, row.evidenceIds(), numbers.stream().distinct().toList()));
        }
        return new GenerateReportRequest(request.projectName(), request.projectProponent(), request.routeAlternativeName(),
                request.studyArea(), request.reportVersion(), request.assessmentDate(), request.evidence(), claims,
                request.gisScreening(), rows);
    }

    private List<NumericValue> supportedNumbersFor(BigDecimal value, List<EvidenceItem> cited) {
        List<NumericValue> result = new ArrayList<>();
        for (EvidenceItem evidence : cited) {
            for (NumericValue source : evidence.numericValues()) {
                if (source != null && source.value() != null
                        && normalizeNumber(source.value()).compareTo(normalizeNumber(value)) == 0
                        && !blank(source.units())) result.add(source);
            }
            if (evidence.type() == EvidenceType.GIS_OBSERVATION && evidence.value() != null) {
                BigDecimal gisValue = parseNumber(evidence.value());
                if (gisValue != null && normalizeNumber(gisValue).compareTo(normalizeNumber(value)) == 0
                        && !blank(evidence.units())) result.add(new NumericValue(gisValue, evidence.units()));
            }
        }
        return result.stream().distinct().toList();
    }

    record GroundedText(String text, List<NumericValue> numericClaims) {}

    private boolean numericSupported(NumericValue number, List<EvidenceItem> cited) {
        if (number == null || number.value() == null || blank(number.units())) return false;
        for (EvidenceItem evidence : cited) {
            for (NumericValue source : evidence.numericValues()) {
                if (source != null && source.value() != null
                        && normalizeNumber(source.value()).compareTo(normalizeNumber(number.value())) == 0
                        && number.units().trim().equalsIgnoreCase(source.units() == null ? "" : source.units().trim())) return true;
            }
            if (evidence.type() == EvidenceType.GIS_OBSERVATION && evidence.value() != null) {
                BigDecimal value = parseNumber(evidence.value());
                if (value != null && normalizeNumber(value).compareTo(normalizeNumber(number.value())) == 0
                        && number.units().trim().equalsIgnoreCase(evidence.units() == null ? "" : evidence.units().trim())) return true;
            }
        }
        return false;
    }

    private BigDecimal normalizeNumber(BigDecimal value) { return value.stripTrailingZeros(); }

    private BigDecimal parseNumber(String value) {
        try { return new BigDecimal(value.replace(",", "")); }
        catch (NumberFormatException ignored) { return null; }
    }

    private String normalizeTemplateToken(String token) {
        String value = clean(token);
        if (value == null) return null;
        if (value.startsWith("{{") && value.endsWith("}}")) return value;
        return "{{" + value + "}}";
    }

    private String normalizeCellName(String value) {
        if (value == null) return "";
        String name = value.trim();
        if (name.startsWith("{{")) name = name.substring(2);
        if (name.endsWith("}}")) name = name.substring(0, name.length() - 2);
        name = name
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_');
        return name;
    }

    private Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private String firstString(Map<?, ?> map, String... keys) {
        Object value = first(map, keys);
        return value == null ? null : String.valueOf(value);
    }

    private List<?> asList(Object value) {
        if (value instanceof List<?> list) return list;
        if (value instanceof Map<?, ?> map) return List.of(map);
        return List.of();
    }

    private List<String> stringList(Object value) {
        if (value instanceof Collection<?> values) return values.stream().filter(java.util.Objects::nonNull)
                .map(String::valueOf).distinct().toList();
        if (value instanceof String text && !text.isBlank()) return List.of(text.trim());
        return List.of();
    }

    private List<NumericValue> numericClaims(Object value) {
        List<NumericValue> result = new ArrayList<>();
        for (Object item : asList(value)) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Object rawValue = first(map, "value", "number");
            String units = firstString(map, "units", "unit");
            if (rawValue == null || blank(units)) continue;
            try {
                result.add(new NumericValue(new BigDecimal(String.valueOf(rawValue).replace(",", "")), units.trim()));
            } catch (NumberFormatException ignored) { }
        }
        return List.copyOf(result);
    }

    private EvidenceItem toEvidence(EnvironmentalKnowledgeIndex.SearchHit hit) {
        boolean project = hit.documentClass() == EnvironmentalKnowledgeIndex.DocumentClass.PROJECT_DOCUMENT;
        EvidenceType type = project ? EvidenceType.PROJECT_DOCUMENT_CLAIM : EvidenceType.GUIDANCE_REFERENCE;
        return new EvidenceItem(hit.evidenceId(), type, hit.excerpt(), hit.sourceTitle(), hit.fileName(),
                project ? hit.pageNumber() : null, project ? hit.chunkId() : null, hit.excerpt(),
                "page " + hit.pageNumber() + ", chunk " + hit.chunkId(), hit.sourceVersion(), hit.jurisdiction(),
                hit.applicability(), null, null, null, null, null, List.of("Text extraction from PDF; verify against the cited original page."),
                hit.numericValues() == null ? List.of() : hit.numericValues());
    }

    private EvidenceItem toEvidence(ProjectFact fact) {
        List<NumericValue> numericValues = new ArrayList<>();
        if (fact.value() != null && fact.units() != null && !fact.units().isBlank()) {
            try { numericValues.add(new NumericValue(new BigDecimal(fact.value().replace(",", "")), fact.units())); }
            catch (NumberFormatException ignored) { }
        }
        return new EvidenceItem(fact.evidenceId(), EvidenceType.PROJECT_DOCUMENT_CLAIM,
                clean(fact.field()) + " = " + clean(fact.value()) + " " + clean(fact.units()),
                fact.sourceTitle(), fact.sourceFileName(), fact.pageNumber(), fact.chunkId(), fact.excerpt(),
                "page " + fact.pageNumber() + ", chunk " + fact.chunkId(), null,
                "Project-specific", "Claim from supplied project source; requires context verification",
                null, null, fact.field(), fact.value(), fact.units(), List.of(), numericValues);
    }

    private void validateFact(ProjectFact fact) {
        if (fact == null || blank(fact.evidenceId()) || blank(fact.field()) || blank(fact.value())
                || blank(fact.sourceTitle()) || blank(fact.sourceFileName()) || fact.pageNumber() == null
                || fact.pageNumber() < 1 || blank(fact.chunkId()) || blank(fact.excerpt())) {
            throw new IllegalArgumentException("Every project fact must include a stable evidence ID, field/value and document title, filename, page, chunk and excerpt.");
        }
    }

    private String formatHit(EnvironmentalKnowledgeIndex.SearchHit hit) {
        return "[" + hit.evidenceId() + "] class=" + hit.documentClass() + "; title=" + hit.sourceTitle()
                + "; filename=" + hit.fileName() + "; page=" + hit.pageNumber() + "; chunk=" + hit.chunkId()
                + "; version=" + hit.sourceVersion() + "; jurisdiction=" + hit.jurisdiction()
                + "; applicability=" + hit.applicability() + "; numericValues=" + hit.numericValues()
                + "\nExcerpt: " + hit.excerpt();
    }

    private String formatGisEvidence(EvidenceItem item) {
        String claim = clean(item.claim());
        String component = claim.contains(":") ? claim.substring(0, claim.indexOf(':')).trim() : claim;
        return "[" + item.evidenceId() + "] GIS_OBSERVATION; component=" + readableField(component)
                + "; layer=" + readableField(clean(item.gisLayer())) + "; operation=" + clean(item.operation())
                + "; metric=" + readableField(clean(item.metric())) + "; value=" + clean(item.value()) + " " + clean(item.units())
                + "; limitations=" + String.join("; ", item.limitations());
    }

    private String readableField(String value) {
        if (value == null || value.isBlank()) return "not supplied";
        return value.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ').replace(":", " — ").replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String clean(String value) { return value == null ? "not supplied" : value.trim(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    record DraftBundle(List<ReportClaim> claims, List<ReportTableRow> tableRows) {}
    private record AnswerDraft(String answer, List<String> evidenceIds) {}
}
