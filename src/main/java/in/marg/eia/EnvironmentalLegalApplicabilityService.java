package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Builds a source-grounded legal reference register without making a model call or legal determination. */
@Service
public class EnvironmentalLegalApplicabilityService {

    private final EnvironmentalKnowledgeIndex index;

    public EnvironmentalLegalApplicabilityService(EnvironmentalKnowledgeIndex index) {
        this.index = index;
    }

    /**
     * Returns a deterministic inventory of retrieved legal sources. Applicability is intentionally left
     * unresolved here: a source's presence in the library is not evidence that a project trigger applies.
     */
    public LegalApplicabilityAssessment assess(LegalApplicabilityRequest request) {
        if (request == null) throw new IllegalArgumentException("legal-applicability request is required");
        for (ProjectFact fact : request.projectFacts()) validateFact(fact);

        String query = String.join(" ", safe(request.projectType()), safe(request.projectState()),
                "highway environmental clearance forest land biological diversity wetlands protected areas legal rules");
        List<EnvironmentalKnowledgeIndex.SearchHit> retrieved = index.search(query, 50, false).stream()
                .filter(hit -> hit.documentClass() == EnvironmentalKnowledgeIndex.DocumentClass.LEGAL_SOURCE)
                .filter(this::hasStableSourceProvenance)
                .limit(12).toList();
        if (retrieved.isEmpty()) {
            List<String> gaps = new ArrayList<>(knowledgeBaseGaps());
            gaps.add("No legal-source passages were retrieved for this project/state query; legal applicability was not assessed.");
            return new LegalApplicabilityAssessment(request.projectName(), request.assessmentDate(), List.of(), gaps,
                    "No legal applicability conclusion is available. Retrieve current official legal sources and have a qualified reviewer assess project triggers.");
        }

        Map<String, List<EnvironmentalKnowledgeIndex.SearchHit>> byDocument = new LinkedHashMap<>();
        for (EnvironmentalKnowledgeIndex.SearchHit hit : retrieved) {
            String key = hit.documentId() + "|" + hit.fileName();
            byDocument.computeIfAbsent(key, ignored -> new ArrayList<>()).add(hit);
        }
        List<LegalInstrumentAssessment> instruments = new ArrayList<>();
        for (List<EnvironmentalKnowledgeIndex.SearchHit> sourceHits : byDocument.values()) {
            EnvironmentalKnowledgeIndex.SearchHit source = sourceHits.getFirst();
            List<String> evidenceIds = sourceHits.stream().map(EnvironmentalKnowledgeIndex.SearchHit::evidenceId)
                    .distinct().toList();
            String currency = source.sourceVersion() == null || source.sourceVersion().isBlank()
                    ? "Indexed copy; currency has not been verified against the current official source."
                    : "Indexed version metadata: " + source.sourceVersion() + "; verify against the current official source.";
            instruments.add(new LegalInstrumentAssessment(source.sourceTitle(), "INSUFFICIENT_INFORMATION",
                    "A passage from this source was retrieved. Source presence alone does not establish whether a project-specific legal trigger applies. A qualified reviewer must verify project-specific triggers against the complete current official instrument.",
                    evidenceIds, List.of(), currency));
        }
        return new LegalApplicabilityAssessment(request.projectName(), request.assessmentDate(), instruments,
                knowledgeBaseGaps(), "Source inventory only. This service does not determine legal applicability, compliance, exemption or clearance; obtain qualified review against current official sources.");
    }

    private boolean hasStableSourceProvenance(EnvironmentalKnowledgeIndex.SearchHit hit) {
        return hit != null && hit.evidenceId() != null && !hit.evidenceId().isBlank()
                && hit.documentId() != null && !hit.documentId().isBlank()
                && hit.sourceTitle() != null && !hit.sourceTitle().isBlank()
                && hit.fileName() != null && !hit.fileName().isBlank()
                && hit.pageNumber() > 0 && hit.chunkId() != null && !hit.chunkId().isBlank()
                && hit.excerpt() != null && !hit.excerpt().isBlank();
    }

    private List<String> knowledgeBaseGaps() {
        List<String> fileNames = index.status().documents().stream()
                .map(doc -> doc.fileName().toLowerCase().replace('_', ' ').replace('-', ' ')).toList();
        List<String> gaps = new ArrayList<>();
        if (fileNames.stream().noneMatch(name -> name.contains("eia notification"))) gaps.add("Current consolidated EIA Notification and amendments from official sources.");
        else if (fileNames.stream().anyMatch(name -> name.contains("eia notification"))
                && fileNames.stream().noneMatch(name -> name.contains("july 2026"))) {
            gaps.add("Uploaded EIA Notification compilation is through March 2025; add the official compilation through July 2026 before relying on currency.");
        }
        if (fileNames.stream().noneMatch(name -> name.contains("air act") || name.contains("air_prevention")))
            gaps.add("Air (Prevention and Control of Pollution) Act, 1981 and current amendments/rules (the file named regulations_air.pdf is actually Biological Diversity Rules, 2024).");
        if (fileNames.stream().noneMatch(name -> name.contains("water act") || name.contains("water_prevention")))
            gaps.add("Water (Prevention and Control of Pollution) Act, 1974 and current amendments/rules.");
        if (fileNames.stream().noneMatch(name -> name.contains("wildlife") || name.contains("wild life")))
            gaps.add("Wild Life (Protection) Act, 1972 and current amendments/rules.");
        if (fileNames.stream().noneMatch(name -> name.contains("coastal regulation") || name.contains("crz")))
            gaps.add("Coastal Regulation Zone Notification and amendments, if the project can affect a coastal regulated area.");
        if (fileNames.stream().noneMatch(name -> name.contains("noise")))
            gaps.add("Noise Pollution (Regulation and Control) Rules, 2000 and current amendments.");
        return List.copyOf(gaps);
    }

    private void validateFact(ProjectFact fact) {
        if (fact == null || blank(fact.evidenceId()) || blank(fact.field()) || blank(fact.value())
                || blank(fact.sourceTitle()) || blank(fact.sourceFileName()) || fact.pageNumber() == null
                || fact.pageNumber() < 1 || blank(fact.chunkId()) || blank(fact.excerpt())) {
            throw new IllegalArgumentException("Every project fact must include a stable evidence ID, field/value and document title, filename, page, chunk and excerpt.");
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String safe(String value) { return blank(value) ? "not supplied" : value.trim(); }
}
