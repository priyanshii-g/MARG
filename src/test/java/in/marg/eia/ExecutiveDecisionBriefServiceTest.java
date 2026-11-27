package in.marg.eia;

import in.marg.eia.ExecutiveDecisionBriefService.ExecutiveBrief;
import in.marg.eia.Mode2AlignmentScreeningService.AlignmentScreeningRequest;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ExecutiveDecisionBriefServiceTest {

    @Autowired
    private ExecutiveDecisionBriefService briefService;

    @Autowired
    private ExecutiveDecisionBriefDocxWriter docxWriter;

    @Autowired
    private Mode2ProjectDocumentService documentService;

    @Test
    void testGenerateExecutiveBriefAndDocx() throws IOException {
        // Create project and screen alignment
        var project = documentService.createProject("Executive Brief Test Project");
        String projectId = project.projectId();

        AlignmentScreeningRequest request = new AlignmentScreeningRequest(
                "Sildharampur RF Corridor",
                "assam",
                "NATIONAL_HIGHWAY",
                List.of(
                        List.of(92.9999, 26.2208),
                        List.of(93.0159, 26.2185),
                        List.of(93.0474, 26.2188)
                )
        );

        ExecutiveBrief brief = briefService.generateBrief(projectId, request);

        assertNotNull(brief);
        assertEquals(projectId, brief.projectId());
        assertNotNull(brief.readinessReport());
        assertTrue(brief.readinessReport().score() >= 0 && brief.readinessReport().score() <= 100);
        assertNotNull(brief.mitigationBudget());
        assertTrue(brief.mitigationBudget().totalStatutoryLiabilityCrores() > 0.0);
        assertNotNull(brief.lendersRecommendation());

        // Test DOCX Generation
        byte[] docxBytes = docxWriter.writeBrief(brief);
        assertNotNull(docxBytes);
        assertTrue(docxBytes.length > 5000, "Generated DOCX document must be non-empty and well-structured, was: " + docxBytes.length);
    }
}
