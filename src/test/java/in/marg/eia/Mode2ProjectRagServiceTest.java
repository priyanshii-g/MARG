package in.marg.eia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

class Mode2ProjectRagServiceTest {

    @TempDir
    Path directory;

    @Test
    void returnsCitedProjectPassagesWithoutInventingAnAnswerWhenLlmIsDisabled() throws Exception {
        var projects = new Mode2ProjectDocumentService(new Mode2ProjectProperties(directory), new PageTextChunker());
        var project = projects.createProject("Project Epsilon");
        projects.uploadDocument(project.projectId(), new MockMultipartFile("file", "DPR.pdf", "application/pdf",
                pdf("The water crossing inventory lists three seasonal streams.")));
        var rag = new Mode2ProjectRagService(projects,
                new StubLlm(false, ""), JsonMapper.builder().build());

        var answer = rag.answer(project.projectId(), "water crossing inventory", 5);

        assertEquals("ANSWERED_WITH_PROJECT_EVIDENCE", answer.status());
        assertFalse(answer.evidenceIds().isEmpty());
        assertFalse(answer.sources().isEmpty());
        assertTrue(answer.sources().get(0).excerpt().contains("seasonal streams"));
    }

    @Test
    void rejectsUnknownEvidenceIdsAndUnsupportedNumbersInGeneratedAnswers() throws Exception {
        var projects = new Mode2ProjectDocumentService(new Mode2ProjectProperties(directory), new PageTextChunker());
        var project = projects.createProject("Project Zeta");
        projects.uploadDocument(project.projectId(), new MockMultipartFile("file", "DPR.pdf", "application/pdf",
                pdf("The water crossing inventory lists 3 seasonal streams.")));
        var hits = projects.search(project.projectId(), "water crossing inventory", 5);
        String evidenceId = hits.get(0).evidenceId();
        var json = JsonMapper.builder().build();

        var hallucinatedCitation = new Mode2ProjectRagService(projects,
                new StubLlm(true, "{\"answer\":\"The document reports 3 streams.\",\"evidenceIds\":[\"M2-EV-FAKE\"]}"), json);
        assertThrows(IllegalStateException.class,
                () -> hallucinatedCitation.answer(project.projectId(), "water crossing inventory", 5));

        var unsupportedNumber = new Mode2ProjectRagService(projects,
                new StubLlm(true, "{\"answer\":\"The document reports 30 streams.\",\"evidenceIds\":[\""
                        + evidenceId + "\"]}"), json);
        assertThrows(IllegalStateException.class,
                () -> unsupportedNumber.answer(project.projectId(), "water crossing inventory", 5));
    }

    @Test
    void factExtractionRequiresVerbatimSupportAndReturnsUnreportedFields() throws Exception {
        var projects = new Mode2ProjectDocumentService(new Mode2ProjectProperties(directory), new PageTextChunker());
        var project = projects.createProject("Project Eta");
        String excerpt = "The water crossing inventory lists three seasonal streams.";
        projects.uploadDocument(project.projectId(), new MockMultipartFile("file", "DPR.pdf", "application/pdf", pdf(excerpt)));
        String evidenceId = projects.search(project.projectId(), "water crossing inventory", 5).get(0).evidenceId();
        String validJson = "{\"facts\":[{\"field\":\"water_crossings\",\"value\":\"three seasonal streams\","
                + "\"units\":null,\"supportingExcerpt\":\"" + excerpt + "\",\"evidenceIds\":[\""
                + evidenceId + "\"]}],\"notFoundFields\":[]}";
        var valid = new Mode2ProjectRagService(projects, new StubLlm(true, validJson), JsonMapper.builder().build());

        var result = valid.extractFacts(project.projectId(), "water crossing inventory", 5);

        assertEquals(1, result.facts().size());
        assertEquals("water_crossings", result.facts().get(0).field());
        assertEquals(evidenceId, result.facts().get(0).sources().get(0).evidenceId());
        assertEquals(1, result.facts().get(0).sources().get(0).pageNumber());
        assertTrue(result.missingFields().contains("project_length"));

        var unsupported = new Mode2ProjectRagService(projects,
                new StubLlm(true, validJson.replace(excerpt, "No source support for this statement.")), JsonMapper.builder().build());
        var unsupportedResult = unsupported.extractFacts(project.projectId(), "water crossing inventory", 5);
        assertTrue(unsupportedResult.facts().isEmpty());
        assertTrue(unsupportedResult.missingFields().contains("water_crossings"));
        assertTrue(unsupportedResult.limitation().contains("omitted by evidence validation"));

        String mismatchedValueJson = validJson.replace("\"value\":\"three seasonal streams\"",
                "\"value\":\"four seasonal streams\"");
        var mismatchedValue = new Mode2ProjectRagService(projects,
                new StubLlm(true, mismatchedValueJson), JsonMapper.builder().build());
        var mismatchedResult = mismatchedValue.extractFacts(project.projectId(), "water crossing inventory", 5);
        assertTrue(mismatchedResult.facts().isEmpty());
        assertTrue(mismatchedResult.missingFields().contains("water_crossings"));
        assertTrue(mismatchedResult.limitation().contains("value was not present in the cited excerpt"));
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                stream.newLineAtOffset(50, 750);
                stream.showText(text);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static final class StubLlm extends EnvironmentalLlmClient {
        private final boolean configured;
        private final String response;

        private StubLlm(boolean configured, String response) {
            super(new EnvironmentalLlmProperties(false, null, null, null, 1));
            this.configured = configured;
            this.response = response;
        }

        @Override public boolean isConfigured() { return configured; }
        @Override public String completeJson(String operation, String systemPrompt, String userPrompt) { return response; }
    }
}
