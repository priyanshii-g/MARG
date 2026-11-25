package in.marg.eia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import in.marg.region.RegionalCorridorRegistry;
import in.marg.model.GeoPoint;
import in.marg.transport.FacilityMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Mode2AlignmentScreeningServiceTest {

    @TempDir
    Path directory;

    @Test
    void screensSuppliedAlignmentAndReturnsEvidenceReadyGisObservations() throws Exception {
        var projects = new Mode2ProjectDocumentService(new Mode2ProjectProperties(directory), new PageTextChunker());
        var project = projects.createProject("Existing Highway");
        var registry = new RegionalCorridorRegistry();
        var diagnostics = Map.<String, Object>of(
                "routeLengthKm", 11.1,
                "diagnosticIsApproximate", true,
                "sampleSpacingMeters", 125.0,
                "diagnosticSampleCount", 89,
                "protectedAreaExposureKm", 0.2,
                "lulcExposureKm", Map.of("FOREST", 5.5),
                "riverBridgeCrossingSummary", Map.of("totalRiverCrossingsCount", 2, "majorRiverBridgesCount", 1),
                "drainageAndWetlandSummary", Map.of("totalWaterCrossingsCount", 3),
                "hardConstraintValidation", Map.of("protectedAreaNoDataSamples", 2));
        AtomicReference<List<GeoPoint>> screenedRoute = new AtomicReference<>();
        AtomicReference<FacilityMode> screenedMode = new AtomicReference<>();
        Mode2GisMetricsProvider metrics = (region, route, mode) -> {
            assertEquals("assam", region);
            screenedRoute.set(route);
            screenedMode.set(mode);
            return diagnostics;
        };
        var service = new Mode2AlignmentScreeningService(projects, registry, metrics, new Mode2GisEvidenceAssembler());
        var request = new Mode2AlignmentScreeningService.AlignmentScreeningRequest("NH sample alignment", "assam", null,
                List.of(List.of(93.30, 26.20), List.of(93.40, 26.30)));

        var result = service.screen(project.projectId(), request);

        assertEquals("assam", result.regionId());
        assertEquals(2, result.vertexCount());
        assertEquals(7, result.evidence().size());
        assertTrue(result.evidence().stream().allMatch(item -> item.type() == EnvironmentalReportModels.EvidenceType.GIS_OBSERVATION));
        assertTrue(result.evidence().stream().allMatch(item -> item.evidenceId().startsWith("GIS-M2-")));
        assertTrue(result.evidence().stream().anyMatch(item -> item.metric().equals("lulcExposureKm:FOREST")));
        assertTrue(result.evidence().stream().anyMatch(item -> item.metric().equals("protectedAreaRasterNoDataSamples")));
        assertTrue(result.evidence().stream().anyMatch(item -> item.metric().equals("protectedAreaExposureKm")
                && item.limitations().stream().anyMatch(limitation -> limitation.contains("Some protected-area raster samples had no data"))));
        assertTrue(result.notAssessedLayers().contains("WILDLIFE_CORRIDORS_AND_ESZ"));
        assertFalse(result.metadata().containsKey("sourceGeometryModified") && Boolean.TRUE.equals(result.metadata().get("sourceGeometryModified")));
        assertEquals(FacilityMode.NATIONAL_HIGHWAY, screenedMode.get());
        assertEquals(new GeoPoint(26.20, 93.30), screenedRoute.get().get(0));
    }

    @Test
    void rejectsCoordinatesOutsideChosenRegionalDatasetAndInvalidGeoJsonPairs() throws Exception {
        var projects = new Mode2ProjectDocumentService(new Mode2ProjectProperties(directory), new PageTextChunker());
        var project = projects.createProject("Existing Highway");
        var registry = new RegionalCorridorRegistry();
        var service = new Mode2AlignmentScreeningService(projects, registry, (region, route, mode) -> Map.of(),
                new Mode2GisEvidenceAssembler());
        var outside = new Mode2AlignmentScreeningService.AlignmentScreeningRequest("Outside", "assam", null,
                List.of(List.of(93.30, 26.20), List.of(94.50, 26.30)));
        var invalid = new Mode2AlignmentScreeningService.AlignmentScreeningRequest("Invalid", "assam", null,
                List.of(List.of(93.30), List.of(93.40, 26.30)));

        assertThrows(IllegalArgumentException.class, () -> service.screen(project.projectId(), outside));
        assertThrows(IllegalArgumentException.class, () -> service.screen(project.projectId(), invalid));
    }
}
