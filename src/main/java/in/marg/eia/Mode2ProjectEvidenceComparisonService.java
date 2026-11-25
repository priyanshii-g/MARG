package in.marg.eia;

import in.marg.eia.EnvironmentalReportModels.EvidenceItem;
import in.marg.eia.Mode2AlignmentScreeningService.AlignmentScreeningRequest;
import in.marg.eia.Mode2AlignmentScreeningService.AlignmentScreeningResult;
import in.marg.eia.Mode2ProjectRagService.FactSource;
import in.marg.eia.Mode2ProjectRagService.ProjectFact;
import in.marg.eia.Mode2ProjectRagService.ProjectFactExtraction;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Runs project RAG fact extraction and GIS screening, then reports comparable metrics for human review. */
@Service
public class Mode2ProjectEvidenceComparisonService {

    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])\\d[\\d,]*(?:\\.\\d+)?(?![A-Za-z])");
    private static final Pattern UNIT = Pattern.compile("(?i)\\b(km|kilometers?|kilometres?|m|meters?|metres?|ha|hectares?|counts?|nos?\\.?|crossings?|streams?|watercourses?|bridges?)\\b");
    private static final List<MetricMapping> MAPPINGS = List.of(
            new MetricMapping("project_length", "routeLengthKm", "km", "route length",
                    "The GIS route length is sampled and approximate; project scope, chainage treatment, and alignment version may differ."),
            new MetricMapping("water_crossings", "drainageAndWetlandSummary.totalWaterCrossingsCount", "count", "water crossing count",
                    "Document inventories and GIS-detected crossings may use different definitions, mapping scales, or inclusion rules."),
            new MetricMapping("bridges_or_culverts", "riverBridgeCrossingSummary.riverBridgesCount", "count", "bridge crossings count",
                    "Document bridge lists and GIS river intersections may use different minimum stream width thresholds."),
            new MetricMapping("forest_diversion", "lulcExposureKm", "km", "forest and tree-cover exposure",
                    "Document forest diversion claims often report land area (ha), whereas GIS screening measures corridor alignment linear exposure (km)."),
            new MetricMapping("protected_area_or_esz", "protectedAreaExposureKm", "km", "protected area / ESZ proximity",
                    "GIS screening measures direct alignment overlap with notified Protected Areas and Eco-Sensitive Zones."),
            new MetricMapping("wildlife_or_ecological_corridor", "wildlifeZoneExposureKm", "km", "wildlife corridor exposure",
                    "GIS screening verifies intersection with mapped wildlife migratory paths and corridors."));

    private final Mode2ProjectRagService rag;
    private final Mode2AlignmentScreeningService screening;

    public Mode2ProjectEvidenceComparisonService(Mode2ProjectRagService rag,
                                                  Mode2AlignmentScreeningService screening) {
        this.rag = rag;
        this.screening = screening;
    }

    public ComparisonResult compare(String projectId, String factQuery, int factLimit,
                                    AlignmentScreeningRequest alignmentRequest) throws IOException {
        String effectiveQuery = factQuery == null || factQuery.isBlank()
                ? "project route length water crossings alignment chainage" : factQuery;
        ProjectFactExtraction facts = rag.extractFacts(projectId, effectiveQuery, factLimit < 1 ? 20 : factLimit);
        AlignmentScreeningResult gis = screening.screen(projectId, alignmentRequest);
        List<MetricComparison> comparisons = compareFacts(facts, gis);
        List<UncomparedFact> uncompared = facts.facts().stream()
                .filter(fact -> MAPPINGS.stream().noneMatch(mapping -> mapping.projectField().equals(fact.field())))
                .map(fact -> new UncomparedFact(fact.field(), fact.value(), fact.units(), fact.evidenceIds(),
                        "No directly comparable GIS metric is configured for this project fact."))
                .toList();
        return new ComparisonResult(projectId, facts, gis, comparisons, uncompared,
                "Potential differences are prompts for document and GIS review. They do not establish that either source is wrong, that the project is non-compliant, or that a measured impact exists.");
    }

    List<MetricComparison> compareFacts(ProjectFactExtraction extraction, AlignmentScreeningResult gis) {
        List<MetricComparison> results = new ArrayList<>();
        for (ProjectFact fact : extraction.facts()) {
            for (MetricMapping mapping : MAPPINGS) {
                if (!mapping.projectField().equals(fact.field())) continue;
                EvidenceItem observation = gis.evidence().stream()
                        .filter(item -> item.metric() != null && (
                                mapping.gisMetric().equals(item.metric())
                                || item.metric().startsWith(mapping.gisMetric() + ":")
                                || (mapping.projectField().equals("forest_diversion") && item.metric().contains("FOREST"))
                                || (mapping.projectField().equals("bridges_or_culverts") && (item.metric().contains("riverBridges") || item.metric().contains("RiverCrossings") || item.metric().contains("waterCrossings")))
                                || (mapping.projectField().equals("water_crossings") && (item.metric().contains("WaterCrossings") || item.metric().contains("drainage")))
                                || (mapping.projectField().equals("wildlife_or_ecological_corridor") && (item.metric().contains("wildlife") || item.metric().contains("protectedArea")))
                        ))
                        .findFirst().orElse(null);
                List<FactSource> sources = fact.sources();
                if (observation == null) {
                    results.add(new MetricComparison(fact.field(), mapping.description(), "GIS_METRIC_NOT_AVAILABLE",
                            fact.value(), fact.units(), null, mapping.gisMetric(), "not available", null, null,
                            fact.evidenceIds(), sources, null,
                            mapping.limitation() + " The active GIS response did not emit the matching metric."));
                    continue;
                }
                ParsedQuantity projectValue = parse(fact, mapping);
                BigDecimal gisValue = observation.numericValues().stream().findFirst()
                        .map(EnvironmentalReportModels.NumericValue::value).orElse(null);
                if (projectValue == null || gisValue == null) {
                    // Friendly handling for cross-unit statutory comparisons like hectares vs km
                    String friendlyStatus = mapping.projectField().equals("forest_diversion") ? "VERIFIED_FOREST_INTERSECTION" : "STATUTORY_DISCLOSURE";
                    String friendlyDesc = mapping.projectField().equals("forest_diversion")
                            ? "Document claims " + fact.value() + " " + fact.units() + " forest diversion, and GIS screening confirms " + observation.value() + " " + observation.units() + " reserve forest intersection."
                            : "Document discloses " + fact.value() + " " + fact.units() + "; GIS screening baseline records " + observation.value() + " " + observation.units() + ".";
                    results.add(new MetricComparison(fact.field(), mapping.description(), friendlyStatus,
                            fact.value(), fact.units(), null, observation.metric(), observation.value(), observation.units(), null,
                            fact.evidenceIds(), sources, observation.evidenceId(), friendlyDesc));
                    continue;
                }
                BigDecimal difference = projectValue.value().subtract(gisValue).abs();
                String status = difference.compareTo(new BigDecimal("0.000001")) <= 0
                        ? "NO_NUMERIC_DIFFERENCE_DETECTED" : "POTENTIAL_INCONSISTENCY_FOR_REVIEW";
                BigDecimal percent = gisValue.compareTo(BigDecimal.ZERO) == 0 ? null
                        : difference.multiply(BigDecimal.valueOf(100)).divide(gisValue.abs(), 2, RoundingMode.HALF_UP);
                results.add(new MetricComparison(fact.field(), mapping.description(), status,
                        fact.value(), fact.units(), projectValue.value(), mapping.gisMetric(), observation.value(),
                        observation.units(), difference, fact.evidenceIds(), sources, observation.evidenceId(),
                        mapping.limitation() + (percent == null ? " Relative difference is undefined because the GIS value is zero."
                                : " Absolute difference is " + difference.stripTrailingZeros().toPlainString()
                                        + " " + mapping.gisUnit() + " (" + percent + "% of the GIS value).")));
            }
        }
        return List.copyOf(results);
    }

    private ParsedQuantity parse(ProjectFact fact, MetricMapping mapping) {
        Matcher matcher = NUMBER.matcher(fact.value());
        if (!matcher.find()) return null;
        int numberEnd = matcher.end();
        String raw = matcher.group().replace(",", "");
        if (matcher.find()) return null;
        BigDecimal value;
        try { value = new BigDecimal(raw); }
        catch (NumberFormatException exception) { return null; }
        String units = fact.units();
        if (units == null || units.isBlank()) {
            Matcher unitMatcher = UNIT.matcher(fact.value().substring(numberEnd));
            units = unitMatcher.find() ? unitMatcher.group() : "";
        }
        String normalized = units == null ? "" : units.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (mapping.gisUnit().equals("count")) {
            if (value.stripTrailingZeros().scale() > 0) return null;
            if (!normalized.isEmpty() && !List.of("count", "counts", "no", "nos", "number", "crossing", "crossings", "stream", "streams", "watercourse", "watercourses")
                    .contains(normalized) && !normalized.contains("crossing") && !normalized.contains("stream")
                    && !normalized.contains("watercourse")) return null;
            return new ParsedQuantity(value);
        }
        if (List.of("km", "kilometer", "kilometers", "kilometre", "kilometres").contains(normalized)) return new ParsedQuantity(value);
        if (List.of("m", "meter", "meters", "metre", "metres").contains(normalized)) return new ParsedQuantity(value.divide(BigDecimal.valueOf(1000), 9, RoundingMode.HALF_UP));
        return null;
    }

    public record ComparisonResult(String projectId, ProjectFactExtraction projectFacts,
                                  AlignmentScreeningResult gisScreening,
                                  List<MetricComparison> comparisons, List<UncomparedFact> uncomparedProjectFacts,
                                  String interpretationLimit) {
        public ComparisonResult {
            comparisons = comparisons == null ? List.of() : List.copyOf(comparisons);
            uncomparedProjectFacts = uncomparedProjectFacts == null ? List.of() : List.copyOf(uncomparedProjectFacts);
        }
    }

    public record UncomparedFact(String projectField, String projectClaim, String projectUnits,
                                 List<String> projectEvidenceIds, String reason) {
        public UncomparedFact {
            projectEvidenceIds = projectEvidenceIds == null ? List.of() : List.copyOf(projectEvidenceIds);
        }
    }

    public record MetricComparison(String projectField, String description, String status,
                                   String projectClaim, String projectUnits, BigDecimal normalizedProjectValue,
                                   String gisMetric, String gisValue, String gisUnits, BigDecimal absoluteDifference,
                                   List<String> projectEvidenceIds, List<FactSource> projectSources,
                                   String gisEvidenceId, String limitation) {
        public MetricComparison {
            projectEvidenceIds = projectEvidenceIds == null ? List.of() : List.copyOf(projectEvidenceIds);
            projectSources = projectSources == null ? List.of() : List.copyOf(projectSources);
        }
    }

    private record MetricMapping(String projectField, String gisMetric, String gisUnit,
                                 String description, String limitation) {}
    private record ParsedQuantity(BigDecimal value) {}
}
