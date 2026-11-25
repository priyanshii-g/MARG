package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.EvidenceItem;
import static in.marg.eia.EnvironmentalReportModels.EvidenceType;
import static in.marg.eia.EnvironmentalReportModels.NumericValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Turns deterministic Mode 2 route diagnostics into typed GIS observations with stable IDs. */
@Component
public class Mode2GisEvidenceAssembler {

    private static final List<ExposureSpec> EXPOSURES = List.of(
            new ExposureSpec("lulcExposureKm", "LAND_COVER", "land_use_and_land_cover"),
            new ExposureSpec("floodExposureKm", "FLOOD_HAZARD", "flood_hazard"),
            new ExposureSpec("slopeExposureKm", "SLOPE", "terrain"),
            new ExposureSpec("protectedAreaExposureKm", "PROTECTED_AREAS", "protected_and_conserved_areas"),
            new ExposureSpec("settlementExposureKm", "SETTLEMENT_EXPOSURE", "settlements_and_receptors"),
            new ExposureSpec("waterBodyExposureKm", "WATER_BODIES", "water_resources"),
            new ExposureSpec("wildlifeZoneExposureKm", "WILDLIFE_CORRIDORS_AND_ESZ", "wildlife_and_sensitive_areas"),
            new ExposureSpec("geotechHazardExposureKm", "GEOTECH_HAZARDS", "terrain_and_geohazards"),
            new ExposureSpec("drainageWetlandExposureKm", "DRAINAGE_AND_WETLANDS", "drainage_and_wetlands"),
            new ExposureSpec("utilityCorridorExposureKm", "UTILITY_CORRIDORS", "utility_corridors"),
            new ExposureSpec("buildingDensityExposureKm", "BUILDING_DENSITY", "settlements_and_receptors"),
            new ExposureSpec("populationDensityExposureKm", "POPULATION_DENSITY", "population_and_social_exposure"));

    private static final List<SummarySpec> SUMMARIES = List.of(
            new SummarySpec("riverBridgeCrossingSummary", "RIVER_NETWORK", "water_resources"),
            new SummarySpec("wildlifePassageCrossingSummary", "WILDLIFE_CORRIDORS", "wildlife_and_sensitive_areas"),
            new SummarySpec("drainageAndWetlandSummary", "DRAINAGE_AND_WETLANDS", "drainage_and_wetlands"),
            new SummarySpec("transportCrossingSummary", "TRANSPORT_NETWORK", "transport_infrastructure"),
            new SummarySpec("geotechMitigationCrossingSummary", "GEOTECH_HAZARDS", "terrain_and_geohazards"),
            new SummarySpec("utilityCrossingSummary", "UTILITY_CORRIDORS", "utility_corridors"),
            new SummarySpec("buildingAndSettlementRowImpactSummary", "BUILDING_AND_SETTLEMENTS", "settlements_and_receptors"),
            new SummarySpec("populationAndDemographicExposureSummary", "POPULATION_DEMOGRAPHICS", "population_and_social_exposure"));

    public List<EvidenceItem> assemble(String projectId, String regionId, String routeFingerprint,
                                       Map<String, Object> diagnostics) {
        List<EvidenceItem> evidence = new ArrayList<>();
        Map<?, ?> hardConstraints = diagnostics.get("hardConstraintValidation") instanceof Map<?, ?> values
                ? values : Map.of();
        Object routeLength = diagnostics.get("routeLengthKm");
        if (routeLength instanceof Number number) {
            evidence.add(item(projectId, regionId, routeFingerprint, "routeLengthKm", "ROUTE_GEOMETRY",
                    "route_geometry", "sampledRouteLength", number, "km",
                    "Approximate route length calculated by the GIS diagnostic sampler."));
        }
        for (ExposureSpec spec : EXPOSURES) {
            Object raw = diagnostics.get(spec.diagnosticKey());
            if (raw instanceof Number number) {
                String limitation = "Mapped route exposure accumulated from diagnostic samples; it is not affected land area or a verified impact.";
                if (spec.diagnosticKey().equals("protectedAreaExposureKm")
                        && positiveCount(hardConstraints.get("protectedAreaNoDataSamples")) > 0) {
                    limitation += " Some protected-area raster samples had no data; this value covers only sampled cells with data and must not be read as total or zero exposure.";
                }
                evidence.add(item(projectId, regionId, routeFingerprint, spec.diagnosticKey(), spec.layer(),
                        spec.component(), spec.diagnosticKey(), number, "km",
                        limitation));
            } else if (raw instanceof Map<?, ?> classes) {
                for (Map.Entry<?, ?> entry : classes.entrySet()) {
                    if (!(entry.getValue() instanceof Number number)) continue;
                    String metric = spec.diagnosticKey() + ":" + entry.getKey();
                    evidence.add(item(projectId, regionId, routeFingerprint, metric, spec.layer(), spec.component(),
                            "sampledRouteLengthByClass:" + entry.getKey(), number, "km",
                            "Mapped route exposure accumulated from diagnostic samples; it is not affected land area or a verified impact."));
                }
            }
        }
        addNoDataEvidence(evidence, projectId, regionId, routeFingerprint, hardConstraints,
                "protectedAreaNoDataSamples", "PROTECTED_AREAS", "protected_and_conserved_areas",
                "protectedAreaRasterNoDataSamples", "Protected-area raster coverage was unavailable at these diagnostic samples; exposure is incomplete.");
        addNoDataEvidence(evidence, projectId, regionId, routeFingerprint, hardConstraints,
                "slopeNoDataViolationSamples", "SLOPE", "terrain", "slopeRasterNoDataSamples",
                "Slope raster coverage was unavailable at these diagnostic samples; slope constraints are incomplete.");
        for (SummarySpec spec : SUMMARIES) {
            Object raw = diagnostics.get(spec.diagnosticKey());
            if (!(raw instanceof Map<?, ?> summary)) continue;
            for (Map.Entry<?, ?> entry : summary.entrySet()) {
                if (!(entry.getValue() instanceof Number number)) continue;
                String metric = spec.diagnosticKey() + "." + entry.getKey();
                evidence.add(item(projectId, regionId, routeFingerprint, metric, spec.layer(), spec.component(),
                        metric, number, unitsFor(entry.getKey().toString()),
                        "Modelled GIS vector-index or corridor-footprint diagnostic; verify against authoritative data and detailed design."));
            }
        }
        return List.copyOf(evidence);
    }

    public Map<String, Object> methodSummary(Map<String, Object> diagnostics) {
        Map<String, Object> method = new LinkedHashMap<>();
        for (String key : List.of("diagnosticIsApproximate", "sampleSpacingMeters", "diagnosticSampleCount",
                "routeLengthKm", "hardConstraintValidation")) {
            if (diagnostics.containsKey(key)) method.put(key, diagnostics.get(key));
        }
        method.put("analysisMethod", "CompositeRouteMetricsService samples the supplied WGS84 alignment; vector layers are evaluated by their configured spatial indices.");
        method.put("scope", "Screening against the selected regional corridor's currently configured GIS layers.");
        return Collections.unmodifiableMap(method);
    }

    private int positiveCount(Object value) {
        return value instanceof Number number && number.intValue() > 0 ? number.intValue() : 0;
    }

    private void addNoDataEvidence(List<EvidenceItem> evidence, String projectId, String regionId,
                                   String routeFingerprint, Map<?, ?> hardConstraints, String sourceKey,
                                   String layer, String component, String metric, String limitation) {
        int count = positiveCount(hardConstraints.get(sourceKey));
        if (count == 0) return;
        evidence.add(item(projectId, regionId, routeFingerprint, metric, layer, component,
                sourceKey, count, "samples", limitation));
    }

    private EvidenceItem item(String projectId, String regionId, String routeFingerprint, String metricKey,
                              String layer, String component, String operation, Number rawValue, String units,
                              String limitation) {
        BigDecimal value = new BigDecimal(rawValue.toString());
        String normalizedMetric = Pattern.compile("[^A-Za-z0-9]+").matcher(metricKey.toUpperCase(Locale.ROOT))
                .replaceAll("_").replaceAll("^_+|_+$", "");
        String evidenceId = "GIS-M2-" + projectId.substring(4, 12) + "-" + routeFingerprint.substring(0, 12)
                + "-" + normalizedMetric;
        String statement = component + ": " + metricKey + " = " + value.stripTrailingZeros().toPlainString() + " " + units + ".";
        return new EvidenceItem(evidenceId, EvidenceType.GIS_OBSERVATION, statement,
                "MARG composite GIS screening: " + regionId, null, null, null, null,
                "Mode 2 project " + projectId + "; region=" + regionId + "; route=" + routeFingerprint,
                null, "Regional corridor GIS dataset", "Preliminary spatial screening only; not a legal determination or field-verified condition.",
                layer, "CompositeRouteMetricsService: " + operation,
                metricKey, value.stripTrailingZeros().toPlainString(), units,
                List.of(limitation, "Dataset vintage and positional accuracy are not exposed in this analysis response."),
                List.of(new NumericValue(value, units)));
    }

    private String unitsFor(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        if (normalized.contains("meter") || normalized.contains("span")) return "m";
        if (normalized.contains("angle") || normalized.contains("degrees")) return "degrees";
        if (normalized.contains("km")) return "km";
        if (normalized.contains("count") || normalized.endsWith("crossings")) return "count";
        return "value";
    }

    private record ExposureSpec(String diagnosticKey, String layer, String component) {}
    private record SummarySpec(String diagnosticKey, String layer, String component) {}
}
