package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EnvironmentalReportValidatorTest {

    private final EnvironmentalReportValidator validator = new EnvironmentalReportValidator();
    private final Set<String> tokens = Set.of("{{environmental_gis_findings}}");

    @Test
    void acceptsClaimsWithStableIdsTypedValuesAndMatchingUnits() {
        var request = new GenerateReportRequest("Route X", "Proponent", "Alternative A", "Assam",
                "v1", "2026-11-14", List.of(gisEvidence()),
                List.of(new ReportClaim("baseline", "{{environmental_gis_findings}}",
                        "GIS screening reports 2.5 km of mapped forest exposure.", List.of("GIS-FOREST-01"),
                        List.of(new NumericValue(new BigDecimal("2.5"), "km")))), null);

        assertTrue(validator.validate(request, tokens).isEmpty());
    }

    @Test
    void validatesRepeatedTableRowEvidenceAndNumericSupport() {
        var row = new ReportTableRow("impact_matrix", java.util.Map.of(
                "phase_component", "Baseline / forest exposure: 2.5 km",
                "pathway_basis", "Mapped route intersection",
                "significance_uncertainty", "Screening-level observation",
                "mitigation_consideration", "Review alignment",
                "evidence_ids", "GIS-FOREST-01"), List.of("MISSING-01"),
                List.of(new NumericValue(new BigDecimal("2.5"), "meters")));
        var request = new GenerateReportRequest("Route X", "Proponent", "Alternative A", "Assam",
                "v1", "2026-11-14", List.of(gisEvidence()),
                List.of(new ReportClaim("baseline", "{{environmental_gis_findings}}", "Mapped forest exposure is screened.",
                        List.of("GIS-FOREST-01"), List.of())), null, List.of(row));

        var issues = validator.validate(request, tokens);
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("UNKNOWN_EVIDENCE_ID")
                && "MISSING-01".equals(issue.evidenceId())));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("UNSUPPORTED_NUMERIC_CLAIM")));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("UNDECLARED_NUMERIC_CLAIM")));
    }

    @Test
    void rejectsUnknownEvidenceIdsUnsupportedNumbersAndMissingDocumentPages() {
        var document = new EvidenceItem("DOC-12", EvidenceType.PROJECT_DOCUMENT_CLAIM,
                "Document describes baseline conditions", "Project report", "report.pdf", 0,
                "CH-03", "Baseline conditions are described.", "page 0", "2025", "India", "Project-specific",
                null, null, null, null, null, List.of(), List.of());
        var request = new GenerateReportRequest("Route X", null, null, "Assam", "v1", "2026-11-14",
                List.of(document, gisEvidence()),
                List.of(new ReportClaim("baseline", "{{environmental_gis_findings}}",
                        "GIS screening reports 2.5 km of mapped forest exposure.", List.of("MISSING-ID"),
                        List.of(new NumericValue(new BigDecimal("2.5"), "meters")))), null);

        var issues = validator.validate(request, tokens);
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("DOCUMENT_PAGE_REQUIRED")));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("UNKNOWN_EVIDENCE_ID")));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("UNSUPPORTED_NUMERIC_CLAIM")));
    }

    @Test
    void convertsExistingMode1GisPayloadIntoCitableReportEvidence() {
        Mode1EnvironmentalSection gis = Mode1EnvironmentalSectionAssembler.assemble(
                java.util.Map.of("profile", "COMPOSITE_12_LAYER"),
                java.util.Map.of("routingResolutionMeters", 250.0,
                        "coordinateReferenceSystem", "EPSG:32646", "searchWindow", "assam-test"),
                java.util.Map.of("diagnosticIsApproximate", true, "sampleSpacingMeters", 125.0,
                        "diagnosticSampleCount", 42, "routeLengthKm", 12.5,
                        "lulcExposureKm", java.util.Map.of("FOREST", 2.5)));
        var finding = gis.findings().getFirst();
        var claim = new ReportClaim("baseline_environment", "{{environmental_gis_findings}}",
                "GIS screening records 2.5 km of mapped forest exposure.", List.of(finding.evidenceId()),
                List.of(new NumericValue(new BigDecimal("2.5"), "km")));
        var request = new GenerateReportRequest("Route X", null, "Alternative A", "Assam", "v1",
                "2026-11-14", List.of(), List.of(claim), gis);

        var assembled = new EnvironmentalReportEvidenceAssembler().assemble(request);
        var issues = validator.validate(assembled, Set.of("{{environmental_methodology}}", "{{environmental_gis_findings}}"));

        assertTrue(assembled.evidence().size() >= gis.findings().size() + 1);
        assertTrue(assembled.claims().size() == 1);
        assertTrue(issues.isEmpty(), () -> issues.toString());
    }

    @Test
    void acceptsStatutoryYearOnlyWhenItIsDeclaredAndSupportedAsSourceMetadata() {
        var legal = new EvidenceItem("DOC-LEGAL-P1-C0", EvidenceType.GUIDANCE_REFERENCE,
                "Official legal source excerpt", "Environment Impact Assessment Notification, 2006", "eia.pdf",
                null, null, "Source excerpt", "page 1, chunk DOC-LEGAL-P1-C0", "official source",
                "India", "Candidate reference; applicability not determined", null, null, null, null, null,
                List.of(), List.of(new NumericValue(new BigDecimal("2006"), "year")));
        var request = new GenerateReportRequest("Route X", "Proponent", "Alternative A", "Assam", "v1", "2026-11-14",
                List.of(legal), List.of(new ReportClaim("legal", "{{legal_applicability_register}}",
                "Environment Impact Assessment Notification, 2006 is a candidate reference.",
                List.of("DOC-LEGAL-P1-C0"), List.of(new NumericValue(new BigDecimal("2006"), "year")))), null);

        assertTrue(validator.validate(request, Set.of("{{legal_applicability_register}}")).isEmpty());
    }

    @Test
    void doesNotTreatPollutantFormulaDigitsAsUncitedNumericFindings() {
        var guidance = new EvidenceItem("GUIDE-AIR-01", EvidenceType.GUIDANCE_REFERENCE,
                "Air quality monitoring guidance", "Air guidance", "air.pdf", null, null,
                "Monitoring parameters include particulate matter and nitrogen dioxide.", "page 1", "2025",
                "India", "Guidance reference", null, null, null, null, null, List.of(), List.of());
        var request = new GenerateReportRequest("Route X", "Proponent", "Alternative A", "Assam", "v1", "2026-11-14",
                List.of(guidance), List.of(new ReportClaim("air", "{{environmental_gis_findings}}",
                "The parameters include PM10, PM2.5 and NO2.", List.of("GUIDE-AIR-01"), List.of())), null);

        assertTrue(validator.validate(request, tokens).isEmpty());
    }

    private EvidenceItem gisEvidence() {
        return new EvidenceItem("GIS-FOREST-01", EvidenceType.GIS_OBSERVATION,
                "Sampled route exposure by mapped land-cover class", "LULC", null, null, null,
                null, "layer: LULC", "2025", "Kaziranga–Karbi Anglong", "Screening only",
                "Bhuvan LULC", "route diagnostic sample", "sampledRouteLengthByClass:FOREST",
                "2.5", "km", List.of("Sampled route exposure is approximate and not surveyed land take."),
                List.of(new NumericValue(new BigDecimal("2.5"), "km")));
    }
}
