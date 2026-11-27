package in.marg.eia;

import in.marg.eia.EnvironmentalReportModels.EvidenceItem;
import in.marg.eia.EnvironmentalReportModels.EvidenceType;
import in.marg.eia.EnvironmentalReportModels.NumericValue;
import in.marg.eia.Mode2AlignmentScreeningService.AlignmentScreeningResult;
import in.marg.eia.Mode2ProjectEvidenceComparisonService.ComparisonResult;
import in.marg.eia.Mode2ProjectEvidenceComparisonService.MetricComparison;
import in.marg.eia.Mode2ProjectRagService.FactSource;
import in.marg.eia.Mode2ProjectRagService.ProjectFact;
import in.marg.eia.Mode2ProjectRagService.ProjectFactExtraction;
import in.marg.eia.StatutoryRiskAndClearanceScorerService.ClearanceRiskItem;
import in.marg.eia.StatutoryRiskAndClearanceScorerService.RegulatoryReadinessReport;
import in.marg.eia.StatutoryRiskAndClearanceScorerService.RiskSeverity;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatutoryRiskAndClearanceScorerServiceTest {

    private final StatutoryRiskAndClearanceScorerService scorer = new StatutoryRiskAndClearanceScorerService();

    @Test
    void testUnreportedForestDiversionTriggersRedFlagBlocker() {
        // DPR claims 0 ha forest diversion, but GIS detects 5.43 km forest exposure
        ProjectFact forestFact = new ProjectFact("forest_diversion", "0.0 ha", "ha",
                "Zero forest land required",
                List.of("DOC-F01"),
                List.of(new FactSource("DOC-F01", "DPR Ch 4", "dpr.pdf", 42, "c1", "Zero forest land required")));
        ProjectFactExtraction facts = new ProjectFactExtraction("proj-1", "2026-11-27T12:00:00Z", List.of(forestFact),
                List.of(), List.of("DOC-F01"), "OK", "Limitation");

        EvidenceItem forestObs = new EvidenceItem(
                "GIS-FOR-01", EvidenceType.GIS_OBSERVATION, "Forest exposure: 5.43 km", "MARG GIS",
                null, null, null, "Forest exposure: 5.43 km", "lulcExposureKm:FOREST", "v0.3",
                "assam", "Preliminary screening", "lulcExposureKm:FOREST", "Bhuvan LULC",
                "forest_exposure", "5.43", "km", List.of("Forest exposure"),
                List.of(new NumericValue(new BigDecimal("5.43"), "km"))
        );
        AlignmentScreeningResult gis = new AlignmentScreeningResult(
                "proj-1", "Test Project", "Test Alignment", "assam", "Assam Region", "NATIONAL_HIGHWAY",
                10, "fp123", Map.of(), Map.of(), Map.of(), List.of(forestObs), List.of(), List.of(), List.of()
        );

        MetricComparison comp = new MetricComparison(
                "forest_diversion", "forest diversion", "POTENTIAL_INCONSISTENCY",
                "0.0 ha", "ha", BigDecimal.ZERO, "lulcExposureKm:FOREST", "5.43", "km",
                new BigDecimal("5.43"), List.of("DOC-F01"), List.of(), "GIS-FOR-01", "Mismatch"
        );

        ComparisonResult result = new ComparisonResult("proj-1", facts, gis, List.of(comp), List.of(), "Limit");

        RegulatoryReadinessReport report = scorer.scoreProject(result);

        assertNotNull(report);
        assertEquals(RiskSeverity.HIGH_RISK_BLOCKER, report.overallRiskTier());
        assertTrue(report.score() < 50, "Score must drop below 50 due to statutory clearance blocker, was: " + report.score());
        assertFalse(report.redFlags().isEmpty(), "Must contain at least 1 red flag");

        ClearanceRiskItem redFlag = report.redFlags().stream()
                .filter(rf -> rf.id().contains("FCA"))
                .findFirst()
                .orElse(null);
        assertNotNull(redFlag, "Must emit RED-FLAG-FCA-01 for unreported forest diversion");
        assertTrue(redFlag.statutoryProvision().contains("Forest (Conservation) Act"));
        assertTrue(redFlag.requiredAction().contains("PARIVESH 2.0"));
        assertTrue(redFlag.requiredAction().contains("1:2 Compensatory Afforestation"));
    }

    @Test
    void testConcordantAlignmentProducesHigherReadinessScore() {
        ProjectFact forestFact = new ProjectFact("forest_diversion", "14.56 ha", "ha",
                "14.56 ha forest diversion",
                List.of("DOC-F01"),
                List.of(new FactSource("DOC-F01", "DPR Ch 4", "dpr.pdf", 42, "c1", "14.56 ha forest diversion")));
        ProjectFact wlFact = new ProjectFact("wildlife_or_ecological_corridor", "Kaziranga-Karbi Anglong Elephant Reserve buffer", "km",
                "Traverses elephant reserve buffer",
                List.of("DOC-W01"),
                List.of(new FactSource("DOC-W01", "DPR Ch 5", "dpr.pdf", 55, "c2", "Traverses elephant reserve buffer")));
        ProjectFactExtraction facts = new ProjectFactExtraction("proj-2", "2026-11-27T12:00:00Z", List.of(forestFact, wlFact),
                List.of(), List.of("DOC-F01", "DOC-W01"), "OK", "Limitation");

        EvidenceItem forestObs = new EvidenceItem(
                "GIS-FOR-02", EvidenceType.GIS_OBSERVATION, "Forest exposure: 5.43 km", "MARG GIS",
                null, null, null, "Forest exposure: 5.43 km", "lulcExposureKm:FOREST", "v0.3",
                "assam", "Preliminary screening", "lulcExposureKm:FOREST", "Bhuvan LULC",
                "forest_exposure", "5.43", "km", List.of("Forest exposure"),
                List.of(new NumericValue(new BigDecimal("5.43"), "km"))
        );
        AlignmentScreeningResult gis = new AlignmentScreeningResult(
                "proj-2", "Test Project", "Disclosed Alignment", "assam", "Assam Region", "NATIONAL_HIGHWAY",
                10, "fp123", Map.of(), Map.of(), Map.of(), List.of(forestObs), List.of(), List.of(), List.of()
        );

        ComparisonResult result = new ComparisonResult("proj-2", facts, gis, List.of(), List.of(), "Limit");

        RegulatoryReadinessReport report = scorer.scoreProject(result);

        assertNotNull(report);
        assertEquals(RiskSeverity.MODERATE_RISK_QUERY, report.overallRiskTier());
        assertTrue(report.score() >= 50, "Disclosed alignment should score higher than undisclosed blocker");
        assertTrue(report.redFlags().isEmpty(), "No red flags should be present when facts are fully disclosed");
    }
}
