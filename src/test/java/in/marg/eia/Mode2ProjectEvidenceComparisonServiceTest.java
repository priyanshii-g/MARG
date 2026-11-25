package in.marg.eia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.marg.eia.EnvironmentalReportModels.EvidenceItem;
import in.marg.eia.Mode2ProjectRagService.FactSource;
import in.marg.eia.Mode2ProjectRagService.ProjectFact;
import in.marg.eia.Mode2ProjectRagService.ProjectFactExtraction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Mode2ProjectEvidenceComparisonServiceTest {

    @Test
    void comparesOnlyComparableMetricsAndPreservesBothEvidenceSources() {
        var assembler = new Mode2GisEvidenceAssembler();
        List<EvidenceItem> evidence = assembler.assemble("M2P-1234567890ABCDEF1234567890ABCDEF", "assam",
                "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF",
                Map.of("routeLengthKm", 11.1,
                        "drainageAndWetlandSummary", Map.of("totalWaterCrossingsCount", 4)));
        var gis = new Mode2AlignmentScreeningService.AlignmentScreeningResult(
                "M2P-1234567890ABCDEF1234567890ABCDEF", "Test project", "alignment", "assam", "Assam",
                "NATIONAL_HIGHWAY", 2, "0123456789ABCDEF", Map.of(), Map.of(), Map.of(), evidence,
                List.of("DRAINAGE_AND_WETLANDS"), List.of(), List.of());
        var lengthSource = new FactSource("DOC-LENGTH", "DPR", "DPR.pdf", 7, "DPR-p7-c1", "The project length is 12 km.");
        var crossingsSource = new FactSource("DOC-CROSS", "DPR", "DPR.pdf", 12, "DPR-p12-c1", "The inventory lists 4 water crossings.");
        ProjectFactExtraction facts = new ProjectFactExtraction(
                "M2P-1234567890ABCDEF1234567890ABCDEF", "now",
                List.of(
                        new ProjectFact("project_length", "12", "km", "The project length is 12 km.",
                                List.of("DOC-LENGTH"), List.of(lengthSource)),
                        new ProjectFact("water_crossings", "4 water crossings", null, "The inventory lists 4 water crossings.",
                                List.of("DOC-CROSS"), List.of(crossingsSource)),
                        new ProjectFact("bridges_or_culverts", "2", "count", "Two bridges.",
                                List.of("DOC-STRUCT"), List.of())),
                List.of(), List.of("DOC-LENGTH", "DOC-CROSS", "DOC-STRUCT"), "EXTRACTED", "test");
        var service = new Mode2ProjectEvidenceComparisonService(null, null);

        var result = service.compareFacts(facts, gis);

        assertEquals(3, result.size());
        assertEquals("POTENTIAL_INCONSISTENCY_FOR_REVIEW", result.get(0).status());
        assertEquals(new BigDecimal("0.9"), result.get(0).absoluteDifference());
        assertEquals("DOC-LENGTH", result.get(0).projectSources().get(0).evidenceId());
        assertNotNull(result.get(0).gisEvidenceId());
        assertEquals("NO_NUMERIC_DIFFERENCE_DETECTED", result.get(1).status());
        assertEquals("GIS_METRIC_NOT_AVAILABLE", result.get(2).status());
        assertTrue(result.stream().anyMatch(item -> item.limitation().contains("may use different")
                || item.limitation().contains("may differ")));
    }
}
