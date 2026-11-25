package in.marg.eia;

import in.marg.eia.EnvironmentalReportModels.EvidenceItem;
import in.marg.eia.Mode2AlignmentScreeningService.AlignmentScreeningRequest;
import in.marg.eia.Mode2AlignmentScreeningService.AlignmentScreeningResult;
import in.marg.model.GeoPoint;
import in.marg.region.RegionalCorridorRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import org.springframework.stereotype.Service;

/** Generates one GIS-engine candidate and screens it against the submitted project's route. */
@Service
public class Mode2AlternativeRouteService {
    private static final double ROUTING_RESOLUTION_METERS = 250.0;
    private static final double CORRIDOR_BUFFER_DEGREES = 0.08;
    private static final int MAX_LULC_SAMPLES = 4000;

    private final RegionalCorridorRegistry corridors;
    private final Mode2AlignmentScreeningService screening;

    public Mode2AlternativeRouteService(RegionalCorridorRegistry corridors,
                                        Mode2AlignmentScreeningService screening) {
        this.corridors = corridors;
        this.screening = screening;
    }

    public AlternativeRecommendation recommend(String projectId, AlignmentScreeningRequest request) throws IOException {
        if (request == null || request.coordinates() == null || request.coordinates().size() < 2) {
            throw new IllegalArgumentException("An existing alignment with at least two coordinates is required.");
        }
        AlignmentScreeningResult existing = screening.screen(projectId, request);
        List<List<Double>> coordinates = request.coordinates();
        List<Double> first = coordinates.getFirst();
        List<Double> last = coordinates.getLast();
        String facilityMode = existing.facilityMode();

        Map<String, Object> generated = corridors.route(existing.regionId(), "TIER_2_PRECACHED_BENCHMARK",
                first.get(1), first.get(0), last.get(1), last.get(0), ROUTING_RESOLUTION_METERS,
                CORRIDOR_BUFFER_DEGREES, MAX_LULC_SAMPLES, null, facilityMode, null, null, null,
                false, null);
        List<List<Double>> alternativeCoordinates = extractMainline(generated);
        AlignmentScreeningRequest alternativeRequest = new AlignmentScreeningRequest(
                "MARG GIS-engine candidate", existing.regionId(), facilityMode, alternativeCoordinates);
        AlignmentScreeningResult alternative = screening.screen(projectId, alternativeRequest);

        List<IndicatorComparison> comparisons = compareEnvironmentalIndicators(existing.evidence(), alternative.evidence());
        long improved = comparisons.stream().filter(item -> item.status().equals("LOWER_ON_CANDIDATE")).count();
        long worsened = comparisons.stream().filter(item -> item.status().equals("HIGHER_ON_CANDIDATE")).count();
        boolean incomplete = comparisons.stream().anyMatch(item -> item.status().startsWith("NOT_ASSESSED"));
        String status = comparisons.isEmpty() ? "INSUFFICIENT_COMPARABLE_METRICS"
                : improved > 0 && worsened == 0 && !incomplete ? "POTENTIAL_ALTERNATIVE_FOR_REVIEW"
                : improved > 0 ? "TRADE_OFF_REQUIRES_REVIEW" : "NO_CLEAR_GIS_ADVANTAGE";
        if (incomplete && improved == 0 && worsened == 0) status = "INSUFFICIENT_COMPARABLE_METRICS";
        BigDecimal existingLength = metricValue(existing.evidence(), "routeLengthKm");
        BigDecimal candidateLength = metricValue(alternative.evidence(), "routeLengthKm");
        BigDecimal lengthDelta = existingLength == null || candidateLength == null
                ? null : candidateLength.subtract(existingLength);
        Object candidateGeoJsonFeatures = generated == null ? List.of() : generated.getOrDefault("features", List.of());
        return new AlternativeRecommendation(projectId, status, existing, alternative,
                alternativeCoordinates, existingLength, candidateLength, lengthDelta, comparisons,
                candidateGeoJsonFeatures,
                "The candidate uses MARG's existing composite GIS routing engine with the submitted route's endpoints and facility class. A lower mapped exposure is only a screening advantage; assess endpoint snapping, land ownership, engineering feasibility, legal constraints, authoritative datasets and field conditions before considering any change.");
    }

    private List<List<Double>> extractMainline(Map<String, Object> route) {
        Object rawFeatures = route == null ? null : route.get("features");
        if (!(rawFeatures instanceof List<?> features)) {
            throw new IllegalStateException("The GIS routing engine did not return a candidate route feature collection.");
        }
        for (Object rawFeature : features) {
            if (!(rawFeature instanceof Map<?, ?> feature)) continue;
            Object rawProperties = feature.get("properties");
            String role = rawProperties instanceof Map<?, ?> properties ? String.valueOf(properties.get("featureRole")) : "";
            if (!"MAINLINE".equalsIgnoreCase(role) && !"GREENFIELD_MAINLINE".equalsIgnoreCase(role)) {
                // If no mainline tagged but it's a LineString feature with route properties, check if it's the primary route
                if (rawProperties instanceof Map<?, ?> properties && !properties.containsKey("routeLengthKm")) {
                    continue;
                }
            }
            Object rawGeometry = feature.get("geometry");
            if (!(rawGeometry instanceof Map<?, ?> geometry)
                    || !(geometry.get("coordinates") instanceof List<?> points)) continue;
            List<List<Double>> result = new ArrayList<>();
            for (Object rawPoint : points) {
                if (!(rawPoint instanceof List<?> pair) || pair.size() < 2
                        || !(pair.get(0) instanceof Number longitude) || !(pair.get(1) instanceof Number latitude)) {
                    throw new IllegalStateException("The GIS engine returned an invalid mainline coordinate.");
                }
                result.add(List.of(longitude.doubleValue(), latitude.doubleValue()));
            }
            if (result.size() < 2) throw new IllegalStateException("The GIS engine candidate has fewer than two vertices.");
            return List.copyOf(result);
        }
        throw new IllegalStateException("The GIS routing response contains no MAINLINE feature.");
    }

    private List<IndicatorComparison> compareEnvironmentalIndicators(List<EvidenceItem> existing,
                                                                     List<EvidenceItem> alternative) {
        Map<String, EvidenceItem> existingByMetric = indicatorMap(existing);
        Map<String, EvidenceItem> alternativeByMetric = indicatorMap(alternative);
        List<IndicatorComparison> result = new ArrayList<>();
        LinkedHashSet<String> metrics = new LinkedHashSet<>(existingByMetric.keySet());
        metrics.addAll(alternativeByMetric.keySet());
        for (String metric : metrics) {
            EvidenceItem baseline = existingByMetric.get(metric);
            EvidenceItem candidate = alternativeByMetric.get(metric);
            if (baseline == null || candidate == null) {
                EvidenceItem available = baseline == null ? candidate : baseline;
                result.add(new IndicatorComparison(metric, available.units(),
                        baseline == null ? null : numericValue(baseline),
                        candidate == null ? null : numericValue(candidate), null,
                        baseline == null ? "NOT_ASSESSED_ON_SUBMITTED_ROUTE" : "NOT_ASSESSED_ON_CANDIDATE",
                        baseline == null ? null : baseline.evidenceId(), candidate == null ? null : candidate.evidenceId()));
                continue;
            }
            BigDecimal oldValue = numericValue(baseline);
            BigDecimal newValue = candidate == null ? null : numericValue(candidate);
            if (oldValue == null || newValue == null) continue;
            // Suppress non-impactful zero-zero technical sub-crossings to keep indicator table clean and legible
            if (oldValue.signum() == 0 && newValue.signum() == 0 && (metric.contains(".") || metric.contains("ehv") || metric.contains("subtransmission"))) {
                continue;
            }
            int order = newValue.compareTo(oldValue);
            result.add(new IndicatorComparison(metric, baseline.units(), oldValue, newValue,
                    newValue.subtract(oldValue), order < 0 ? "LOWER_ON_CANDIDATE"
                            : order > 0 ? "HIGHER_ON_CANDIDATE" : "UNCHANGED",
                    baseline.evidenceId(), candidate.evidenceId()));
        }
        return List.copyOf(result);
    }

    private Map<String, EvidenceItem> indicatorMap(List<EvidenceItem> evidence) {
        Map<String, EvidenceItem> result = new LinkedHashMap<>();
        for (EvidenceItem item : evidence) {
            String metric = item.metric();
            if (metric == null || !isEnvironmentalIndicator(metric) || numericValue(item) == null) continue;
            result.putIfAbsent(metric, item);
        }
        return result;
    }

    private boolean isEnvironmentalIndicator(String metric) {
        String key = metric.toLowerCase(java.util.Locale.ROOT);
        if (key.contains("nodata")) return false;
        return key.contains("exposurekm") || key.contains("crossingscount") || key.contains("crossingcount")
                || key.contains("rowareahectares") || key.contains("populationwithin")
                || key.contains("displacedpopulation") || key.contains("settlementsintersecting")
                || key.contains("buildingsindirectrow") || key.contains("wetlandsindirectrow");
    }

    private BigDecimal metricValue(List<EvidenceItem> evidence, String metric) {
        return evidence.stream().filter(item -> metric.equals(item.metric())).map(this::numericValue)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private BigDecimal numericValue(EvidenceItem item) {
        return item.numericValues() == null || item.numericValues().isEmpty()
                ? null : item.numericValues().getFirst().value();
    }

    public record IndicatorComparison(String metric, String units, BigDecimal existingValue,
                                      BigDecimal candidateValue, BigDecimal candidateMinusExisting,
                                      String status, String existingEvidenceId, String candidateEvidenceId) {}

    public record AlternativeRecommendation(String projectId, String recommendationStatus,
                                             AlignmentScreeningResult existingRoute,
                                             AlignmentScreeningResult candidateRoute,
                                             List<List<Double>> candidateCoordinates,
                                             BigDecimal existingRouteLengthKm,
                                             BigDecimal candidateRouteLengthKm,
                                             BigDecimal candidateLengthMinusExistingKm,
                                             List<IndicatorComparison> environmentalComparisons,
                                             Object candidateFeatures,
                                             String interpretationLimit) {
        public AlternativeRecommendation {
            candidateCoordinates = List.copyOf(candidateCoordinates);
            environmentalComparisons = List.copyOf(environmentalComparisons);
        }
    }
}
