package in.marg.eia;

import in.marg.model.GeoPoint;
import in.marg.region.RegionalCorridorPreset;
import in.marg.region.RegionalCorridorRegistry;
import in.marg.transport.FacilityMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import in.marg.eia.EnvironmentalReportModels.EvidenceItem;

/** Runs the existing composite GIS diagnostics against an existing project's supplied alignment geometry. */
@Service
public class Mode2AlignmentScreeningService {

    private static final int MAX_VERTICES = 20_000;
    private static final List<LayerDiagnostic> EXPECTED_DIAGNOSTICS = List.of(
            new LayerDiagnostic("lulcExposureKm", "LAND_COVER"),
            new LayerDiagnostic("floodExposureKm", "FLOOD_HAZARD"),
            new LayerDiagnostic("slopeExposureKm", "SLOPE"),
            new LayerDiagnostic("protectedAreaExposureKm", "PROTECTED_AREAS"),
            new LayerDiagnostic("settlementExposureKm", "SETTLEMENT_EXPOSURE"),
            new LayerDiagnostic("waterBodyExposureKm", "WATER_BODIES"),
            new LayerDiagnostic("riverBridgeCrossingSummary", "RIVER_NETWORK"),
            new LayerDiagnostic("wildlifeZoneExposureKm", "WILDLIFE_CORRIDORS_AND_ESZ"),
            new LayerDiagnostic("wildlifePassageCrossingSummary", "WILDLIFE_CORRIDORS"),
            new LayerDiagnostic("geotechHazardExposureKm", "GEOTECH_HAZARDS"),
            new LayerDiagnostic("drainageWetlandExposureKm", "DRAINAGE_AND_WETLANDS"),
            new LayerDiagnostic("drainageAndWetlandSummary", "DRAINAGE_AND_WETLANDS"),
            new LayerDiagnostic("utilityCorridorExposureKm", "UTILITY_CORRIDORS"),
            new LayerDiagnostic("utilityCrossingSummary", "UTILITY_CORRIDORS"),
            new LayerDiagnostic("buildingDensityExposureKm", "BUILDING_DENSITY"),
            new LayerDiagnostic("buildingAndSettlementRowImpactSummary", "BUILDING_AND_SETTLEMENTS"),
            new LayerDiagnostic("populationDensityExposureKm", "POPULATION_DENSITY"),
            new LayerDiagnostic("populationAndDemographicExposureSummary", "POPULATION_DEMOGRAPHICS"),
            new LayerDiagnostic("transportCrossingSummary", "TRANSPORT_NETWORK"));

    private final Mode2ProjectDocumentService projects;
    private final RegionalCorridorRegistry corridors;
    private final Mode2GisMetricsProvider gisMetrics;
    private final Mode2GisEvidenceAssembler evidenceAssembler;

    public Mode2AlignmentScreeningService(Mode2ProjectDocumentService projects,
                                          RegionalCorridorRegistry corridors,
                                          Mode2GisMetricsProvider gisMetrics,
                                          Mode2GisEvidenceAssembler evidenceAssembler) {
        this.projects = projects;
        this.corridors = corridors;
        this.gisMetrics = gisMetrics;
        this.evidenceAssembler = evidenceAssembler;
    }

    public AlignmentScreeningResult screen(String projectId, AlignmentScreeningRequest request) throws IOException {
        if (request == null) throw new IllegalArgumentException("Alignment screening request is required.");
        Mode2ProjectDocumentService.ProjectRecord project = projects.getProject(projectId);
        String alignmentName = request.alignmentName() == null ? "" : request.alignmentName().trim();
        if (alignmentName.isBlank() || alignmentName.length() > 200) {
            throw new IllegalArgumentException("alignmentName must contain 1 to 200 characters.");
        }
        List<GeoPoint> route = validateCoordinates(request.coordinates());
        RegionalCorridorPreset preset = choosePreset(request.regionId(), route);
        double marginDeg = 0.20;
        for (GeoPoint point : route) {
            if (!preset.contains(point) && !RegionalCorridorRegistry.containsWithMargin(preset.studyRegion(), point, marginDeg)) {
                throw new IllegalArgumentException("Every alignment vertex must lie inside or near the selected GIS region '"
                        + preset.id() + "' (within " + marginDeg + "° margin). No geometry was clipped or moved.");
            }
        }
        FacilityMode facilityMode = FacilityMode.fromParam(request.facilityMode(), FacilityMode.NATIONAL_HIGHWAY);
        Map<String, Object> diagnostics = gisMetrics.summarize(preset.id(), route, facilityMode);
        String fingerprint = fingerprint(route);
        List<EvidenceItem> evidence = evidenceAssembler.assemble(projectId, preset.id(), fingerprint, diagnostics);
        Set<String> availableKeys = diagnostics.keySet();
        List<String> notAssessed = EXPECTED_DIAGNOSTICS.stream()
                .filter(item -> !availableKeys.contains(item.key()))
                .map(LayerDiagnostic::layer).distinct().toList();
        List<String> limitations = List.of(
                "This is preliminary GIS screening of the supplied alignment, not a route optimization or recommendation.",
                "Route exposure values are diagnostics from the configured regional layers; they are not field measurements or legal findings.",
                "The response lists diagnostics not emitted by the active pipeline; missing output does not establish that a receptor or impact is absent.",
                "The analysis uses the selected regional preset only. Dataset vintage and positional accuracy are not reported by the current GIS response.",
                "The supplied geometry is not clipped, snapped, or altered; its coordinates must be authoritative WGS84 longitude/latitude.");
        Map<String, Object> method = evidenceAssembler.methodSummary(diagnostics);
        Map<String, Object> metadata = Map.of("coordinateReferenceSystem", "EPSG:4326",
                "gisRegionCrs", preset.epsgCode(), "regionId", preset.id(), "facilityMode", facilityMode.name(),
                "vertexCount", route.size(), "routeFingerprint", fingerprint, "generatedAt", Instant.now().toString(),
                "sourceGeometryModified", false);
        return new AlignmentScreeningResult(project.projectId(), project.name(), alignmentName, preset.id(),
                preset.displayName(), facilityMode.name(), route.size(), fingerprint, metadata, method,
                diagnostics, evidence, availableLayerNames(availableKeys), notAssessed, limitations);
    }

    private RegionalCorridorPreset choosePreset(String requestedRegion, List<GeoPoint> route) {
        if (requestedRegion != null && !requestedRegion.isBlank()) {
            return corridors.getPreset(requestedRegion);
        }
        RegionalCorridorPreset preset = corridors.findContainingPreset(route.get(0), route.get(route.size() - 1));
        if (preset == null) {
            for (RegionalCorridorPreset candidate : corridors.listPresets()) {
                if (RegionalCorridorRegistry.containsWithMargin(candidate.studyRegion(), route.get(0), 0.25)
                        && RegionalCorridorRegistry.containsWithMargin(candidate.studyRegion(), route.get(route.size() - 1), 0.25)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("No configured regional GIS preset contains this alignment's endpoints. "
                    + "Select an ingested corridor region or prepare regional layers before screening.");
        }
        return preset;
    }

    private List<String> availableLayerNames(Set<String> keys) {
        LinkedHashSet<String> layers = new LinkedHashSet<>();
        for (LayerDiagnostic item : EXPECTED_DIAGNOSTICS) if (keys.contains(item.key())) layers.add(item.layer());
        return List.copyOf(layers);
    }

    private List<GeoPoint> validateCoordinates(List<List<Double>> coordinates) {
        if (coordinates == null || coordinates.size() < 2 || coordinates.size() > MAX_VERTICES) {
            throw new IllegalArgumentException("GeoJSON LineString coordinates must contain 2 to " + MAX_VERTICES + " vertices.");
        }
        List<GeoPoint> route = new ArrayList<>(coordinates.size());
        boolean hasLength = false;
        GeoPoint previous = null;
        for (int index = 0; index < coordinates.size(); index++) {
            List<Double> pair = coordinates.get(index);
            if (pair == null || pair.size() < 2 || pair.get(0) == null || pair.get(1) == null) {
                throw new IllegalArgumentException("Each GeoJSON coordinate must contain [longitude, latitude].");
            }
            double lon = pair.get(0);
            double lat = pair.get(1);
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                throw new IllegalArgumentException("Alignment contains a non-finite or out-of-range WGS84 coordinate.");
            }
            GeoPoint point = new GeoPoint(lat, lon);
            route.add(point);
            if (previous != null && (Math.abs(previous.lat() - lat) > 1e-12 || Math.abs(previous.lon() - lon) > 1e-12)) {
                hasLength = true;
            }
            previous = point;
        }
        if (!hasLength) throw new IllegalArgumentException("Alignment geometry must have non-zero length.");
        return List.copyOf(route);
    }

    private static String fingerprint(List<GeoPoint> route) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (GeoPoint point : route) {
                String coordinate = Double.toHexString(point.lon()) + "," + Double.toHexString(point.lat()) + ";";
                digest.update(coordinate.getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest()).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public record AlignmentScreeningRequest(String alignmentName, String regionId, String facilityMode,
                                            List<List<Double>> coordinates) {}

    public record AlignmentScreeningResult(String projectId, String projectName, String alignmentName,
                                           String regionId, String regionDisplayName, String facilityMode,
                                           int vertexCount, String routeFingerprint, Map<String, Object> metadata,
                                           Map<String, Object> method, Map<String, Object> diagnostics,
                                           List<EvidenceItem> evidence, List<String> assessedLayers,
                                           List<String> notAssessedLayers, List<String> limitations) {}

    private record LayerDiagnostic(String key, String layer) {}
}
