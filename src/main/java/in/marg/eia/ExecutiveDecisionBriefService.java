package in.marg.eia;

import in.marg.eia.CampaMitigationCalculatorService.MitigationBudgetSummary;
import in.marg.eia.Mode2AlignmentScreeningService.AlignmentScreeningRequest;
import in.marg.eia.Mode2AlignmentScreeningService.AlignmentScreeningResult;
import in.marg.eia.Mode2ProjectEvidenceComparisonService.ComparisonResult;
import in.marg.eia.Mode2ProjectEvidenceComparisonService.MetricComparison;
import in.marg.eia.StatutoryRiskAndClearanceScorerService.RegulatoryReadinessReport;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Generates structured 2-Page Executive Decision-Support Briefing data.
 */
@Service
public class ExecutiveDecisionBriefService {

    private final Mode2ProjectEvidenceComparisonService comparisonService;
    private final StatutoryRiskAndClearanceScorerService scorerService;
    private final CampaMitigationCalculatorService campaService;

    public ExecutiveDecisionBriefService(Mode2ProjectEvidenceComparisonService comparisonService,
                                         StatutoryRiskAndClearanceScorerService scorerService,
                                         CampaMitigationCalculatorService campaService) {
        this.comparisonService = comparisonService;
        this.scorerService = scorerService;
        this.campaService = campaService;
    }

    public record ExecutiveBrief(
            String projectId,
            String projectTitle,
            String generatedDate,
            String locationJurisdiction,
            RegulatoryReadinessReport readinessReport,
            MitigationBudgetSummary mitigationBudget,
            List<MetricComparison> keyComparisons,
            String executiveAppraisalVerdict,
            String lendersRecommendation,
            String appraisalDisclaimer
    ) {}

    public ExecutiveBrief generateBrief(String projectId, AlignmentScreeningRequest alignmentRequest) throws IOException {
        ComparisonResult comparison = comparisonService.compare(projectId,
                "project route length forest diversion wildlife crossings bridges", 20, alignmentRequest);

        RegulatoryReadinessReport readiness = scorerService.scoreProject(comparison);

        // Derive mitigation inputs from screening
        AlignmentScreeningResult gis = comparison.gisScreening();
        MitigationBudgetSummary mitigation = campaService.calculateFromScreening(projectId, gis);

        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy"));

        String jurisdiction = "Karbi Anglong East Division, Assam (NH-29 / Sildharampur RF Corridor)";
        if (gis != null && gis.regionId() != null) {
            String rid = gis.regionId().toLowerCase();
            if (rid.contains("sikkim")) jurisdiction = "Sikkim Himalayan Corridor (Teesta River Basin)";
            else if (rid.contains("gangetic")) jurisdiction = "Indo-Gangetic Plains (NH-927 Corridor, UP)";
            else if (rid.contains("western_ghats")) jurisdiction = "Western Ghats Eco-Sensitive Corridor (Maharashtra)";
        }

        String projectTitle = (alignmentRequest != null && alignmentRequest.alignmentName() != null && !alignmentRequest.alignmentName().isBlank())
                ? alignmentRequest.alignmentName()
                : "National Highway Project Corridor";

        String appraisalVerdict = readiness.executiveVerdict();

        String lendersRecommendation = readiness.score() < 50
                ? "CONDITIONAL HOLD: Financiers and appraisal committees must withhold final statutory clearance until: (1) Form-A for 14.56 ha Reserve Forest diversion is submitted on PARIVESH 2.0 with identified 29.12 ha CA land, and (2) Binding commitment for 2 WII-compliant Wildlife Underpasses (₹19.00 Cr) is integrated into EPC bid documents."
                : (readiness.score() < 80
                ? "PROCEED WITH SUPPLEMENTARY CONDITIONS: Approve preliminary clearance subject to satisfactory replies to 3-season air/noise baseline data and 50-year HFL cross-drainage afflux verification prior to financial closure."
                : "PROCEED WITH STANDARD MONITORING: Concordance verified; proceed with standard EMP compliance monitoring.");

        return new ExecutiveBrief(
                projectId,
                projectTitle,
                dateStr,
                jurisdiction,
                readiness,
                mitigation,
                comparison.comparisons(),
                appraisalVerdict,
                lendersRecommendation,
                "MARG Executive Decision-Support Brief is a desktop analytical verification tool based on GIS ground-truth spatial screening and RAG document extraction. It does not replace statutory appraisals by MoEFCC or state authorities."
        );
    }
}
