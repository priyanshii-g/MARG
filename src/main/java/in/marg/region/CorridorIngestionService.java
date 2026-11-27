package in.marg.region;

import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.spatial.Wgs84UtmProjection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * On-Demand Corridor Ingestion Service for Tier-1 to Tier-2 Dynamic Materialization.
 *
 * <p>Allows evaluators to specify any arbitrary corridor or bounding box across India.
 * The ingestion service automatically:
 * <ol>
 *   <li>Determines the optimal metric UTM Zone (42N..47N / EPSG:32642..32647).</li>
 *   <li>Runs the background 13-layer ingestion pipeline to generate georeferenced GeoTIFFs,
 *       vector spatial indices, Bhuvan LULC sample catalogs, and flood hazard mosaics.</li>
 *   <li>Dynamically registers the synthesized corridor as a benchmark preset in
 *       {@link RegionalCorridorRegistry} so it immediately supports full 13-layer routing.</li>
 * </ol>
 */
@Service
public class CorridorIngestionService {

    private final RegionalCorridorRegistry registry;

    @Autowired
    public CorridorIngestionService(RegionalCorridorRegistry registry) {
        this.registry = registry;
    }

    public record IngestionRequest(
            String regionId,
            String displayName,
            String stateOrTerritory,
            String physiographicZone,
            Double startLat,
            Double startLon,
            Double endLat,
            Double endLon,
            Double minLat,
            Double minLon,
            Double maxLat,
            Double maxLon
    ) {}

    public record IngestionResult(
            String status,
            String message,
            String regionId,
            String displayName,
            int utmZone,
            String coordinateReferenceSystem,
            Map<String, Object> bbox,
            Map<String, Object> origin,
            Map<String, Object> destination,
            String sampleRoutingUrl,
            int totalLayersIngested
    ) {}

    public IngestionResult ingestCorridor(IngestionRequest request) {
        if (request.regionId() == null || request.regionId().isBlank()) {
            throw new IllegalArgumentException("regionId is required (e.g. 'bundelkhand')");
        }

        String normalizedId = RegionalCorridorRegistry.normalizeRegionId(request.regionId());
        if ("pan_india".equals(normalizedId)) {
            throw new IllegalArgumentException("Cannot overwrite global 'pan_india' corridor ID");
        }

        double startLat = request.startLat() != null ? request.startLat() : 25.45;
        double startLon = request.startLon() != null ? request.startLon() : 78.58;
        double endLat = request.endLat() != null ? request.endLat() : 25.48;
        double endLon = request.endLon() != null ? request.endLon() : 80.34;

        validatePanIndiaCoordinate(startLat, startLon, "start");
        validatePanIndiaCoordinate(endLat, endLon, "end");

        double bufferDeg = 0.25;
        double minLat = request.minLat() != null ? request.minLat() : Math.min(startLat, endLat) - bufferDeg;
        double minLon = request.minLon() != null ? request.minLon() : Math.min(startLon, endLon) - bufferDeg;
        double maxLat = request.maxLat() != null ? request.maxLat() : Math.max(startLat, endLat) + bufferDeg;
        double maxLon = request.maxLon() != null ? request.maxLon() : Math.max(startLon, endLon) + bufferDeg;

        double midLon = (minLon + maxLon) / 2.0;
        int utmZone = Wgs84UtmProjection.indiaUtmZoneForLongitude(midLon);
        String crs = "EPSG:" + (32600 + utmZone);

        String displayName = request.displayName() != null && !request.displayName().isBlank()
                ? request.displayName()
                : toTitleCase(normalizedId) + " Ingested 13-Layer Corridor";

        String state = request.stateOrTerritory() != null && !request.stateOrTerritory().isBlank()
                ? request.stateOrTerritory()
                : "India (UTM Zone " + utmZone + "N)";

        String physioZone = request.physiographicZone() != null && !request.physiographicZone().isBlank()
                ? request.physiographicZone()
                : "PAN_INDIA_DYNAMIC_INGESTED_CORRIDOR";

        // Execute python scripts/ingest_corridor.py
        runPythonIngestion(normalizedId, displayName, minLat, minLon, maxLat, maxLon,
                startLat, startLon, endLat, endLon, state, physioZone);

        // Build and register RegionalCorridorPreset
        Path regionDir = Path.of("data/processed/regions", normalizedId);
        if (!Files.exists(regionDir)) {
            throw new IllegalStateException("Ingestion failed: directory not found at " + regionDir);
        }

        RegionalCorridorPreset preset = new RegionalCorridorPreset(
                normalizedId,
                "CORRIDOR-" + normalizedId.toUpperCase(),
                displayName,
                state,
                physioZone,
                "On-Demand Ingested 13-Layer Corridor with complete GeoTIFF rasters and spatial indices.",
                utmZone,
                crs,
                new StudyRegion(
                        displayName,
                        minLat, minLon, maxLat, maxLon,
                        new GeoPoint(startLat, startLon),
                        new GeoPoint(endLat, endLon)
                ),
                regionDir.resolve(normalizedId + "_lulc_samples.json").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_slope_utm" + utmZone + ".tif").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_protected_area_mask_utm" + utmZone + ".tif").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_settlement_exposure_utm" + utmZone + ".tif").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_settlement_boundaries.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_transport_network_utm" + utmZone + ".tif").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_transport_network.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_regional_hubs.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_water_bodies_utm" + utmZone + ".tif").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_perennial_rivers.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_wetlands_polygons.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_wildlife_esz_corridors_utm" + utmZone + ".tif").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_wildlife_corridors.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_geotech_landslide_utm" + utmZone + ".tif").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_fault_landslide_zones.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_utility_corridors_utm" + utmZone + ".tif").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_power_transmission_lines.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_utility_assets.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_building_density_utm" + utmZone + ".tif").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_settlement_boundaries.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_building_footprints.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_population_density_utm" + utmZone + ".tif").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_population_demographics.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_drainage_wetland_utm" + utmZone + ".tif").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_wetlands_polygons.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_drainage_network.geojson").toString().replace('\\', '/'),
                regionDir.resolve(normalizedId + "_watershed_basins.geojson").toString().replace('\\', '/')
        );

        registry.registerCustomPreset(preset);

        Map<String, Object> bboxMap = new LinkedHashMap<>();
        bboxMap.put("minLat", minLat);
        bboxMap.put("minLon", minLon);
        bboxMap.put("maxLat", maxLat);
        bboxMap.put("maxLon", maxLon);

        return new IngestionResult(
                "SUCCESS",
                "Corridor '" + normalizedId + "' successfully ingested with 13 layers into UTM Zone " + utmZone + "N (" + crs + ").",
                normalizedId,
                displayName,
                utmZone,
                crs,
                bboxMap,
                Map.of("lat", startLat, "lon", startLon),
                Map.of("lat", endLat, "lon", endLon),
                "/api/routes/composite?region=" + normalizedId + "&facilityMode=EXPRESSWAY",
                13
        );
    }

    private static String resolvePythonCommand() {
        for (String cmd : List.of("python3", "python")) {
            try {
                if (new ProcessBuilder(cmd, "--version").start().waitFor() == 0) {
                    return cmd;
                }
            } catch (Exception ignored) {}
        }
        return "python3";
    }

    private void runPythonIngestion(
            String id, String name, double minLat, double minLon, double maxLat, double maxLon,
            double startLat, double startLon, double endLat, double endLon, String state, String physioZone) {

        ProcessBuilder pb = new ProcessBuilder(
                resolvePythonCommand(),
                "scripts/ingest_corridor.py",
                "--id", id,
                "--name", name,
                "--minLat", String.valueOf(minLat),
                "--minLon", String.valueOf(minLon),
                "--maxLat", String.valueOf(maxLat),
                "--maxLon", String.valueOf(maxLon),
                "--startLat", String.valueOf(startLat),
                "--startLon", String.valueOf(startLon),
                "--endLat", String.valueOf(endLat),
                "--endLon", String.valueOf(endLon),
                "--state", state,
                "--physioZone", physioZone
        );
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            boolean finished = process.waitFor(180, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Corridor ingestion script timed out after 180s");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Corridor ingestion script failed (code "
                        + process.exitValue() + "): " + output);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to run ingestion pipeline: " + ex.getMessage(), ex);
        }
    }

    private static void validatePanIndiaCoordinate(double lat, double lon, String label) {
        if (lat < 6.5 || lat > 37.5 || lon < 68.0 || lon > 97.5) {
            throw new IllegalArgumentException(label + " coordinate (" + lat + ", " + lon
                    + ") is outside India mainland bounds (lat 6.5..37.5 N, lon 68.0..97.5 E)");
        }
    }

    private static String toTitleCase(String str) {
        if (str == null || str.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String word : str.split("_")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase())
                        .append(" ");
            }
        }
        return sb.toString().trim();
    }
}
