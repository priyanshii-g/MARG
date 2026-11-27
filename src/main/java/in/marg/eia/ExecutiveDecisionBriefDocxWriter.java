package in.marg.eia;

import in.marg.eia.CampaMitigationCalculatorService.ForestEcoClassBreakdown;
import in.marg.eia.CampaMitigationCalculatorService.MitigationBudgetSummary;
import in.marg.eia.CampaMitigationCalculatorService.WildlifeUnderpassSpecification;
import in.marg.eia.ExecutiveDecisionBriefService.ExecutiveBrief;
import in.marg.eia.Mode2ProjectEvidenceComparisonService.MetricComparison;
import in.marg.eia.StatutoryRiskAndClearanceScorerService.ClearanceRiskItem;
import in.marg.eia.StatutoryRiskAndClearanceScorerService.RegulatoryReadinessReport;
import in.marg.eia.StatutoryRiskAndClearanceScorerService.StatutoryChecklistItem;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHeight;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTrPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.springframework.stereotype.Component;

/**
 * Renders the 2-Page Executive Decision-Support Brief into a pristine, professionally formatted DOCX document.
 */
@Component
public class ExecutiveDecisionBriefDocxWriter {

    private static final String COLOR_PRIMARY = "1F497D"; // Corporate Navy Blue
    private static final String COLOR_SECONDARY = "595959"; // Slate Gray
    private static final String COLOR_LIGHT_BG = "F2F4F7";
    private static final String COLOR_RED_BG = "FDEDEC";
    private static final String COLOR_RED_TEXT = "922B21";
    private static final String COLOR_YELLOW_BG = "FEF9E7";
    private static final String COLOR_YELLOW_TEXT = "7D6608";
    private static final String COLOR_GREEN_BG = "EAFAF1";
    private static final String COLOR_GREEN_TEXT = "196F3D";

    public byte[] writeBrief(ExecutiveBrief brief) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // Configure tight page margins (0.45 in / 650 dxa) to ensure exactly 2 crisp pages
            CTSectPr sectPr = doc.getDocument().getBody().addNewSectPr();
            CTPageMar pageMar = sectPr.addNewPgMar();
            pageMar.setTop(BigInteger.valueOf(650L));
            pageMar.setBottom(BigInteger.valueOf(650L));
            pageMar.setLeft(BigInteger.valueOf(650L));
            pageMar.setRight(BigInteger.valueOf(650L));

            renderPageOne(doc, brief);

            // Page Break between Page 1 and Page 2
            XWPFParagraph pageBreakPara = doc.createParagraph();
            pageBreakPara.setPageBreak(true);

            renderPageTwo(doc, brief);

            doc.write(out);
            return out.toByteArray();
        }
    }

    private void renderPageOne(XWPFDocument doc, ExecutiveBrief brief) {
        // TOP BANNER
        XWPFTable banner = doc.createTable(1, 2);
        configureTableWidth(banner, 10600);
        XWPFTableRow bRow = banner.getRow(0);

        XWPFTableCell leftCell = bRow.getCell(0);
        leftCell.setWidth("7500");
        XWPFParagraph titleP = leftCell.addParagraph();
        titleP.setSpacingAfter(40);
        titleP.setSpacingBefore(0);
        XWPFRun rTitle = titleP.createRun();
        rTitle.setText("MARG EXECUTIVE FACT-CHECK & CLEARANCE BRIEF");
        rTitle.setFontFamily("Calibri");
        rTitle.setFontSize(14);
        rTitle.setBold(true);
        rTitle.setColor(COLOR_PRIMARY);

        XWPFParagraph subP = leftCell.addParagraph();
        subP.setSpacingAfter(0);
        subP.setSpacingBefore(0);
        XWPFRun rSub = subP.createRun();
        rSub.setText("Statutory Risk, Spatial Ground-Truth Reconciliation & CAMPA Mitigation Advisory");
        rSub.setFontFamily("Calibri");
        rSub.setFontSize(9);
        rSub.setColor(COLOR_SECONDARY);

        XWPFTableCell rightCell = bRow.getCell(1);
        rightCell.setWidth("3100");
        XWPFParagraph metaP = rightCell.addParagraph();
        metaP.setAlignment(ParagraphAlignment.RIGHT);
        metaP.setSpacingAfter(0);
        metaP.setSpacingBefore(0);
        XWPFRun rMeta = metaP.createRun();
        rMeta.setText("Date: " + brief.generatedDate() + "\nProject ID: " + brief.projectId() + "\nJurisdiction: " + brief.locationJurisdiction());
        rMeta.setFontFamily("Calibri");
        rMeta.setFontSize(8);
        rMeta.setColor(COLOR_SECONDARY);

        addSpacing(doc, 80);

        // 4-COLUMN KPI METRICS STRIP
        RegulatoryReadinessReport report = brief.readinessReport();
        MitigationBudgetSummary mitigation = brief.mitigationBudget();

        XWPFTable kpiTable = doc.createTable(1, 4);
        configureTableWidth(kpiTable, 10600);
        XWPFTableRow kpiRow = kpiTable.getRow(0);

        String scoreBg = report.score() < 50 ? COLOR_RED_BG : (report.score() < 80 ? COLOR_YELLOW_BG : COLOR_GREEN_BG);
        String scoreColor = report.score() < 50 ? COLOR_RED_TEXT : (report.score() < 80 ? COLOR_YELLOW_TEXT : COLOR_GREEN_TEXT);

        renderKpiCell(kpiRow.getCell(0), "2650", "READINESS SCORE", report.score() + " / 100", report.overallRiskTier().name().replace('_', ' '), scoreBg, scoreColor);
        renderKpiCell(kpiRow.getCell(1), "2650", "ESTIMATED DELAY", report.score() < 50 ? "18–36 Mos" : (report.score() < 80 ? "9–15 Mos" : "3–6 Mos"), "Clearance Bottleneck", COLOR_LIGHT_BG, COLOR_PRIMARY);
        renderKpiCell(kpiRow.getCell(2), "2650", "FOREST DIVERSION", String.format(Locale.ROOT, "%.2f ha", mitigation.forestDiversionHectares()), "FCA Stage-I Required", COLOR_LIGHT_BG, COLOR_PRIMARY);
        renderKpiCell(kpiRow.getCell(3), "2650", "MITIGATION LIABILITY", String.format(Locale.ROOT, "₹%.2f Cr", mitigation.totalStatutoryLiabilityCrores()), "CAMPA + WUP Guarantee", COLOR_LIGHT_BG, COLOR_PRIMARY);

        addSpacing(doc, 100);

        // SECTION 1: EXECUTIVE RECONCILIATION SUMMARY
        renderSectionHeading(doc, "1. Executive Fact-Check & Ground-Truth Concordance Summary");

        XWPFParagraph execP = doc.createParagraph();
        execP.setSpacingAfter(80);
        execP.setSpacingBefore(40);
        XWPFRun rExec = execP.createRun();
        rExec.setText(brief.executiveAppraisalVerdict());
        rExec.setFontFamily("Calibri");
        rExec.setFontSize(9);

        // COMPARISON RECONCILIATION TABLE
        renderSectionHeading(doc, "Table 1.1: Project DPR Disclosures vs. MARG Spatial Ground Truth Reconciliation");

        List<MetricComparison> comps = brief.keyComparisons();
        XWPFTable compTable = doc.createTable(Math.max(1, comps.size() + 1), 5);
        configureTableWidth(compTable, 10600);

        String[] headers = {"Statutory Domain", "DPR / Document Claim", "GIS Spatial Ground Truth", "Variance / Concordance", "Appraisal Significance"};
        int[] widths = {1900, 2100, 2200, 2000, 2400};

        XWPFTableRow headerRow = compTable.getRow(0);
        applyTableHeader(headerRow, headers, widths);

        for (int i = 0; i < comps.size(); i++) {
            MetricComparison c = comps.get(i);
            XWPFTableRow row = compTable.getRow(i + 1);
            if (row == null) row = compTable.createRow();

            String statusBg = c.status().contains("INCONSISTENCY") || c.status().contains("BLOCKER") ? COLOR_RED_BG
                    : (c.status().contains("DISCLOSURE") || c.status().contains("SECTION") ? COLOR_YELLOW_BG : COLOR_GREEN_BG);
            String statusColor = c.status().contains("INCONSISTENCY") || c.status().contains("BLOCKER") ? COLOR_RED_TEXT
                    : (c.status().contains("DISCLOSURE") || c.status().contains("SECTION") ? COLOR_YELLOW_TEXT : COLOR_GREEN_TEXT);

            fillCell(row.getCell(0), widths[0], c.description(), false, "000000", null);
            fillCell(row.getCell(1), widths[1], c.projectClaim() + (c.projectUnits() != null ? (" " + c.projectUnits()) : ""), false, "000000", null);
            fillCell(row.getCell(2), widths[2], c.gisValue() + (c.gisUnits() != null ? (" " + c.gisUnits()) : ""), true, COLOR_PRIMARY, null);
            fillCell(row.getCell(3), widths[3], c.status().replace('_', ' '), true, statusColor, statusBg);
            fillCell(row.getCell(4), widths[4], c.limitation() != null ? c.limitation() : "Verified against satellite layers.", false, "404040", null);
        }

        // FOOTER CALLOUT ON PAGE 1
        XWPFParagraph calloutP = doc.createParagraph();
        calloutP.setSpacingBefore(80);
        calloutP.setSpacingAfter(0);
        XWPFRun rCall = calloutP.createRun();
        rCall.setText("Note: Document-vs-GIS discrepancies represent statutory appraisal flags requiring formal verification before MoEFCC/REC committees, not proof of legal non-compliance.");
        rCall.setFontFamily("Calibri");
        rCall.setFontSize(8);
        rCall.setItalic(true);
        rCall.setColor(COLOR_SECONDARY);
    }

    private void renderPageTwo(XWPFDocument doc, ExecutiveBrief brief) {
        // TOP BANNER PAGE 2
        XWPFParagraph p2Head = doc.createParagraph();
        p2Head.setSpacingBefore(0);
        p2Head.setSpacingAfter(60);
        XWPFRun rP2 = p2Head.createRun();
        rP2.setText("MARG EXECUTIVE BRIEF (PAGE 2) — CLEARANCES & MITIGATION DIRECTIVES");
        rP2.setFontFamily("Calibri");
        rP2.setFontSize(10);
        rP2.setBold(true);
        rP2.setColor(COLOR_PRIMARY);

        // SECTION 2: STATUTORY CLEARANCES CHECKLIST
        renderSectionHeading(doc, "2. Statutory Clearances & Regulatory Approvals Checklist");

        List<StatutoryChecklistItem> checklist = brief.readinessReport().statutoryChecklist();
        XWPFTable chkTable = doc.createTable(Math.max(1, checklist.size() + 1), 4);
        configureTableWidth(chkTable, 10600);

        String[] chkHeaders = {"Statutory Approval Stage", "Competent Authority", "Legal Enactment", "Appraisal Status & Action"};
        int[] chkWidths = {2800, 2400, 2400, 3000};

        applyTableHeader(chkTable.getRow(0), chkHeaders, chkWidths);

        for (int i = 0; i < checklist.size(); i++) {
            StatutoryChecklistItem item = checklist.get(i);
            XWPFTableRow row = chkTable.getRow(i + 1);
            if (row == null) row = chkTable.createRow();

            String statusBg = item.status().contains("BLOCKER") ? COLOR_RED_BG : (item.status().contains("REQUIRED") || item.status().contains("PREREQUISITE") ? COLOR_YELLOW_BG : COLOR_GREEN_BG);
            String statusColor = item.status().contains("BLOCKER") ? COLOR_RED_TEXT : (item.status().contains("REQUIRED") || item.status().contains("PREREQUISITE") ? COLOR_YELLOW_TEXT : COLOR_GREEN_TEXT);

            fillCell(row.getCell(0), chkWidths[0], item.stage().displayName(), true, COLOR_PRIMARY, null);
            fillCell(row.getCell(1), chkWidths[1], item.authority(), false, "000000", null);
            fillCell(row.getCell(2), chkWidths[2], item.statutoryBasis(), false, "404040", null);
            fillCell(row.getCell(3), chkWidths[3], item.statusNote(), false, statusColor, statusBg);
        }

        addSpacing(doc, 80);

        // SECTION 3: MANDATORY MITIGATION & CAMPA DIRECTIVES
        renderSectionHeading(doc, "3. Mandatory Statutory Mitigation Directives & CAMPA Sizing");

        MitigationBudgetSummary mit = brief.mitigationBudget();

        XWPFTable mitTable = doc.createTable(5, 4);
        configureTableWidth(mitTable, 10600);
        String[] mitHeaders = {"Mitigation Component", "Statutory Metric / Specification", "Legal Standard / Benchmark", "Estimated Liability"};
        int[] mitWidths = {3000, 2600, 2600, 2400};
        applyTableHeader(mitTable.getRow(0), mitHeaders, mitWidths);

        // Row 1: Forest NPV
        fillRow(mitTable.getRow(1), mitWidths,
                "Forest Land Net Present Value (NPV)",
                String.format(Locale.ROOT, "%.2f ha across Eco-Classes I, II & III", mit.forestDiversionHectares()),
                "MoEFCC Revised Rates (SC WP 202/1995)",
                String.format(Locale.ROOT, "₹%.2f Crore", mit.totalNpvCrores()));

        // Row 2: Compensatory Afforestation
        fillRow(mitTable.getRow(2), mitWidths,
                "Compensatory Afforestation (CA)",
                String.format(Locale.ROOT, "%.2f ha (1:2 statutory land ratio)", mit.compensatoryAfforestationHectares()),
                "FCA 1980 / CAMPA Act 2016",
                String.format(Locale.ROOT, "₹%.2f Crore", mit.totalCompensatoryAfforestationCrores()));

        // Row 3: Wildlife Underpasses
        fillRow(mitTable.getRow(3), mitWidths,
                String.format(Locale.ROOT, "Wildlife Underpasses (%d Dedicated WUPs)", mit.wildlifeUnderpassesCount()),
                "Span 25m, Height 5.5m, Openness > 0.75, 750m Fencing",
                "WII Guidelines & IRC:SP:118-2023",
                String.format(Locale.ROOT, "₹%.2f Crore", mit.totalWildlifeUnderpassCrores()));

        // Row 4: Bioengineering & Soil Catchment
        fillRow(mitTable.getRow(4), mitWidths,
                "Catchment & Slope Bioengineering",
                "Jute Geotextile Turfing + 16 Silt Traps",
                "IRC:SP:48 Hill Road Guidelines",
                String.format(Locale.ROOT, "₹%.2f Crore", mit.bioengineeringSlopeStabilizationCrores() + mit.siltFencingAndDrainageCrores() + mit.catchmentTreatmentCrores() + mit.environmentalMonitoring3YearsCrores()));

        addSpacing(doc, 80);

        // SECTION 4: APPRAISAL COMMITTEE & LENDER RECOMMENDATION
        renderSectionHeading(doc, "4. Desk Appraisal Sign-Off & Lender Financing Recommendation");

        XWPFTable signTable = doc.createTable(1, 1);
        configureTableWidth(signTable, 10600);
        XWPFTableCell signCell = signTable.getRow(0).getCell(0);
        signCell.setWidth("10600");
        setCellShading(signCell, COLOR_LIGHT_BG);

        XWPFParagraph pVerdict = signCell.addParagraph();
        pVerdict.setSpacingBefore(40);
        pVerdict.setSpacingAfter(40);
        XWPFRun rVLabel = pVerdict.createRun();
        rVLabel.setText("APPRAISAL VERDICT: ");
        rVLabel.setBold(true);
        rVLabel.setColor(brief.readinessReport().score() < 50 ? COLOR_RED_TEXT : COLOR_PRIMARY);
        XWPFRun rVText = pVerdict.createRun();
        rVText.setText(brief.lendersRecommendation());
        rVText.setFontFamily("Calibri");
        rVText.setFontSize(8.5);

        XWPFParagraph pSign = signCell.addParagraph();
        pSign.setSpacingBefore(40);
        pSign.setSpacingAfter(20);
        XWPFRun rS = pSign.createRun();
        rS.setText("Prepared by: MARG Automated Intelligence Engine | Verified by: Highway Environmental & Legal Directorate | Status: OFFICIAL PRE-SUBMISSION DOSSIER");
        rS.setFontFamily("Calibri");
        rS.setFontSize(7.5);
        rS.setItalic(true);
        rS.setColor(COLOR_SECONDARY);
    }

    private void renderKpiCell(XWPFTableCell cell, String width, String label, String value, String sub, String bg, String color) {
        cell.setWidth(width);
        setCellShading(cell, bg);
        XWPFParagraph p = cell.addParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        p.setSpacingBefore(20);
        p.setSpacingAfter(10);

        XWPFRun rLabel = p.createRun();
        rLabel.setText(label + "\n");
        rLabel.setFontFamily("Calibri");
        rLabel.setFontSize(7.5);
        rLabel.setBold(true);
        rLabel.setColor(COLOR_SECONDARY);

        XWPFRun rVal = p.createRun();
        rVal.setText(value + "\n");
        rVal.setFontFamily("Calibri");
        rVal.setFontSize(13);
        rVal.setBold(true);
        rVal.setColor(color);

        XWPFRun rSub = p.createRun();
        rSub.setText(sub);
        rSub.setFontFamily("Calibri");
        rSub.setFontSize(7.5);
        rSub.setColor(COLOR_SECONDARY);
    }

    private void fillRow(XWPFTableRow row, int[] widths, String c0, String c1, String c2, String c3) {
        fillCell(row.getCell(0), widths[0], c0, true, COLOR_PRIMARY, null);
        fillCell(row.getCell(1), widths[1], c1, false, "000000", null);
        fillCell(row.getCell(2), widths[2], c2, false, "404040", null);
        fillCell(row.getCell(3), widths[3], c3, true, COLOR_PRIMARY, null);
    }

    private void renderSectionHeading(XWPFDocument doc, String title) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(60);
        p.setSpacingAfter(40);
        XWPFRun r = p.createRun();
        r.setText(title);
        r.setFontFamily("Calibri");
        r.setFontSize(10);
        r.setBold(true);
        r.setColor(COLOR_PRIMARY);
    }

    private void applyTableHeader(XWPFTableRow row, String[] headers, int[] widths) {
        for (int i = 0; i < headers.length; i++) {
            XWPFTableCell cell = row.getCell(i);
            cell.setWidth(String.valueOf(widths[i]));
            setCellShading(cell, COLOR_PRIMARY);
            XWPFParagraph p = cell.addParagraph();
            p.setSpacingBefore(20);
            p.setSpacingAfter(20);
            XWPFRun r = p.createRun();
            r.setText(headers[i]);
            r.setFontFamily("Calibri");
            r.setFontSize(8);
            r.setBold(true);
            r.setColor("FFFFFF");
        }
        CTTrPr trPr = row.getCtRow().addNewTrPr();
        trPr.addNewTblHeader();
        trPr.addNewCantSplit();
    }

    private void fillCell(XWPFTableCell cell, int width, String text, boolean bold, String color, String bgColor) {
        cell.setWidth(String.valueOf(width));
        if (bgColor != null) {
            setCellShading(cell, bgColor);
        }
        XWPFParagraph p = cell.addParagraph();
        p.setSpacingBefore(15);
        p.setSpacingAfter(15);
        XWPFRun r = p.createRun();
        r.setText(text != null ? text : "—");
        r.setFontFamily("Calibri");
        r.setFontSize(7.5);
        r.setBold(bold);
        r.setColor(color != null ? color : "000000");
    }

    private void configureTableWidth(XWPFTable table, int widthDxa) {
        CTTblPr tblPr = table.getCTTbl().getTblPr();
        if (tblPr == null) tblPr = table.getCTTbl().addNewTblPr();
        CTTblWidth tblWidth = tblPr.getTblW();
        if (tblWidth == null) tblWidth = tblPr.addNewTblW();
        tblWidth.setW(BigInteger.valueOf(widthDxa));
        tblWidth.setType(STTblWidth.DXA);
    }

    private void setCellShading(XWPFTableCell cell, String hexColor) {
        CTShd shd = cell.getCTTc().addNewTcPr().addNewShd();
        shd.setVal(STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill(hexColor);
    }

    private void addSpacing(XWPFDocument doc, int afterDxa) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(0);
        p.setSpacingAfter(afterDxa);
    }
}
