package in.marg.eia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentalKnowledgeIndexTest {

    @TempDir
    Path directory;

    @Test
    void indexesPdfPagesAndChunksAndExcludesSampleReportsFromFactualSearch() throws Exception {
        writePdf(directory.resolve("EIA_Notification_2006.pdf"),
                "Highway environmental clearance requires review of forest land and protected areas. Study distance 12 km.");
        writePdf(directory.resolve("Sample_EIAReport.pdf"),
                "Sample report states the imaginary tiger population is 999 animals.");
        writePdf(directory.resolve("regulations_air.pdf"),
                "The Gazette notification contains biological diversity rules and regulatory definitions.");

        var index = new EnvironmentalKnowledgeIndex(new EnvironmentalKnowledgeProperties(directory, 8, 400, 50, false),
                new PageTextChunker());
        var status = index.reindex();
        var legalHits = index.search("highway environmental clearance forest land", 5, false);
        var sampleHits = index.search("imaginary tiger population", 5, false);
        var includedSample = index.search("imaginary tiger population", 5, true);
        var misnamedAir = index.search("biological diversity rules", 5, false);

        assertEquals(3, status.documentCount());
        assertFalse(legalHits.isEmpty());
        assertEquals(1, legalHits.getFirst().pageNumber());
        assertTrue(legalHits.getFirst().chunkId().contains("-P1-C"));
        assertTrue(legalHits.getFirst().evidenceId().startsWith("DOC-"));
        assertTrue(sampleHits.isEmpty());
        assertEquals(EnvironmentalKnowledgeIndex.DocumentClass.SAMPLE_EIA, includedSample.getFirst().documentClass());
        assertTrue(misnamedAir.stream().anyMatch(hit -> hit.sourceTitle().contains("Biological Diversity Rules, 2024")));
    }

    private static void writePdf(Path path, String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                stream.newLineAtOffset(50, 750);
                stream.showText(text);
                stream.endText();
            }
            document.save(path.toFile());
        }
    }
}
