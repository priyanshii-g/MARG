package in.marg.eia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class Mode2ProjectDocumentServiceTest {

    @TempDir
    Path directory;

    @Test
    void searchIsProjectScopedAndProvenanceSurvivesRebuildingFromDisk() throws Exception {
        var service = new Mode2ProjectDocumentService(new Mode2ProjectProperties(directory), new PageTextChunker());
        var first = service.createProject("Highway Alpha");
        var second = service.createProject("Highway Beta");
        byte[] source = pdf("Forest diversion is claimed in the project proposal.",
                "The water crossing inventory lists three seasonal streams.");
        var uploaded = service.uploadDocument(first.projectId(), new MockMultipartFile(
                "file", "DPR.pdf", "application/pdf", source));

        var hits = service.search(first.projectId(), "water crossing seasonal streams", 5);
        var otherProjectHits = service.search(second.projectId(), "water crossing seasonal streams", 5);
        var rebuiltService = new Mode2ProjectDocumentService(new Mode2ProjectProperties(directory), new PageTextChunker());
        var rebuiltHits = rebuiltService.search(first.projectId(), "water crossing seasonal streams", 5);

        assertEquals(2, uploaded.pageCount());
        assertEquals("INDEXED", uploaded.processingStatus());
        assertFalse(hits.isEmpty());
        assertEquals(2, hits.get(0).pageNumber());
        assertTrue(hits.get(0).chunkId().contains("-P2-C"));
        assertTrue(hits.get(0).evidenceId().startsWith("M2-EV-"));
        assertEquals(first.projectId(), hits.get(0).projectId());
        assertTrue(otherProjectHits.isEmpty());
        assertEquals(hits.get(0).evidenceId(), rebuiltHits.get(0).evidenceId());
        assertEquals(List.of(uploaded), rebuiltService.listDocuments(first.projectId()));
    }

    @Test
    void sameFileNameWithDifferentContentsRemainsTwoProjectDocuments() throws Exception {
        var service = new Mode2ProjectDocumentService(new Mode2ProjectProperties(directory), new PageTextChunker());
        var project = service.createProject("Highway Gamma");
        var first = service.uploadDocument(project.projectId(), new MockMultipartFile(
                "file", "DPR.pdf", "application/pdf", pdf("First alignment crosses a river.")));
        var second = service.uploadDocument(project.projectId(), new MockMultipartFile(
                "file", "DPR.pdf", "application/pdf", pdf("Second alignment crosses a wetland.")));

        assertEquals(2, service.listDocuments(project.projectId()).size());
        assertNotEquals(first.documentId(), second.documentId());
        assertFalse(service.search(project.projectId(), "river", 5).isEmpty());
        assertFalse(service.search(project.projectId(), "wetland", 5).isEmpty());
    }

    @Test
    void rejectsInvalidProjectIdsAndNonPdfUploads() throws Exception {
        var service = new Mode2ProjectDocumentService(new Mode2ProjectProperties(directory), new PageTextChunker());
        assertThrows(IllegalArgumentException.class, () -> service.search("../../outside", "forest", 5));
        var project = service.createProject("Highway Delta");
        assertThrows(IllegalArgumentException.class, () -> service.uploadDocument(project.projectId(),
                new MockMultipartFile("file", "notes.txt", "text/plain", "not a PDF".getBytes())));
    }

    private static byte[] pdf(String... pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                    stream.newLineAtOffset(50, 750);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
