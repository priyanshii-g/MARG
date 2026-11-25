package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Converts the existing deterministic Mode 1 GIS payload into report evidence without recalculation. */
@Component
public class EnvironmentalReportEvidenceAssembler {

    private static final List<CoverageTopic> SPATIAL_TOPICS = List.of(
            new CoverageTopic("Land use and land cover", List.of("land_cover", "land cover", "lulc", "land use")),
            new CoverageTopic("Water, flood, drainage and wetlands", List.of("water", "flood", "drainage", "wetland", "hydrology")),
            new CoverageTopic("Terrain and geohazards", List.of("terrain", "slope", "geotech", "geohazard", "landslide")),
            new CoverageTopic("Protected areas, forests and ecological context", List.of("protected", "conserved", "forest", "wildlife", "ecolog", "esz")),
            new CoverageTopic("Settlements, buildings and population receptors", List.of("settlement", "building", "population", "community", "receptor", "demographic"))
    );

    /** Summarizes route GIS evidence presence; missing topics mean missing evidence in this payload, not absence on the ground. */
    public Coverage assessCoverage(Mode1EnvironmentalSection section) {
        List<Mode1EnvironmentalSection.Finding> findings = section == null ? List.of() : section.findings();
        List<String> covered = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (CoverageTopic topic : SPATIAL_TOPICS) {
            boolean found = findings.stream().anyMatch(finding -> {
                String descriptor = (safe(finding.environmentalComponent()) + " " + safe(finding.metric()) + " "
                        + safe(finding.sourceLayer())).toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
                return topic.terms().stream().anyMatch(descriptor::contains);
            });
            (found ? covered : missing).add(topic.label());
        }
        return new Coverage(findings.size(), List.copyOf(covered), List.copyOf(missing));
    }

    private String safe(String value) { return value == null ? "" : value; }

    public record Coverage(int findingCount, List<String> coveredTopics, List<String> missingTopics) {
        public Coverage {
            coveredTopics = coveredTopics == null ? List.of() : List.copyOf(coveredTopics);
            missingTopics = missingTopics == null ? List.of() : List.copyOf(missingTopics);
        }
    }

    private record CoverageTopic(String label, List<String> terms) {}

    public GenerateReportRequest assemble(GenerateReportRequest request) {
        if (request == null || request.gisScreening() == null) {
            return request;
        }
        List<EvidenceItem> evidence = new ArrayList<>(request.evidence());
        Mode1EnvironmentalSection gis = request.gisScreening();

        for (Mode1EnvironmentalSection.Finding finding : gis.findings()) {
            evidence.add(fromFinding(finding));
        }

        if (!gis.method().isEmpty()) {
            evidence.add(fromMethod(gis));
        }

        return new GenerateReportRequest(request.projectName(), request.projectProponent(), request.routeAlternativeName(),
                request.studyArea(), request.reportVersion(), request.assessmentDate(), evidence, request.claims(), null,
                request.tableRows());
    }

    private EvidenceItem fromFinding(Mode1EnvironmentalSection.Finding finding) {
        return new EvidenceItem(finding.evidenceId(), EvidenceType.GIS_OBSERVATION,
                findingStatement(finding), finding.sourceLayer(), null, null, null, null,
                "MARG composite route screening; profile-specific evidence ID " + finding.evidenceId(),
                null, "Study-region GIS analysis", "Screening observation only; no legal applicability determination",
                finding.sourceLayer(), finding.method(), finding.metric(), String.valueOf(finding.value()), finding.units(),
                finding.limitations(), numericValue(finding.value(), finding.units()));
    }

    private EvidenceItem fromMethod(Mode1EnvironmentalSection section) {
        TreeMap<String, Object> ordered = new TreeMap<>(section.method());
        List<NumericValue> numbers = new ArrayList<>();
        StringBuilder summary = new StringBuilder();
        ordered.forEach((key, value) -> appendMethodValue(summary, numbers, key, value));
        String profile = String.valueOf(section.method().getOrDefault("routeProfile", "MODE1"));
        String id = "GIS-M1-METHOD-" + profile.replaceAll("[^A-Za-z0-9._:-]", "_");
        return new EvidenceItem(id, EvidenceType.GIS_OBSERVATION,
                "Deterministic GIS route-analysis method and diagnostic metadata",
                "MARG route diagnostics", null, null, null, summary.toString(),
                "metadata.environmentalSectionInput.method", null, "Study-region GIS analysis",
                "Method metadata only; not a field measurement or legal determination",
                "MARG_ROUTE_DIAGNOSTICS", "Existing composite route diagnostic metadata; no new spatial calculation",
                "routeAnalysisMethodAndDiagnostics", summary.toString(), "structured metadata",
                section.limitations(), numbers);
    }

    private void appendMethodValue(StringBuilder text, List<NumericValue> numbers, String key, Object value) {
        if (value instanceof Map<?, ?> nested) {
            new TreeMap<>(stringKeyMap(nested)).forEach((nestedKey, nestedValue) ->
                    appendMethodValue(text, numbers, key + "." + nestedKey, nestedValue));
            return;
        }
        if (value instanceof Number numeric) {
            String units = unitsFor(key);
            BigDecimal decimal = new BigDecimal(numeric.toString());
            numbers.add(new NumericValue(decimal, units));
            text.append(key).append('=').append(decimal.stripTrailingZeros().toPlainString()).append(' ').append(units);
        } else {
            text.append(key).append('=').append(value == null ? "unavailable" : value);
        }
        text.append("; ");
    }

    private Map<String, Object> stringKeyMap(Map<?, ?> map) {
        Map<String, Object> result = new TreeMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private List<NumericValue> numericValue(Object raw, String units) {
        if (!(raw instanceof Number number)) {
            return List.of();
        }
        return List.of(new NumericValue(new BigDecimal(number.toString()), units));
    }

    private String findingStatement(Mode1EnvironmentalSection.Finding finding) {
        return finding.environmentalComponent() + ": " + finding.metric() + " = " + finding.value() + " " + finding.units()
                + "; source layer " + finding.sourceLayer() + "; method: " + finding.method()
                + "; limitations: " + String.join("; ", finding.limitations());
    }

    private String unitsFor(String key) {
        String lower = key.toLowerCase();
        if (lower.endsWith("meters") || lower.endsWith("meter")) return "m";
        if (lower.endsWith("km")) return "km";
        if (lower.toLowerCase().contains("percent")) return "%";
        if (lower.toLowerCase().contains("count")) return "count";
        if (lower.toLowerCase().contains("spacing")) return "m";
        if (lower.toLowerCase().contains("resolution")) return "m";
        return "value";
    }
}
