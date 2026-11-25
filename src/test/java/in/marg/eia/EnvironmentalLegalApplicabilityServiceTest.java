package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class EnvironmentalLegalApplicabilityServiceTest {

    @Test
    void createsExactSourceEvidenceRegisterWithoutMakingLlmCalls() {
        EnvironmentalKnowledgeIndex index = mock(EnvironmentalKnowledgeIndex.class);
        var eiaPageOne = hit("KB-EIA-P1-C0", "DOC-EIA", "Environment Impact Assessment Notification, 2006 and amendments",
                "EIA_Notification_2006_including_all_amendments_till_March_2025.pdf", 1, "DOC-EIA-P1-C0");
        var eiaPageTwo = hit("KB-EIA-P2-C0", "DOC-EIA", "Environment Impact Assessment Notification, 2006 and amendments",
                "EIA_Notification_2006_including_all_amendments_till_March_2025.pdf", 2, "DOC-EIA-P2-C0");
        var biodiversity = hit("KB-BIO-P1-C0", "DOC-BIO", "Biological Diversity Rules, 2024",
                "regulations_air.pdf", 1, "DOC-BIO-P1-C0");
        when(index.search(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(List.of(eiaPageOne, eiaPageTwo, biodiversity));
        when(index.status()).thenReturn(new EnvironmentalKnowledgeIndex.IndexStatus("/kb", 2, 3,
                List.of(new EnvironmentalKnowledgeIndex.LibraryDocument("DOC-EIA",
                                "EIA_Notification_2006_including_all_amendments_till_March_2025.pdf",
                                EnvironmentalKnowledgeIndex.DocumentClass.LEGAL_SOURCE, 82, null),
                        new EnvironmentalKnowledgeIndex.LibraryDocument("DOC-BIO", "regulations_air.pdf",
                                EnvironmentalKnowledgeIndex.DocumentClass.LEGAL_SOURCE, 86, null)), null, false));

        var service = new EnvironmentalLegalApplicabilityService(index);
        var assessment = service.assess(new LegalApplicabilityRequest("Road Project", "Highway", "Assam",
                "2026-11-20", List.of()));

        assertEquals(2, assessment.instruments().size());
        var eia = assessment.instruments().getFirst();
        assertEquals("Environment Impact Assessment Notification, 2006 and amendments", eia.instrument());
        assertEquals(List.of("KB-EIA-P1-C0", "KB-EIA-P2-C0"), eia.evidenceIds());
        assertEquals("INSUFFICIENT_INFORMATION", eia.screeningStatus());
        assertTrue(eia.missingProjectFacts().isEmpty());
        assertTrue(eia.analysis().contains("qualified reviewer must verify"));
        assertTrue(assessment.knowledgeBaseGaps().stream().anyMatch(gap -> gap.contains("through March 2025")));
    }

    private EnvironmentalKnowledgeIndex.SearchHit hit(String evidenceId, String documentId, String title,
                                                       String fileName, int page, String chunkId) {
        return new EnvironmentalKnowledgeIndex.SearchHit(evidenceId, documentId, title, fileName, page, chunkId,
                "Retrieved legal source excerpt.", EnvironmentalKnowledgeIndex.DocumentClass.LEGAL_SOURCE,
                "Indexed copy", "India", "Verify project-specific applicability.", 1.0, List.of());
    }
}
