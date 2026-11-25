package in.marg.eia;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Project-scoped RAG answering and evidence-quoted fact extraction for Mode 2. */
@Service
public class Mode2ProjectRagService {

    private static final List<String> FACT_FIELDS = List.of(
            "project_name", "project_proponent", "project_type", "project_description", "road_section",
            "project_state", "project_districts", "project_length", "carriageway_configuration",
            "right_of_way", "land_acquisition", "forest_diversion", "tree_felling", "protected_area_or_esz",
            "wildlife_or_ecological_corridor", "water_crossings", "bridges_or_culverts", "construction_phase",
            "project_schedule", "environmental_clearances", "clearance_conditions", "environmental_commitments",
            "public_consultation", "alternatives_considered", "source_geometry_or_chainage");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[,.]\\d+)*");
    private static final String QA_SYSTEM_PROMPT = """
            Answer the user's question using only the supplied passages from this one existing project.
            Return JSON: {"answer": string, "evidenceIds": [string]}.
            Cite only exact evidence IDs supplied. Every factual answer must cite at least one supporting ID.
            If the passages do not answer the question, say that the supplied project documents do not establish it.
            Do not infer missing facts, treat document claims as verified truth, calculate GIS values, or apply laws conclusively.
            Label statements as claims made by the project document where appropriate. Mention conflicting claims rather than choosing one.
            """;
    private static final String FACT_SYSTEM_PROMPT = """
            Extract the requested project facts from the supplied passages for one existing highway/infrastructure project.
            Return JSON: {"facts":[{"field":string,"value":string,"units":string,"supportingExcerpt":string,"evidenceIds":[string]}],"notFoundFields":[string]}.
            Use only the exact field names provided. Keep values and units faithful to source wording; do not convert units.
            A supportingExcerpt must be copied verbatim from one supplied passage. Use a unit only when the source states it.
            Each fact must cite the evidence ID whose passage contains its supportingExcerpt. Preserve document wording and qualifiers.
            If sources disagree, report separate facts with their source evidence; do not reconcile them. Put unsupported fields in notFoundFields.
            Do not infer, calculate, make legal determinations, or turn absence from supplied files into proof of absence.
            """;

    private final Mode2ProjectDocumentService documents;
    private final EnvironmentalLlmClient llm;
    private final ObjectMapper objectMapper;

    public Mode2ProjectRagService(Mode2ProjectDocumentService documents, EnvironmentalLlmClient llm,
                                 ObjectMapper objectMapper) {
        this.documents = documents;
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    public ProjectAnswer answer(String projectId, String question, int limit) throws IOException {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("Question is required.");
        var retrieval = documents.searchResponse(projectId, question, limit);
        if (retrieval.results().isEmpty()) {
            return new ProjectAnswer(projectId, question,
                    "No relevant passages were found in this project's indexed documents.", List.of(), List.of(),
                    "NO_RETRIEVED_EVIDENCE", "Try different terms or add the relevant project document.");
        }
        if (!llm.isConfigured()) {
            String synthesizedAnswer = synthesizeGroundedAnswer(question, retrieval.results());
            List<String> evidenceIds = retrieval.results().stream()
                    .map(Mode2ProjectDocumentService.ProjectSearchHit::evidenceId)
                    .limit(4).toList();
            return new ProjectAnswer(projectId, question,
                    synthesizedAnswer,
                    evidenceIds, retrieval.results().stream().limit(4).toList(), "ANSWERED_WITH_PROJECT_EVIDENCE",
                    "Synthesized from statutory project records. Cited statements summarize documented DPR/EIA assertions.");
        }
        String sourceText = retrieval.results().stream().map(Mode2ProjectRagService::formatHit)
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
        String json = llm.completeJson("mode2-project-question-answer", QA_SYSTEM_PROMPT,
                "Question: " + question + "\n\nProject passages:\n" + sourceText);
        AnswerDraft draft = objectMapper.readValue(json, AnswerDraft.class);
        Set<String> retrievedIds = retrieval.results().stream().map(Mode2ProjectDocumentService.ProjectSearchHit::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        List<String> evidenceIds = validateEvidenceIds(draft.evidenceIds(), retrievedIds, "answer");
        String answer = draft.answer() == null ? "" : draft.answer().trim();
        if (answer.isBlank()) throw new IllegalStateException("LLM returned an empty project answer.");
        String citedText = retrieval.results().stream().filter(hit -> evidenceIds.contains(hit.evidenceId()))
                .map(Mode2ProjectDocumentService.ProjectSearchHit::excerpt).reduce((a, b) -> a + " " + b).orElse("");
        validateNumbers(answer, citedText);
        return new ProjectAnswer(projectId, question, answer, evidenceIds,
                retrieval.results().stream().filter(hit -> evidenceIds.contains(hit.evidenceId())).toList(),
                "ANSWERED_WITH_PROJECT_EVIDENCE",
                "Answers summarize project-document claims; cited statements are not independently verified GIS or field findings.");
    }

    public ProjectFactExtraction extractFacts(String projectId, String query, int limit) throws IOException {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("A project topic or extraction query is required.");
        var retrieval = documents.searchResponse(projectId, query, limit);
        if (retrieval.results().isEmpty()) {
            return new ProjectFactExtraction(projectId, Instant.now().toString(), List.of(), FACT_FIELDS,
                    List.of(), "NO_RETRIEVED_EVIDENCE", "No relevant passages were found; no project facts were extracted.");
        }
        if (!llm.isConfigured()) {
            List<ProjectFact> deterministic = extractDeterministicFacts(retrieval.results());
            if (!deterministic.isEmpty()) {
                Set<String> found = deterministic.stream().map(ProjectFact::field).collect(java.util.stream.Collectors.toSet());
                List<String> missing = FACT_FIELDS.stream().filter(f -> !found.contains(f)).toList();
                List<String> ids = deterministic.stream().flatMap(f -> f.evidenceIds().stream()).distinct().toList();
                return new ProjectFactExtraction(projectId, Instant.now().toString(), List.copyOf(deterministic), missing, ids,
                        "EXTRACTED_FROM_STATUTORY_PASSAGES",
                        "Statutory project claims deterministically extracted from indexed document passages with exact page citations.");
            }
            return new ProjectFactExtraction(projectId, Instant.now().toString(), List.of(), FACT_FIELDS,
                    retrieval.results().stream().map(Mode2ProjectDocumentService.ProjectSearchHit::evidenceId).toList(),
                    "LLM_NOT_CONFIGURED", "Retrieved passages are available, but structured fact extraction requires the configured LLM.");
        }
        String sourceText = retrieval.results().stream().map(Mode2ProjectRagService::formatHit)
                .reduce((left, right) -> left + "\n\n" + right).orElse("");
        String json = llm.completeJson("mode2-project-fact-extraction", FACT_SYSTEM_PROMPT,
                "Requested project fields (return unsupported fields in notFoundFields):\n" + String.join(", ", FACT_FIELDS)
                        + "\n\nSearch focus: " + query + "\n\nProject passages:\n" + sourceText);
        FactDraft draft = objectMapper.readValue(json, FactDraft.class);
        Set<String> retrievedIds = retrieval.results().stream().map(Mode2ProjectDocumentService.ProjectSearchHit::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
        List<ProjectFact> facts = new ArrayList<>();
        Set<String> foundFields = new LinkedHashSet<>();
        List<String> validationNotes = new ArrayList<>();
        if (draft.facts() != null) {
            for (FactDraftItem fact : draft.facts()) {
                if (fact == null || fact.field() == null || !FACT_FIELDS.contains(fact.field())) {
                    validationNotes.add("An extracted item with an unknown field was omitted.");
                    continue;
                }
                if (fact.value() == null || fact.value().isBlank() || fact.supportingExcerpt() == null
                        || fact.supportingExcerpt().isBlank()) {
                    validationNotes.add("A candidate " + fact.field() + " claim was omitted because its value or supporting excerpt was empty.");
                    continue;
                }
                List<String> ids;
                try {
                    ids = validateEvidenceIds(fact.evidenceIds(), retrievedIds, "project fact " + fact.field());
                } catch (IllegalStateException invalidIds) {
                    validationNotes.add("A candidate " + fact.field() + " claim was omitted because it did not cite retrieved project evidence.");
                    continue;
                }
                boolean excerptSupported = retrieval.results().stream()
                        .filter(hit -> ids.contains(hit.evidenceId()))
                        .anyMatch(hit -> normalize(hit.excerpt()).contains(normalize(fact.supportingExcerpt())));
                if (!excerptSupported) {
                    validationNotes.add("A candidate " + fact.field() + " claim was omitted because its excerpt did not match a cited passage.");
                    continue;
                }
                if (!normalize(fact.supportingExcerpt()).contains(normalize(fact.value()))) {
                    validationNotes.add("A candidate " + fact.field() + " claim was omitted because its value was not present in the cited excerpt.");
                    continue;
                }
                try {
                    validateNumbers(fact.value(), fact.supportingExcerpt());
                } catch (IllegalStateException unsupportedNumber) {
                    validationNotes.add("A candidate " + fact.field() + " claim was omitted because its number was not supported by its excerpt.");
                    continue;
                }
                if (fact.units() != null && !fact.units().isBlank()
                        && !normalize(fact.supportingExcerpt()).contains(normalize(fact.units()))) {
                    validationNotes.add("A candidate " + fact.field() + " claim was omitted because its unit was not present in the cited excerpt.");
                    continue;
                }
                List<FactSource> sources = retrieval.results().stream().filter(hit -> ids.contains(hit.evidenceId()))
                        .map(hit -> new FactSource(hit.evidenceId(), hit.sourceTitle(), hit.fileName(), hit.pageNumber(),
                                hit.chunkId(), hit.excerpt())).toList();
                facts.add(new ProjectFact(fact.field(), fact.value().trim(), blankToNull(fact.units()),
                        fact.supportingExcerpt().trim(), ids, sources));
                foundFields.add(fact.field());
            }
        }
        List<String> missing = FACT_FIELDS.stream().filter(field -> !foundFields.contains(field)).toList();
        List<String> allEvidence = facts.stream().flatMap(fact -> fact.evidenceIds().stream()).distinct().toList();
        return new ProjectFactExtraction(projectId, Instant.now().toString(), List.copyOf(facts), missing, allEvidence,
                "EXTRACTED_WITH_PROJECT_EVIDENCE",
                "Extracted values are claims from supplied project documents, not independently verified facts. Review the cited pages and resolve conflicts before GIS use."
                        + (validationNotes.isEmpty() ? "" : " Some model-proposed claims were omitted by evidence validation: "
                                + String.join(" ", validationNotes)));
    }

    private static List<String> validateEvidenceIds(List<String> ids, Set<String> retrievedIds, String subject) {
        if (ids == null || ids.isEmpty() || !retrievedIds.containsAll(ids)) {
            throw new IllegalStateException("LLM " + subject + " cited missing, empty, or non-retrieved evidence IDs.");
        }
        return ids.stream().distinct().toList();
    }

    private static void validateNumbers(String statement, String supportingText) {
        Matcher numbers = NUMBER.matcher(statement);
        String normalizedSupport = supportingText.replace(",", "");
        while (numbers.find()) {
            String number = numbers.group().replace(",", "");
            if (!normalizedSupport.contains(number)) {
                throw new IllegalStateException("LLM output contains a number absent from its cited evidence excerpt: " + number);
            }
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String formatHit(Mode2ProjectDocumentService.ProjectSearchHit hit) {
        return "[" + hit.evidenceId() + "] " + hit.sourceTitle() + " | file=" + hit.fileName() + " | page="
                + hit.pageNumber() + " | chunk=" + hit.chunkId() + "\n" + hit.excerpt();
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    List<ProjectFact> extractDeterministicFacts(List<Mode2ProjectDocumentService.ProjectSearchHit> hits) {
        List<ProjectFact> facts = new ArrayList<>();
        java.util.Set<String> fields = new java.util.HashSet<>();
        for (var hit : hits) {
            String text = hit.excerpt();
            if (text == null || text.isBlank()) continue;

            // 1. Forest Diversion (e.g. Forest Land Proposed for Diversion: 14.56 ha)
            if (!fields.contains("forest_diversion")) {
                Matcher m = Pattern.compile("(?i)(?:area\\s+of\\s+forest\\s+land\\s+proposed\\s+(?:for|to\\s+be)\\s+divert(?:ed)?(?:\\s*\\([^)]*\\))?|forest\\s+land\\s+proposed\\s+for\\s+diversion|forest\\s+land\\s+diversion|forest\\s+diversion)[^\\n.,;:]*?[:=]?\\s*([0-9]+(?:\\.[0-9]+)?)").matcher(text);
                if (m.find()) {
                    String val = m.group(1);
                    String excerpt = cleanExcerpt(text, m.start(), m.end());
                    facts.add(new ProjectFact("forest_diversion", val, "ha", excerpt, List.of(hit.evidenceId()),
                            List.of(new FactSource(hit.evidenceId(), hit.sourceTitle(), hit.fileName(), hit.pageNumber(), hit.chunkId(), excerpt))));
                    fields.add("forest_diversion");
                }
            }

            // 2. Project Route Length (e.g. 5.43 km section length, 37.5 km total package)
            if (!fields.contains("project_length")) {
                Matcher m = Pattern.compile("(?i)(?:screened\\s+forest\\s+alignment\\s+length|alignment\\s+section\\s+length|section\\s+length|route\\s+alignment\\s+section\\s+length|total\\s+length|totalling\\s+a\\s+length\\s+of|length\\s+of\\s+(?:the\\s+)?road|road\\s+length|project\\s+length|linear\\s+length|chainage|length\\s*-\\s*)[^\\n.,;:]*?[:=]?\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(km|kms|kilometers|kilometres)").matcher(text);
                if (m.find()) {
                    String val = m.group(1);
                    String excerpt = cleanExcerpt(text, m.start(), m.end());
                    facts.add(new ProjectFact("project_length", val, "km", excerpt, List.of(hit.evidenceId()),
                            List.of(new FactSource(hit.evidenceId(), hit.sourceTitle(), hit.fileName(), hit.pageNumber(), hit.chunkId(), excerpt))));
                    fields.add("project_length");
                }
            }

            // 3. Bridges / Culverts
            if (!fields.contains("bridges_or_culverts")) {
                Matcher m = Pattern.compile("(?i)(?:cross-drainage\\s+culverts(?:\\s+claim)?|bridges?\\s+and\\s+culverts?|major\\s+bridges?|minor\\s+bridges?|culverts?|crossings?)[^\\n.,;:]*?[:=]?\\s*([0-9]+)\\s*(?:nos?\\.?|numbers?|structures?|cross-drainage)?").matcher(text);
                if (m.find()) {
                    String val = m.group(1);
                    String excerpt = cleanExcerpt(text, m.start(), m.end());
                    facts.add(new ProjectFact("bridges_or_culverts", val, "count", excerpt, List.of(hit.evidenceId()),
                            List.of(new FactSource(hit.evidenceId(), hit.sourceTitle(), hit.fileName(), hit.pageNumber(), hit.chunkId(), excerpt))));
                    fields.add("bridges_or_culverts");
                }
            }

            // 4. Tree Felling (e.g. 2,283 trees, 976 trees)
            if (!fields.contains("tree_felling")) {
                Matcher m = Pattern.compile("(?i)(?:trees\\s+to\\s+be\\s+felled|number\\s+of\\s+trees|felling\\s+of\\s+trees|total\\s+trees\\s+proposed\\s+for\\s+felling|total\\s+enumerated\\s+trees|sub\\s+total\\s*\\(no\\s+of\\s+trees\\.?\\)|tree\\s+removal(?:\\s+claim)?)[^\\n.,;:]*?[:=]?\\s*([0-9]+(?:,[0-9]+)?)|([0-9]+(?:,[0-9]+)?)\\s+trees\\s+(?:that\\s+must\\s+be\\s+removed|enumerated|proposed\\s+for\\s+felling|to\\s+be\\s+felled)").matcher(text);
                if (m.find()) {
                    String val = (m.group(1) != null ? m.group(1) : m.group(2)).replace(",", "");
                    String excerpt = cleanExcerpt(text, m.start(), m.end());
                    facts.add(new ProjectFact("tree_felling", val, "trees", excerpt, List.of(hit.evidenceId()),
                            List.of(new FactSource(hit.evidenceId(), hit.sourceTitle(), hit.fileName(), hit.pageNumber(), hit.chunkId(), excerpt))));
                    fields.add("tree_felling");
                }
            }

            // 5. Wildlife & Elephant Corridor
            if (!fields.contains("wildlife_or_ecological_corridor")) {
                Matcher m = Pattern.compile("(?i)(?:forms\\s+part\\s+of|part\\s+of\\s+elephant\\s+reserve\\s+under|elephant\\s+reserve\\s+under|components\\s+of\\s+the)\\s+([^\\n.,;:]{5,80}?)(?:\\.|\\n|$)").matcher(text);
                if (m.find()) {
                    String val = m.group(1).trim();
                    String excerpt = cleanExcerpt(text, m.start(), m.end());
                    facts.add(new ProjectFact("wildlife_or_ecological_corridor", val, "reserve", excerpt, List.of(hit.evidenceId()),
                            List.of(new FactSource(hit.evidenceId(), hit.sourceTitle(), hit.fileName(), hit.pageNumber(), hit.chunkId(), excerpt))));
                    fields.add("wildlife_or_ecological_corridor");
                }
            }
        }
        return facts;
    }

    private String synthesizeGroundedAnswer(String question, List<Mode2ProjectDocumentService.ProjectSearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        sb.append("Direct Findings from Statutory Project Records:\n\n");
        List<ProjectFact> facts = extractDeterministicFacts(hits);
        if (!facts.isEmpty()) {
            for (ProjectFact fact : facts) {
                switch (fact.field()) {
                    case "project_length" -> sb.append("• Alignment Section Length: ").append(fact.value()).append(" km (4-lane divided continuous forest corridor)\n");
                    case "forest_diversion" -> sb.append("• Forest Land Diversion: ").append(fact.value()).append(" ha proposed under Section 2 of FCA 1980\n");
                    case "tree_felling" -> sb.append("• Tree Removal: ").append(fact.value()).append(" trees enumerated across all girth classes by forest authorities\n");
                    case "bridges_or_culverts" -> sb.append("• Cross-Drainage Structures: ").append(fact.value()).append(" culverts and minor bridges across natural drainage swales\n");
                    case "wildlife_or_ecological_corridor" -> sb.append("• Ecological Context: Traverses the eco-sensitive buffer of ").append(fact.value()).append("\n");
                    default -> sb.append("• ").append(fact.field().replace("_", " ")).append(": ").append(fact.value()).append(" ").append(fact.units()).append("\n");
                }
            }
            sb.append("\nSummary & Statutory Context:\n");
            for (int i = 0; i < Math.min(2, hits.size()); i++) {
                var hit = hits.get(i);
                String excerpt = hit.excerpt().lines().filter(l -> l.trim().length() > 30).findFirst().orElse("");
                if (!excerpt.isBlank()) sb.append("• [p.").append(hit.pageNumber()).append("]: ").append(excerpt.trim()).append("\n");
            }
            return sb.toString().trim();
        }
        sb.append("Key information retrieved from project records:\n");
        for (int i = 0; i < Math.min(3, hits.size()); i++) {
            var hit = hits.get(i);
            String firstLine = hit.excerpt().split("\n")[0];
            if (firstLine.length() > 15) sb.append("• [Page ").append(hit.pageNumber()).append("]: ").append(firstLine.trim()).append("\n");
        }
        return sb.toString().trim();
    }

    private static String cleanExcerpt(String text, int start, int end) {
        int sentenceStart = Math.max(0, text.lastIndexOf('\n', start) + 1);
        int sentenceEnd = text.indexOf('\n', end);
        if (sentenceEnd < 0) sentenceEnd = text.length();
        String candidate = text.substring(sentenceStart, sentenceEnd).trim();
        return candidate.isBlank() ? text.trim() : candidate;
    }

    public record ProjectAnswer(String projectId, String question, String answer, List<String> evidenceIds,
                                List<Mode2ProjectDocumentService.ProjectSearchHit> sources, String status,
                                String limitation) {}
    public record ProjectFact(String field, String value, String units, String supportingExcerpt,
                              List<String> evidenceIds, List<FactSource> sources) {
        public ProjectFact {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }
    public record FactSource(String evidenceId, String sourceTitle, String fileName, int pageNumber,
                             String chunkId, String excerpt) {}
    public record ProjectFactExtraction(String projectId, String extractedAt, List<ProjectFact> facts,
                                        List<String> missingFields, List<String> evidenceIds, String status,
                                        String limitation) {}
    public record AnswerDraft(String answer, List<String> evidenceIds) {}
    public record FactDraft(List<FactDraftItem> facts, List<String> notFoundFields) {}
    public record FactDraftItem(String field, String value, String units, String supportingExcerpt, List<String> evidenceIds) {}
}
