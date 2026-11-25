package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnvironmentalReportDocxWriterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void writesEvidenceAndKeepsMapAndApprovalInsertionInstructionsOnly() throws Exception {
        Path template = Path.of("data/templates/mode1-environmental-eia-section-template.docx").toAbsolutePath();
        var properties = new EnvironmentalReportProperties(template, tempDirectory, 8, "libreoffice");
        var writer = new EnvironmentalReportDocxWriter(properties);
        var evidence = new EvidenceItem("GIS-FOREST-01", EvidenceType.GIS_OBSERVATION,
                "Mapped forest-class route exposure", "LULC", null, null, null, null,
                "layer: LULC", "2025", "Assam", "Screening only", "Bhuvan LULC",
                "route diagnostic sample", "sampledRouteLengthByClass:FOREST", "2.5", "km",
                List.of("Approximate screening; not surveyed land take."),
                List.of(new NumericValue(new BigDecimal("2.5"), "km")));
        var claim = new ReportClaim("baseline_environment", "{{environmental_gis_findings}}",
                "GIS screening records 2.5 km of mapped forest exposure.", List.of("GIS-FOREST-01"),
                List.of(new NumericValue(new BigDecimal("2.5"), "km")));
        var request = new GenerateReportRequest("Route X", "Not supplied", "Alternative A", "Assam",
                "v1", "2026-11-14", List.of(evidence), List.of(claim), null, List.of(
                new ReportTableRow("impact_matrix", Map.of("phase_component", "Construction / air quality",
                        "pathway_basis", "Dust from earthworks", "significance_uncertainty", "Potential effect; site verification required",
                        "mitigation_consideration", "Dust suppression subject to design review", "evidence_ids", "GIS-FOREST-01"),
                        List.of("GIS-FOREST-01"), List.of()),
                new ReportTableRow("impact_matrix", Map.of("phase_component", "Operation / noise",
                        "pathway_basis", "Traffic noise", "significance_uncertainty", "Receptor data unavailable",
                        "mitigation_consideration", "Assess sensitive receptors", "evidence_ids", "GIS-FOREST-01"),
                        List.of("GIS-FOREST-01"), List.of()),
                new ReportTableRow("mitigation_plan", Map.of("impact_evidence_id", "GIS-FOREST-01", "action", "Review forest exposure",
                        "phase_location", "Route alignment", "responsible_party", "Project proponent to confirm",
                        "timing_indicator_cost", "Before final design; indicator and cost to be determined"), List.of("GIS-FOREST-01"), List.of()),
                new ReportTableRow("monitoring_plan", Map.of("parameter_receptor", "Mapped forest exposure",
                        "location_method", "Route corridor GIS review", "frequency_phase", "Before construction",
                        "standard_trigger", "Project-specific trigger to be confirmed", "responsibility_reporting", "Proponent to assign"),
                        List.of("GIS-FOREST-01"), List.of())));

        String firstHeading = writer.contentsHeadings().getFirst();
        Path output = writer.write("test-job", request, Map.of(firstHeading, 3));

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(output));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            assertTrue(text.contains("GIS screening records 2.5 km of mapped forest exposure."));
            assertTrue(text.contains("GIS-FOREST-01"));
            assertTrue(text.contains("Dust from earthworks"));
            assertTrue(text.contains("Traffic noise"));
            assertTrue(text.contains("Review forest exposure"));
            assertTrue(text.contains("Mapped forest exposure"));
            assertTrue(text.contains("Mapped forest-class route exposure")); // evidence register row is generated from evidence
            XWPFTable evidenceRegister = document.getTables().stream()
                    .filter(table -> table.getText().contains("Evidence ID / type"))
                    .findFirst().orElseThrow();
            var evidenceRow = evidenceRegister.getRow(1);
            assertTrue(evidenceRow.getCell(1).getText().contains("Mapped forest-class route exposure"));
            assertTrue(evidenceRow.getCell(2).getText().contains("2.5 km"));
            assertFalse(evidenceRow.getCell(1).getText().equals(evidenceRow.getCell(2).getText()),
                    "The excerpt/value column must not duplicate the claim column.");
            assertFalse(evidenceRow.getCell(4).getText().contains("Not supplied"),
                    "Unused GIS method fields must not be expanded into repeated placeholders.");
            String footerXml = document.getFooterList().getFirst().getParagraphs().getFirst().getCTP().xmlText();
            assertEquals(1, footerXml.split("PAGE", -1).length - 1,
                    "The template already contains one dynamic page field; the writer must not append another.");
            assertTrue(text.contains(firstHeading));
            assertTrue(text.contains("3"));
            assertTrue(text.contains("MAP INSERTION POINT"));
            assertTrue(text.contains("APPROVAL DOCUMENT INSERTION POINT"));
            assertFalse(text.contains("WRITER INSTRUCTION:"));
            assertFalse(Pattern.compile("\\{\\{[^{}]+}}")
                    .matcher(text).find(), "all template placeholders should be resolved");
        }
    }

    @Test
    void leavesUnusedCellsBlankInEvidenceAndPlanTables() throws Exception {
        Path template = Path.of("data/templates/mode1-environmental-eia-section-template.docx").toAbsolutePath();
        var writer = new EnvironmentalReportDocxWriter(
                new EnvironmentalReportProperties(template, tempDirectory, 8, "libreoffice"));
        var request = new GenerateReportRequest("Route X", null, "Alternative A", "Assam",
                "v1", "2026-11-14", List.of(), List.of(), null, List.of());
        Path output = writer.write("empty-evidence-job", request);

        try (XWPFDocument document = new XWPFDocument(java.nio.file.Files.newInputStream(output))) {
            XWPFTable monitoring = document.getTables().stream()
                    .filter(table -> table.getText().contains("Parameter / receptor"))
                    .findFirst().orElseThrow();
            var emptyRow = monitoring.getRow(1);
            assertEquals("No evidence-backed entry supplied.", emptyRow.getCell(0).getText().trim());
            for (int index = 1; index < emptyRow.getTableCells().size(); index++) {
                assertTrue(emptyRow.getCell(index).getText().isBlank(),
                        "An unused table cell should remain blank rather than repeat unavailable text.");
            }
        }
    }
}
