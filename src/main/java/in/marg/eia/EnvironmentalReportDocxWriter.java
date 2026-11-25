package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STFldCharType;
import org.springframework.stereotype.Component;

/** Populates the controlled EIA DOCX template with validated, evidence-linked content. */
@Component
public class EnvironmentalReportDocxWriter {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{[a-zA-Z0-9_.-]+}}");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\s*\\d{1,2}(?:\\.\\d{1,2})*\\.\\s+.+");
    private static final String UNAVAILABLE = "Not available in the supplied project records or evidence; verification required.";
    private static final Set<String> REPEATABLE_TABLE_TOKENS = Set.of("{{phase_component}}", "{{pathway_basis}}",
            "{{significance_uncertainty}}", "{{mitigation_consideration}}", "{{evidence_ids}}",
            "{{impact_evidence_id}}", "{{action}}", "{{phase_location}}", "{{responsible_party}}",
            "{{timing_indicator_cost}}", "{{parameter_receptor}}", "{{location_method}}",
            "{{frequency_phase}}", "{{standard_trigger}}", "{{responsibility_reporting}}",
            "{{evidence_id}}", "{{evidence_type}}", "{{claim}}", "{{value_excerpt}}",
            "{{source_locator}}", "{{method_units_limits}}");
    private static final String[] METADATA_TOKENS = {
            "{{project_name}}", "{{project_proponent}}", "{{route_alternative_name}}", "{{study_area}}",
            "{{report_version}}", "{{assessment_date}}", "{{assessment_status}}", "{{assessment_scope_statement}}"
    };

    private final EnvironmentalReportProperties properties;

    public EnvironmentalReportDocxWriter(EnvironmentalReportProperties properties) {
        this.properties = properties;
    }

    public Set<String> templateTokens() throws IOException {
        try (InputStream input = Files.newInputStream(properties.templatePath());
             XWPFDocument document = new XWPFDocument(input)) {
            return tokensIn(document);
        }
    }

    public Path write(String jobId, GenerateReportRequest request) throws IOException {
        return write(jobId, request, Map.of());
    }

    public Path write(String jobId, GenerateReportRequest request, Map<String, Integer> headingPages) throws IOException {
        Path template = properties.templatePath().toAbsolutePath().normalize();
        if (!Files.isRegularFile(template)) {
            throw new IOException("Configured environmental report template does not exist: " + template);
        }
        Path outputDirectory = properties.outputDirectory().toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve("marg-environmental-eia-" + jobId + ".docx").normalize();
        if (!output.startsWith(outputDirectory)) {
            throw new IOException("Generated report path escaped the configured output directory.");
        }

        Map<String, String> values = buildValues(request, headingPages);
        try (InputStream input = Files.newInputStream(template);
             XWPFDocument document = new XWPFDocument(input)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                populateParagraph(paragraph, values);
            }
            for (XWPFTable table : document.getTables()) {
                populateTable(table, values, request);
            }
            for (var header : document.getHeaderList()) {
                populateBody(header, values);
            }
            for (var footer : document.getFooterList()) {
                populateBody(footer, values);
                addPageNumber(footer);
            }
            try (OutputStream outputStream = Files.newOutputStream(output)) {
                document.write(outputStream);
            }
        }
        return output;
    }

    private Map<String, String> buildValues(GenerateReportRequest request, Map<String, Integer> headingPages) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("{{project_name}}", valueOrUnavailable(request.projectName()));
        values.put("{{project_proponent}}", valueOrUnavailable(request.projectProponent()));
        values.put("{{route_alternative_name}}", valueOrUnavailable(request.routeAlternativeName()));
        values.put("{{study_area}}", valueOrUnavailable(request.studyArea()));
        values.put("{{report_version}}", valueOrUnavailable(request.reportVersion()));
        values.put("{{assessment_date}}", valueOrUnavailable(request.assessmentDate()));
        values.put("{{assessment_status}}", "Preliminary evidence-grounded environmental report draft — human review required");
        values.put("{{assessment_scope_statement}}",
                "This report is a preliminary desktop decision-support document. It is not a statutory EIA, environmental clearance, field survey or regulatory decision.");
        values.put("{{applicability_status}}", "Not determined; project-specific legal review required.");
        values.put("{{official_source_version}}", "Current official source version must be verified before reliance.");
        values.put("{{legal_evidence_action}}", "Verify project trigger, current law and competent authority; no approval is implied.");
        values.put("{{generated_table_of_contents}}", tableOfContentsFromTemplate(headingPages));

        Map<String, List<ReportClaim>> claimsByToken = new LinkedHashMap<>();
        request.claims().forEach(claim -> claimsByToken.computeIfAbsent(claim.templateToken(), ignored -> new ArrayList<>()).add(claim));
        claimsByToken.forEach((token, claims) -> values.put(token, claims.stream()
                .map(this::renderClaim)
                .reduce((left, right) -> left + "\n" + right)
                .orElseGet(() -> unavailableForSection(token, request.evidence()))));

        values.put("{{environmental_evidence_register}}", evidenceRegister(request.evidence()));
        values.put("{{legal_and_knowledge_references}}", references(request.evidence(), EvidenceType.GUIDANCE_REFERENCE));
        values.put("{{project_and_gis_references}}", projectAndGisReferences(request.evidence()));
        for (String token : templateTokens()) {
            if (!REPEATABLE_TABLE_TOKENS.contains(token)) {
                values.putIfAbsent(token, unavailableForSection(token, request.evidence()));
            }
        }
        return values;
    }

    public List<String> contentsHeadings() throws IOException {
        try (InputStream input = Files.newInputStream(properties.templatePath());
             XWPFDocument document = new XWPFDocument(input)) {
            List<String> chapters = new ArrayList<>();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText().trim();
                if (NUMBERED_HEADING.matcher(text).matches()) {
                    chapters.add(text);
                }
            }
            return List.copyOf(chapters);
        }
    }

    private String tableOfContentsFromTemplate(Map<String, Integer> headingPages) {
        try {
            List<String> headings = contentsHeadings();
            if (headings.isEmpty()) return "Contents will be finalized after page layout.";
            return headings.stream().map(heading -> {
                Integer page = headingPages.get(heading);
                String pageText = page == null ? "page pending" : page.toString();
                int leaderCount = Math.max(5, 72 - heading.length() - pageText.length());
                return heading + " " + ".".repeat(leaderCount) + " " + pageText;
            }).reduce((left, right) -> left + "\n" + right).orElse("Contents will be finalized after page layout.");
        } catch (IOException ex) {
            return "Contents will be finalized after page layout.";
        }
    }

    private String evidenceRegister(List<EvidenceItem> evidence) {
        if (evidence.isEmpty()) {
            return UNAVAILABLE;
        }
        return evidence.stream().map(this::compactEvidenceEntry).reduce((left, right) -> left + "\n" + right).orElse(UNAVAILABLE);
    }

    private String compactEvidenceEntry(EvidenceItem item) {
        String id = "[" + item.evidenceId() + "] " + item.type();
        if (item.type() == EvidenceType.GIS_OBSERVATION) {
            return id + " — " + compact(joinPresent(item.gisLayer(), item.metric()), 100)
                    + ": " + compact(joinPresent(item.value(), item.units()), 60)
                    + " — method: " + compact(item.operation(), 110)
                    + " — limitations: " + compact(String.join("; ", item.limitations()), 120);
        }
        String source = joinPresent(item.sourceTitle(), item.sourceFileName(), evidenceLocator(item));
        if (item.type() == EvidenceType.GUIDANCE_REFERENCE) {
            return id + " — " + compact(source, 150) + " — version/jurisdiction: "
                    + compact(joinPresent(item.sourceVersion(), item.jurisdiction()), 100)
                    + " — applicability: " + compact(item.applicability(), 120)
                    + " — locator: " + compact(item.sourceLocator(), 100);
        }
        return id + " — " + compact(evidenceRegisterClaim(item), 150) + " — source: " + compact(source, 150)
                + " — excerpt: " + compact(evidenceRegisterExcerpt(item), 180)
                + " — limitations: " + compact(String.join("; ", item.limitations()), 100);
    }

    private String unavailableForSection(String token, List<EvidenceItem> evidence) {
        String topic = sectionTopicLabel(token);
        String search = topic.toLowerCase(java.util.Locale.ROOT);
        boolean hasGis = evidence.stream().anyMatch(item -> item.type() == EvidenceType.GIS_OBSERVATION
                && evidenceSearchText(item).contains(search));
        boolean hasProjectRecord = evidence.stream().anyMatch(item ->
                (item.type() == EvidenceType.PROJECT_DOCUMENT_CLAIM || item.type() == EvidenceType.USER_PROVIDED_PROJECT_INPUT)
                        && evidenceSearchText(item).contains(search));
        if (hasGis) {
            return "GIS screening observations relevant to " + topic + " are listed in the evidence register, but no validated narrative was produced for this section. Interpret them within their stated limitations and verify them through project-specific studies.";
        }
        if (hasProjectRecord) {
            return "Supplied project records mention " + topic + ", but the available material does not support a complete assessment in this section. Verify the source details and supplement them with relevant project studies.";
        }
        return "No " + topic + "-specific evidence was supplied for this section. Obtain relevant project records, authoritative datasets or field studies before drawing conclusions.";
    }

    private String sectionTopicLabel(String token) {
        String name = token.replace("{{", "").replace("}}", "").replace('_', ' ');
        if (name.contains("land use")) return "land use and land cover";
        if (name.contains("water") || name.contains("drainage") || name.contains("flood")) return "water and drainage";
        if (name.contains("soil") || name.contains("terrain") || name.contains("geology")) return "soil, terrain and geohazards";
        if (name.contains("biological") || name.contains("ecolog") || name.contains("wildlife")) return "biological environment and ecological connectivity";
        if (name.contains("settlement") || name.contains("livelihood") || name.contains("receptor")) return "settlements and environmental receptors";
        if (name.contains("air")) return "ambient air quality";
        if (name.contains("noise")) return "noise and vibration";
        if (name.contains("climate")) return "climate and micrometeorology";
        if (name.contains("legal") || name.contains("approval") || name.contains("statutory")) return "legal applicability and approvals";
        if (name.contains("mitigation") || name.contains("monitoring") || name.contains("emp")) return "environmental management measures";
        if (name.contains("impact") || name.contains("risk")) return "potential environmental impacts and risks";
        if (name.contains("project need")) return "project need and assessment objectives";
        if (name.contains("project components")) return "project components and activities";
        if (name.contains("project phases")) return "project phases and schedule";
        if (name.contains("route")) return "the route alternative";
        if (name.contains("method") || name.contains("sampling")) return "assessment methods and sampling design";
        if (name.contains("summary") || name.contains("findings") || name.contains("recommendations")) return "supported environmental findings";
        return name;
    }

    private String evidenceSearchText(EvidenceItem item) {
        return joinPresent(item.claim(), item.metric(), item.gisLayer(), item.sourceTitle(), item.sourceFileName(), item.excerpt())
                .toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private String references(List<EvidenceItem> evidence, EvidenceType type) {
        List<EvidenceItem> matches = evidence.stream().filter(item -> item.type() == type).toList();
        if (matches.isEmpty()) {
            return UNAVAILABLE;
        }
        return matches.stream().map(item -> "[" + item.evidenceId() + "] " + valueOrUnavailable(item.sourceTitle())
                + " — " + valueOrUnavailable(item.sourceVersion()) + " — jurisdiction: "
                + valueOrUnavailable(item.jurisdiction()) + " — applicability: "
                + valueOrUnavailable(item.applicability()) + " — " + valueOrUnavailable(item.sourceLocator()))
                .reduce((left, right) -> left + "\n" + right).orElse(UNAVAILABLE);
    }

    private String projectAndGisReferences(List<EvidenceItem> evidence) {
        List<EvidenceItem> matches = evidence.stream()
                .filter(item -> item.type() == EvidenceType.PROJECT_DOCUMENT_CLAIM || item.type() == EvidenceType.GIS_OBSERVATION)
                .toList();
        if (matches.isEmpty()) {
            return UNAVAILABLE;
        }
        return matches.stream().map(item -> "[" + item.evidenceId() + "] " + item.type() + " — "
                + (item.type() == EvidenceType.PROJECT_DOCUMENT_CLAIM
                    ? valueOrUnavailable(item.sourceFileName()) + ", page " + item.pageNumber() + ", chunk " + valueOrUnavailable(item.chunkId())
                    : valueOrUnavailable(item.gisLayer()) + ", " + valueOrUnavailable(item.operation())))
                .reduce((left, right) -> left + "\n" + right).orElse(UNAVAILABLE);
    }

    private String renderClaim(ReportClaim claim) {
        return claim.statement().trim() + " [Evidence IDs: " + String.join(", ", claim.evidenceIds()) + "]";
    }

    private void populateBody(IBody body, Map<String, String> values) {
        for (XWPFParagraph paragraph : body.getParagraphs()) {
            populateParagraph(paragraph, values);
        }
        for (XWPFTable table : body.getTables()) {
            populateTable(table, values, null);
        }
    }

    private void addPageNumber(org.apache.poi.xwpf.usermodel.XWPFFooter footer) {
        String footerXml = footer.getParagraphs().stream().map(paragraph -> paragraph.getCTP().xmlText())
                .reduce("", String::concat);
        if (footerXml.matches("(?is).*<[^>]*(?:fldSimple|instrText)[^>]*PAGE.*")) {
            return;
        }
        XWPFParagraph paragraph = footer.getParagraphs().isEmpty()
                ? footer.createParagraph() : footer.getParagraphs().getLast();
        XWPFRun label = paragraph.createRun();
        label.setText("  |  Page ");
        XWPFRun begin = paragraph.createRun();
        begin.getCTR().addNewFldChar().setFldCharType(STFldCharType.BEGIN);
        XWPFRun instruction = paragraph.createRun();
        instruction.getCTR().addNewInstrText().setStringValue(" PAGE ");
        XWPFRun separate = paragraph.createRun();
        separate.getCTR().addNewFldChar().setFldCharType(STFldCharType.SEPARATE);
        paragraph.createRun().setText("1");
        XWPFRun end = paragraph.createRun();
        end.getCTR().addNewFldChar().setFldCharType(STFldCharType.END);
    }

    private void populateTable(XWPFTable table, Map<String, String> values, GenerateReportRequest request) {
        String tableId = tableId(table);
        int prototypeIndex = -1;
        if (tableId != null) {
            for (int i = 1; i < table.getNumberOfRows(); i++) {
                String rowText = rowText(table.getRow(i));
                if (TOKEN.matcher(rowText).find() && TOKEN.matcher(rowText).replaceAll("")
                        .replaceAll("[\\s/|;,:–—-]+", "").isEmpty()) {
                    prototypeIndex = i;
                    break;
                }
            }
        }
        if (prototypeIndex >= 0) {
            XWPFTableRow prototype = table.getRow(prototypeIndex);
            CTRow prototypeXml = (CTRow) prototype.getCtRow().copy();
            List<Map<String, String>> rows = tableRows(tableId, request);
            table.removeRow(prototypeIndex);
            if (rows.isEmpty()) rows = List.of(unavailableRow(tableId));
            for (int offset = 0; offset < rows.size(); offset++) {
                XWPFTableRow copy = new XWPFTableRow((CTRow) prototypeXml.copy(), table);
                table.addRow(copy, prototypeIndex + offset);
                populateRepeatedRow(copy, rows.get(offset));
            }
        }
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                populateBody(cell, values);
            }
        }
    }

    private String tableId(XWPFTable table) {
        String text = table.getRows().stream().map(this::rowText).reduce("", String::concat);
        if (text.contains("{{phase_component}}")) return "impact_matrix";
        if (text.contains("{{timing_indicator_cost}}")) return "mitigation_plan";
        if (text.contains("{{frequency_phase}}")) return "monitoring_plan";
        if (text.contains("{{value_excerpt}}")) return "evidence_register";
        return null;
    }

    private List<Map<String, String>> tableRows(String tableId, GenerateReportRequest request) {
        if (request == null) return List.of();
        if ("evidence_register".equals(tableId)) {
            return request.evidence().stream().map(this::evidenceRegisterRow).toList();
        }
        return request.tableRows().stream().filter(row -> tableId.equals(row.tableId()))
                .map(ReportTableRow::cells).toList();
    }

    private Map<String, String> unavailableRow(String tableId) {
        String first = switch (tableId) {
            case "impact_matrix" -> "phase_component";
            case "mitigation_plan" -> "impact_evidence_id";
            case "monitoring_plan" -> "parameter_receptor";
            default -> "evidence_id";
        };
        return Map.of(first, "No evidence-backed entry supplied.");
    }

    private void populateRepeatedRow(XWPFTableRow row, Map<String, String> cells) {
        for (XWPFTableCell cell : row.getTableCells()) {
            for (XWPFParagraph paragraph : cell.getParagraphs()) {
                Matcher matcher = TOKEN.matcher(paragraph.getText());
                StringBuffer rendered = new StringBuffer();
                while (matcher.find()) {
                    String key = matcher.group().substring(2, matcher.group().length() - 2);
                    matcher.appendReplacement(rendered, Matcher.quoteReplacement(cells.getOrDefault(key, "")));
                }
                matcher.appendTail(rendered);
                setParagraphText(paragraph, rendered.toString());
            }
        }
    }

    private Map<String, String> evidenceRegisterRow(EvidenceItem item) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("evidence_id", joinPresent(item.evidenceId(), item.type() == null ? null : item.type().name()));
        cells.put("claim", compact(evidenceRegisterClaim(item), 100));
        cells.put("value_excerpt", compact(evidenceRegisterExcerpt(item), 90));
        cells.put("source_locator", compact(joinPresent(item.sourceTitle(), item.sourceFileName(), evidenceLocator(item)), 85));
        cells.put("method_units_limits", compact(evidenceRegisterMethod(item), 90));
        return cells;
    }

    private String evidenceRegisterClaim(EvidenceItem item) {
        if (item.type() == EvidenceType.GUIDANCE_REFERENCE) {
            return "Retrieved legal or guidance passage; project applicability is not established.";
        }
        if (item.type() == EvidenceType.GIS_OBSERVATION) {
            String claim = item.claim();
            if (claim != null && !claim.isBlank() && !claim.contains(" = ")) {
                return claim;
            }
            String component = claim == null || claim.isBlank() || !claim.contains(":")
                    ? null : claim.substring(0, claim.indexOf(':')).trim();
            String joined = joinPresent(humanizeGisField(component), humanizeGisField(item.metric()));
            return joined != null && !joined.isBlank() ? joined : (claim != null ? claim : "GIS observation");
        }
        return item.claim();
    }

    private String evidenceRegisterExcerpt(EvidenceItem item) {
        if (item.type() == EvidenceType.GIS_OBSERVATION) {
            if (item.value() != null && item.units() != null && !item.units().isBlank()) {
                return (item.value().trim() + " " + item.units().trim()).trim();
            }
            return item.value() != null ? item.value().trim() : "Not supplied";
        }
        return item.excerpt() == null || item.excerpt().isBlank() ? item.claim() : item.excerpt();
    }

    private String evidenceRegisterMethod(EvidenceItem item) {
        if (item.type() == EvidenceType.GIS_OBSERVATION) {
            return joinPresent(humanizeGisField(item.gisLayer()), item.operation(), humanizeGisField(item.metric()), item.units(),
                    String.join("; ", item.limitations()));
        }
        return joinPresent(item.sourceVersion(), item.jurisdiction(), item.applicability(),
                String.join("; ", item.limitations()));
    }

    private String evidenceLocator(EvidenceItem item) {
        if (item.pageNumber() != null) {
            return "page " + item.pageNumber() + (item.chunkId() == null ? "" : ", chunk " + item.chunkId());
        }
        return item.sourceLocator();
    }

    private String joinPresent(String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) present.add(value.trim());
        }
        return String.join("; ", present);
    }

    private String compact(String value, int maxLength) {
        if (value == null || value.isBlank()) return "Not supplied";
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) return normalized;
        String suffix = " ... [abridged]";
        int end = maxLength - suffix.length();
        int boundary = normalized.lastIndexOf(' ', end);
        if (boundary < end * 3 / 4) boundary = end;
        return normalized.substring(0, boundary).stripTrailing() + suffix;
    }

    private String humanizeGisField(String value) {
        if (value == null || value.isBlank()) return null;
        return value.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ')
                .replace(":", " — ").replaceAll("\\s+", " ").trim();
    }

    private String rowText(XWPFTableRow row) {
        return row.getTableCells().stream().map(XWPFTableCell::getText).reduce("", String::concat);
    }

    private void populateParagraph(XWPFParagraph paragraph, Map<String, String> values) {
        String text = paragraph.getText();
        if (text.contains("WRITER INSTRUCTION:")) {
            if (text.contains("FIGURE / MAP SLOT")) {
                text = text.replace("[WRITER INSTRUCTION: FIGURE / MAP SLOT —", "[MAP INSERTION POINT —");
            } else if (text.contains("APPROVAL DOCUMENT INSERTION POINT")) {
                text = text.replace("[WRITER INSTRUCTION: APPROVAL DOCUMENT INSERTION POINT —", "[APPROVAL DOCUMENT INSERTION POINT —");
            } else {
                setParagraphText(paragraph, "");
                return;
            }
        }
        Matcher matcher = TOKEN.matcher(text);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(values.getOrDefault(matcher.group(), UNAVAILABLE)));
        }
        matcher.appendTail(rendered);
        setParagraphText(paragraph, rendered.toString());
    }

    private void setParagraphText(XWPFParagraph paragraph, String text) {
        CTRPr runProperties = paragraph.getRuns().isEmpty() || paragraph.getRuns().getFirst().getCTR().getRPr() == null
                ? null : (CTRPr) paragraph.getRuns().getFirst().getCTR().getRPr().copy();
        for (int index = paragraph.getRuns().size() - 1; index >= 0; index--) {
            paragraph.removeRun(index);
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        XWPFRun run = paragraph.createRun();
        if (runProperties != null) {
            run.getCTR().setRPr(runProperties);
        }
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                run.addBreak();
            }
            run.setText(lines[index]);
        }
    }

    private Set<String> tokensIn(XWPFDocument document) {
        Set<String> tokens = new LinkedHashSet<>();
        for (XWPFParagraph paragraph : document.getParagraphs()) {
            addTokens(tokens, paragraph.getText());
        }
        for (XWPFTable table : document.getTables()) {
            collectTokens(table, tokens);
        }
        for (var header : document.getHeaderList()) {
            collectTokens(header, tokens);
        }
        for (var footer : document.getFooterList()) {
            collectTokens(footer, tokens);
        }
        return Set.copyOf(tokens);
    }

    private void collectTokens(XWPFTable table, Set<String> tokens) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                collectTokens(cell, tokens);
            }
        }
    }

    private void collectTokens(IBody body, Set<String> tokens) {
        body.getParagraphs().forEach(paragraph -> addTokens(tokens, paragraph.getText()));
        body.getTables().forEach(table -> collectTokens(table, tokens));
    }

    private void addTokens(Set<String> tokens, String text) {
        Matcher matcher = TOKEN.matcher(text == null ? "" : text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
    }

    private String valueOrUnavailable(String value) {
        return value == null || value.isBlank() ? UNAVAILABLE : value.trim();
    }
}
