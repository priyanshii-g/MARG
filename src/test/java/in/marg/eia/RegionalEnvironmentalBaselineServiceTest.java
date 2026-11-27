package in.marg.eia;

import static in.marg.eia.EnvironmentalReportModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RegionalEnvironmentalBaselineServiceTest {

    private final RegionalEnvironmentalBaselineService service =
            new RegionalEnvironmentalBaselineService(new ObjectMapper());

    @Test
    void extractsCentroidFromCoordinatesWhenAvailable() {
        var request = new GenerateReportFromLibraryRequest(
                "Test Highway", "NHAI", "Alt 1", "Kaziranga", "Assam", "v1.0", "2026-11-26",
                null, List.of(), Map.of(),
                Map.of("coordinates", List.of(
                        List.of(93.10, 26.50),
                        List.of(93.30, 26.58)
                ))
        );

        var centroid = service.extractCentroid(request);
        assertEquals(26.54, centroid.latitude(), 0.01);
        assertEquals(93.20, centroid.longitude(), 0.01);
    }

    @Test
    void extractsCentroidFromStateFallback() {
        var sikkimRequest = new GenerateReportFromLibraryRequest(
                "Sivok Rangpo Rail", "IRCON", "Alignment 1", "Sivok-Rangpo", "Sikkim", "v1.0", "2026-11-26",
                null, List.of(), Map.of(), Map.of()
        );
        var centroid = service.extractCentroid(sikkimRequest);
        assertEquals(27.05, centroid.latitude(), 0.01);
        assertEquals(88.50, centroid.longitude(), 0.01);
    }

    @Test
    void assemblesCompleteRegionalBaselineWithStableEvidenceIds() {
        var request = new GenerateReportFromLibraryRequest(
                "Assam Highway", "NHAI", "Corridor 1", "Kaziranga-Karbi Anglong", "Assam", "v1.0", "2026-11-26",
                null, List.of(), Map.of(), Map.of()
        );

        List<EvidenceItem> items = service.assembleRegionalBaseline(request);
        assertEquals(6, items.size());

        List<String> ids = items.stream().map(EvidenceItem::evidenceId).toList();
        assertTrue(ids.contains("REGIONAL-BASELINE-METEOROLOGY"));
        assertTrue(ids.contains("REGIONAL-BASELINE-AIR-CPCB-NAAQS"));
        assertTrue(ids.contains("REGIONAL-BASELINE-GEOLOGY-SEISMIC"));
        assertTrue(ids.contains("REGIONAL-BASELINE-NOISE-CPCB"));
        assertTrue(ids.contains("REGIONAL-BASELINE-WATER-HYDROLOGY"));
        assertTrue(ids.contains("REGIONAL-BASELINE-ECOLOGY-WII"));

        // Check CPCB NAAQS standards and numeric grounding
        EvidenceItem air = items.stream().filter(i -> i.evidenceId().equals("REGIONAL-BASELINE-AIR-CPCB-NAAQS")).findFirst().orElseThrow();
        assertNotNull(air.sourceTitle());
        assertTrue(air.claim().contains("PM10"));
        assertTrue(air.claim().contains("100"));
        assertTrue(air.numericValues().stream().anyMatch(n -> n.units().equals("µg/m³")));

        // Check Seismic Zone V
        EvidenceItem seismic = items.stream().filter(i -> i.evidenceId().equals("REGIONAL-BASELINE-GEOLOGY-SEISMIC")).findFirst().orElseThrow();
        assertTrue(seismic.claim().contains("Seismic Zone V"));
        assertTrue(seismic.claim().contains("0.36"));

        // Check WII Ecological guidance
        EvidenceItem ecology = items.stream().filter(i -> i.evidenceId().equals("REGIONAL-BASELINE-ECOLOGY-WII")).findFirst().orElseThrow();
        assertTrue(ecology.claim().contains("Kaziranga"));
        assertTrue(ecology.claim().contains("Wildlife Underpasses"));
        assertTrue(ecology.numericValues().stream().anyMatch(n -> n.units().equals("m")));
    }
}
