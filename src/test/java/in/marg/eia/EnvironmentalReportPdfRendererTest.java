package in.marg.eia;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentalReportPdfRendererTest {

    @TempDir
    Path directory;

    @Test
    void detectsMissingLibreOfficeBeforeAnyReportDraftingRequest() {
        var renderer = new EnvironmentalReportPdfRenderer(
                new EnvironmentalReportProperties(directory.resolve("template.docx"), directory, 8, "marg-libreoffice-not-installed"));

        var error = assertThrows(java.io.IOException.class, renderer::verifyAvailable);

        assertTrue(error.getMessage().contains("configure MARG_LIBREOFFICE_BIN"));
    }

    @Test
    void reportsMissingLibreOfficeAsDeploymentRequirement() throws Exception {
        Path docx = directory.resolve("report.docx");
        Files.write(docx, new byte[]{1});
        var renderer = new EnvironmentalReportPdfRenderer(
                new EnvironmentalReportProperties(docx, directory, 8, "marg-libreoffice-not-installed"));

        var error = assertThrows(java.io.IOException.class, () -> renderer.render(docx));

        assertTrue(error.getMessage().contains("requires LibreOffice"));
    }
}
