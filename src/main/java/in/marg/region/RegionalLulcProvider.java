package in.marg.region;

import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.provider.LulcProvider;
import in.marg.provider.LulcSample;
import in.marg.routing.LulcClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazy-loaded regional LULC provider backed by a local pre-cached sample catalog
 * with deterministic physiographic fallback so arbitrary start/end clicks inside
 * a corridor region never block on remote WMS calls.
 */
public class RegionalLulcProvider implements LulcProvider {

    private final String regionId;
    private final String datasetName;
    private final String cacheFilePath;
    private final double resolutionDeg;
    private final ObjectMapper mapper;
    private final Map<String, LulcClass> gridCache = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    public RegionalLulcProvider(
            String regionId,
            String datasetName,
            String cacheFilePath,
            double resolutionDeg,
            ObjectMapper mapper) {
        this.regionId = regionId;
        this.datasetName = datasetName;
        this.cacheFilePath = cacheFilePath;
        this.resolutionDeg = resolutionDeg > 0.0 ? resolutionDeg : 0.01;
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public boolean isInitialized() {
        return initialized;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            try {
                Path path = Path.of(cacheFilePath);
                if (Files.exists(path)) {
                    JsonNode root = mapper.readTree(path.toFile());
                    if (root != null && root.isArray()) {
                        for (JsonNode node : root) {
                            double lat = node.path("lat").asDouble(Double.NaN);
                            double lon = node.path("lon").asDouble(Double.NaN);
                            String typeStr = node.path("type").asText("AGRICULTURE");
                            if (Double.isFinite(lat) && Double.isFinite(lon)) {
                                LulcClass lulcClass;
                                try {
                                    lulcClass = LulcClass.valueOf(typeStr);
                                } catch (IllegalArgumentException ex) {
                                    lulcClass = LulcClass.AGRICULTURE;
                                }
                                gridCache.put(gridKey(lat, lon), lulcClass);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // Fallback deterministic classification remains available if cache file is absent
            }
            initialized = true;
        }
    }

    @Override
    public LulcSample sample(GeoPoint point) {
        ensureInitialized();
        double snappedLat = snap(point.lat());
        double snappedLon = snap(point.lon());
        String key = gridKey(snappedLat, snappedLon);
        LulcClass cls = gridCache.computeIfAbsent(key, k -> deterministicRegionalClass(snappedLat, snappedLon));
        return new LulcSample(snappedLat, snappedLon, cls, providerId(), true);
    }

    @Override
    public List<LulcSample> preload(StudyRegion region, int maxSamples) {
        ensureInitialized();
        List<LulcSample> out = new ArrayList<>();
        double step = Math.max(resolutionDeg, 0.01);
        int limit = Math.max(1, maxSamples);
        for (double lat = snap(region.minLat()); lat <= region.maxLat() + 1e-9 && out.size() < limit; lat += step) {
            for (double lon = snap(region.minLon()); lon <= region.maxLon() + 1e-9 && out.size() < limit; lon += step) {
                out.add(sample(new GeoPoint(lat, lon)));
            }
        }
        return out;
    }

    @Override
    public String providerId() {
        return "bhuvan:regional-lulc-50k:" + regionId;
    }

    @Override
    public double sampleResolution() {
        return resolutionDeg;
    }

    @Override
    public String datasetId() {
        return datasetName;
    }

    private double snap(double coord) {
        return Math.round(coord / resolutionDeg) * resolutionDeg;
    }

    private String gridKey(double lat, double lon) {
        long rLat = Math.round(lat / resolutionDeg);
        long rLon = Math.round(lon / resolutionDeg);
        return rLat + ":" + rLon;
    }

    private LulcClass deterministicRegionalClass(double lat, double lon) {
        if ("western_ghats".equalsIgnoreCase(regionId)) {
            if (lon < 73.76) {
                return LulcClass.FOREST;
            }
            if (Math.abs(lat - 18.14) < 0.025 && Math.abs(lon - 73.84) < 0.04) {
                return LulcClass.WETLAND_WATER;
            }
            return LulcClass.AGRICULTURE;
        }
        if ("gangetic_plains".equalsIgnoreCase(regionId)) {
            if (Math.abs(lat - (27.08 - 0.30 * (lon - 81.10))) < 0.02) {
                return LulcClass.WETLAND_WATER;
            }
            return LulcClass.AGRICULTURE;
        }
        double wave = Math.sin(lat * 25.0) * Math.cos(lon * 25.0);
        if (wave > 0.65) {
            return LulcClass.FOREST;
        }
        if (wave < -0.75) {
            return LulcClass.WETLAND_WATER;
        }
        return LulcClass.AGRICULTURE;
    }
}
