package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.EvidenceItem;
import static in.marg.eia.EnvironmentalReportModels.EvidenceType;
import static in.marg.eia.EnvironmentalReportModels.NumericValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import in.marg.eia.Mode2AlignmentScreeningService.AlignmentScreeningRequest;
import in.marg.eia.Mode2AlignmentScreeningService.AlignmentScreeningResult;
import in.marg.region.RegionalCorridorRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Mode2AlternativeRouteServiceTest {

    @Test
    void createsAndComparesGisEngineCandidateUsingExistingEndpoints() throws Exception {
        RegionalCorridorRegistry corridors = mock(RegionalCorridorRegistry.class);
        Mode2AlignmentScreeningService screening = mock(Mode2AlignmentScreeningService.class);
        String projectId = "M2P-12345678";
        List<List<Double>> submitted = List.of(List.of(93.30, 26.20), List.of(93.40, 26.30));
        List<List<Double>> candidate = List.of(List.of(93.30, 26.20), List.of(93.35, 26.25), List.of(93.40, 26.30));
        Map<String, Object> route = Map.of("features", List.of(Map.of(
                "properties", Map.of("featureRole", "MAINLINE"),
                "geometry", Map.of("type", "LineString", "coordinates", candidate))));
        when(corridors.route(eq("assam"), eq("TIER_2_PRECACHED_BENCHMARK"), eq(26.20), eq(93.30),
                eq(26.30), eq(93.40), eq(250.0), eq(0.08), eq(4000), eq(null), eq("NATIONAL_HIGHWAY"),
                eq(null), eq(null), eq(null), eq(false), eq(null), any(in.marg.routing.AlignmentPreference.class))).thenReturn(route);
        AlignmentScreeningResult existingScreen = result(projectId, "existing-route", "10.0", "8.0");
        AlignmentScreeningResult candidateScreen = result(projectId, "candidate-route", "11.0", "3.0");
        when(screening.screen(eq(projectId), any(AlignmentScreeningRequest.class)))
                .thenReturn(existingScreen, candidateScreen, candidateScreen, candidateScreen);

        var recommendation = new Mode2AlternativeRouteService(corridors, screening).recommend(projectId,
                new AlignmentScreeningRequest("Submitted route", "assam", "NATIONAL_HIGHWAY", submitted));

        assertEquals("POTENTIAL_ALTERNATIVE_FOR_REVIEW", recommendation.recommendationStatus());
        assertEquals(candidate, recommendation.candidateCoordinates());
        assertEquals(3, recommendation.options().size());
        assertEquals(1, recommendation.environmentalComparisons().size());
        assertEquals(new BigDecimal("-5.0"), recommendation.environmentalComparisons().getFirst().candidateMinusExisting());
        verify(screening, org.mockito.Mockito.times(4)).screen(eq(projectId), any(AlignmentScreeningRequest.class));
    }

    private AlignmentScreeningResult result(String projectId, String fingerprint, String length, String protectedArea) {
        List<EvidenceItem> evidence = List.of(
                item("GIS-M2-" + fingerprint + "-LENGTH", "routeLengthKm", length, "km"),
                item("GIS-M2-" + fingerprint + "-PROTECTED", "protectedAreaExposureKm", protectedArea, "km"));
        return new AlignmentScreeningResult(projectId, "Demo", fingerprint, "assam", "Assam", "NATIONAL_HIGHWAY",
                2, fingerprint, Map.of(), Map.of(), Map.of(), evidence, List.of("PROTECTED_AREAS"), List.of(), List.of());
    }

    private EvidenceItem item(String id, String metric, String value, String units) {
        return new EvidenceItem(id, EvidenceType.GIS_OBSERVATION, metric, "GIS", null, null, null, null,
                "test", null, "India", "screening", "PROTECTED_AREAS", "test", metric, value, units,
                List.of(), List.of(new NumericValue(new BigDecimal(value), units)));
    }
}
