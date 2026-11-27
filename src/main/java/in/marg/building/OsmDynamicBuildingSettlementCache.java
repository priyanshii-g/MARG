package in.marg.building;

import in.marg.model.GeoPoint;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk-cached OpenStreetMap (OSM) Dynamic Corridor Building, Hospital &amp; Settlement Indexer
 * for Tier-1 Pan-India Dynamic Routes.
 *
 * <p>Queries Overpass API for real OSM:
 * <ul>
 *   <li>Hospitals, clinics, universities, colleges, schools, and places of worship (Hard-Blocked + 50-100m buffer),</li>
 *   <li>Residential/commercial/industrial/institutional colonies and town/village/suburb/neighbourhood zones,</li>
 *   <li>Building footprints/clusters along the corridor and terminal access zones.</li>
 * </ul>
 * Responses are persisted to {@code data/cache/osm-buildings/} so repeated runs execute in milliseconds
 * completely offline. If Overpass is unreachable or times out, degrades gracefully without failing routing.
 */
public class OsmDynamicBuildingSettlementCache {

    private static final Path DEFAULT_CACHE_DIR = Path.of("data/cache/osm-buildings");
    private static final List<String> OVERPASS_MIRRORS = List.of(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
    );
    private static final double BUCKET_METERS = 1000.0;
    private static final int MAX_CORRIDOR_TILES = 6;

    private static final OsmDynamicBuildingSettlementCache DEFAULT_INSTANCE =
            new OsmDynamicBuildingSettlementCache(DEFAULT_CACHE_DIR);

    private final Path cacheDir;
    private final ObjectMapper objectMapper;
    private volatile HttpClient httpClient;
    private final ConcurrentHashMap<String, List<RawOsmFeature>> memoryTileCache = new ConcurrentHashMap<>();
    private volatile long lastOfflineDetectionMillis = 0L;

    public OsmDynamicBuildingSettlementCache(Path cacheDir) {
        this.cacheDir = cacheDir != null ? cacheDir : DEFAULT_CACHE_DIR;
        this.objectMapper = new ObjectMapper();
    }

    private HttpClient getClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    try {
                        httpClient = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(3))
                                .build();
                    } catch (Throwable t) {
                        lastOfflineDetectionMillis = System.currentTimeMillis();
                        return null;
                    }
                }
            }
        }
        return httpClient;
    }

    public static OsmDynamicBuildingSettlementCache getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Loads or fetches (with local disk caching) OSM hospitals, institutional/religious campuses,
     * settlement/colony polygons, and building clusters for a dynamic Pan-India corridor.
     */
    public OsmCorridorIndex indexForCorridor(
            GeoPoint start,
            GeoPoint goal,
            CoordinateProjection projection) {
        if (start == null || goal == null || projection == null) {
            return OsmCorridorIndex.empty(projection);
        }
        // Build compact sample tiles around start, goal, and intermediate points along the corridor
        List<double[]> windows = buildCorridorWindows(start, goal);
        List<RawOsmFeature> merged = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (double[] win : windows) {
            List<RawOsmFeature> tileFeatures = loadOrFetchTile(win[0], win[1], win[2], win[3]);
            for (RawOsmFeature f : tileFeatures) {
                if (seenIds.add(f.osmId())) {
                    merged.add(f);
                }
            }
        }
        return new OsmCorridorIndex(projection, merged);
    }

    private List<double[]> buildCorridorWindows(GeoPoint start, GeoPoint goal) {
        List<double[]> tiles = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        double spanDeg = Math.hypot(goal.lat() - start.lat(), goal.lon() - start.lon());
        int samples = Math.max(2, Math.min(MAX_CORRIDOR_TILES, (int) Math.ceil(spanDeg / 0.25) + 1));

        for (int i = 0; i < samples; i++) {
            double frac = samples == 1 ? 0.0 : (double) i / (samples - 1);
            double lat = start.lat() + frac * (goal.lat() - start.lat());
            double lon = start.lon() + frac * (goal.lon() - start.lon());
            // Quantize to 0.20 deg (~22 km) grid cells for high cache reuse across queries
            double minLat = Math.floor(lat / 0.20) * 0.20;
            double minLon = Math.floor(lon / 0.20) * 0.20;
            double maxLat = minLat + 0.20;
            double maxLon = minLon + 0.20;
            String key = String.format(Locale.ROOT, "%.2f_%.2f", minLat, minLon);
            if (keys.add(key)) {
                tiles.add(new double[]{minLat, minLon, maxLat, maxLon});
            }
        }
        return tiles;
    }

    private List<RawOsmFeature> loadOrFetchTile(double minLat, double minLon, double maxLat, double maxLon) {
        String key = String.format(Locale.ROOT, "tile_%.2f_%.2f_%.2f_%.2f", minLat, minLon, maxLat, maxLon)
                .replace('.', 'p');
        List<RawOsmFeature> inMem = memoryTileCache.get(key);
        if (inMem != null) {
            return inMem;
        }

        Path diskFile = cacheDir.resolve(key + ".json");
        if (Files.exists(diskFile)) {
            try {
                JsonNode root = objectMapper.readTree(diskFile.toFile());
                List<RawOsmFeature> loaded = parseCachedFeatures(root);
                memoryTileCache.put(key, loaded);
                return loaded;
            } catch (Exception ignored) {
            }
        }

        // Fetch from Overpass API with short timeout; degrade gracefully if offline
        List<RawOsmFeature> fetched = fetchTileFromOverpass(minLat, minLon, maxLat, maxLon);
        memoryTileCache.put(key, fetched);
        if (!fetched.isEmpty()) {
            persistTileToDisk(diskFile, fetched);
        }
        return fetched;
    }

    private List<RawOsmFeature> fetchTileFromOverpass(double minLat, double minLon, double maxLat, double maxLon) {
        if (System.currentTimeMillis() - lastOfflineDetectionMillis < 30_000L) {
            return List.of();
        }
        HttpClient client = getClient();
        if (client == null) {
            return List.of();
        }
        String bbox = String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", minLat, minLon, maxLat, maxLon);
        String query = "[out:json][timeout:12];("
                + "nwr[\"amenity\"~\"^(hospital|clinic|university|college|school|place_of_worship)$\"](" + bbox + ");"
                + "nwr[\"building\"=\"hospital\"](" + bbox + ");"
                + "nwr[\"healthcare\"=\"hospital\"](" + bbox + ");"
                + "wr[\"landuse\"~\"^(residential|commercial|industrial|retail|institutional)$\"](" + bbox + ");"
                + "nwr[\"place\"~\"^(town|village|suburb|neighbourhood)$\"](" + bbox + ");"
                + "way[\"building\"](" + bbox + ");"
                + ");out geom 1800;";

        String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        for (String mirror : OVERPASS_MIRRORS) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(mirror))
                        .timeout(Duration.ofSeconds(6))
                        .header("User-Agent", "MARG-PanIndia-DynamicOSM/3.0")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                    JsonNode root = objectMapper.readTree(resp.body());
                    return parseOverpassElements(root.get("elements"));
                }
            } catch (Exception e) {
                // If network is offline, unresolvable, or timed out, flag offline mode and avoid waiting on remaining mirrors
                lastOfflineDetectionMillis = System.currentTimeMillis();
                break;
            }
        }
        return List.of();
    }

    private List<RawOsmFeature> parseOverpassElements(JsonNode elements) {
        if (elements == null || !elements.isArray()) {
            return List.of();
        }
        List<RawOsmFeature> out = new ArrayList<>();
        for (JsonNode el : elements) {
            String type = el.has("type") ? el.get("type").asString("") : "";
            long id = el.has("id") ? el.get("id").asLong(0) : 0;
            JsonNode tags = el.get("tags");
            if (tags == null || !tags.isObject()) {
                continue;
            }
            String amenity = tag(tags, "amenity");
            String healthcare = tag(tags, "healthcare");
            String building = tag(tags, "building");
            String landuse = tag(tags, "landuse");
            String place = tag(tags, "place");
            String name = tag(tags, "name");
            if (name.isBlank()) {
                name = tag(tags, "name:en");
            }

            FeatureKind kind;
            String category;
            double fallbackRadiusM;

            if (amenity.equals("hospital") || amenity.equals("clinic")
                    || healthcare.equals("hospital") || building.equals("hospital")) {
                kind = FeatureKind.INSTITUTIONAL_HARD;
                category = "HOSPITAL_MEDICAL_CAMPUS";
                fallbackRadiusM = amenity.equals("clinic") ? 45.0 : 95.0;
                if (name.isBlank()) name = "Hospital / Medical Campus";
            } else if (amenity.equals("place_of_worship")) {
                kind = FeatureKind.INSTITUTIONAL_HARD;
                category = "RELIGIOUS_PRECINCT";
                fallbackRadiusM = 45.0;
                if (name.isBlank()) name = "Religious Precinct";
            } else if (amenity.equals("university") || amenity.equals("college") || amenity.equals("school")
                    || landuse.equals("institutional")) {
                kind = FeatureKind.INSTITUTIONAL_HARD;
                category = "INSTITUTIONAL_CAMPUS";
                fallbackRadiusM = amenity.equals("school") ? 55.0 : 100.0;
                if (name.isBlank()) name = "Educational / Institutional Campus";
            } else if (landuse.equals("residential") || landuse.equals("commercial")
                    || landuse.equals("retail") || landuse.equals("industrial")
                    || place.equals("town") || place.equals("suburb") || place.equals("neighbourhood")) {
                kind = FeatureKind.DENSE_COLONY;
                category = landuse.equals("industrial") ? "INDUSTRIAL_COMPLEX" : "DENSE_RESIDENTIAL_COLONY";
                fallbackRadiusM = place.equals("town") ? 550.0 : (place.equals("suburb") ? 400.0 : 250.0);
                if (name.isBlank()) name = "Residential / Urban Colony";
            } else if (place.equals("village")) {
                kind = FeatureKind.RURAL_SETTLEMENT;
                category = "RURAL_HABITATION";
                fallbackRadiusM = 220.0;
                if (name.isBlank()) name = "Rural Village Habitation";
            } else if (!building.isBlank()) {
                kind = FeatureKind.BUILDING;
                category = building.equals("commercial") || building.equals("retail")
                        ? "COMMERCIAL"
                        : (building.equals("industrial") || building.equals("warehouse")
                        ? "INDUSTRIAL" : "RESIDENTIAL");
                fallbackRadiusM = 10.0;
                if (name.isBlank()) name = "OSM Building #" + id;
            } else {
                continue;
            }

            List<double[]> ring = extractRing(el, type, fallbackRadiusM);
            if (ring == null || ring.size() < 4) {
                continue;
            }
            double sumLat = 0.0, sumLon = 0.0;
            int n = ring.size() - 1;
            for (int i = 0; i < n; i++) {
                sumLon += ring.get(i)[0];
                sumLat += ring.get(i)[1];
            }
            double clat = sumLat / n;
            double clon = sumLon / n;
            int floors = 1;
            try {
                String lv = tag(tags, "building:levels");
                if (!lv.isBlank()) {
                    floors = Math.max(1, Math.min(12, (int) Math.round(Double.parseDouble(lv.split(";")[0]))));
                }
            } catch (Exception ignored) {
            }
            out.add(new RawOsmFeature(type + "/" + id, name, kind, category, clat, clon, floors, ring));
        }
        return out;
    }

    private static List<double[]> extractRing(JsonNode el, String type, double fallbackRadiusM) {
        if ("node".equals(type) && el.has("lat") && el.has("lon")) {
            return makeOctagon(el.get("lat").asDouble(), el.get("lon").asDouble(), fallbackRadiusM);
        }
        JsonNode geom = el.get("geometry");
        if (geom != null && geom.isArray() && geom.size() >= 3) {
            List<double[]> pts = new ArrayList<>(geom.size() + 1);
            for (JsonNode g : geom) {
                if (g.has("lat") && g.has("lon")) {
                    pts.add(new double[]{g.get("lon").asDouble(), g.get("lat").asDouble()});
                }
            }
            if (pts.size() >= 3) {
                double[] first = pts.get(0);
                double[] last = pts.get(pts.size() - 1);
                if (first[0] != last[0] || first[1] != last[1]) {
                    pts.add(new double[]{first[0], first[1]});
                }
                return pts;
            }
        }
        JsonNode bounds = el.get("bounds");
        if (bounds != null && bounds.has("minlat") && bounds.has("maxlat")) {
            double minLat = bounds.get("minlat").asDouble();
            double maxLat = bounds.get("maxlat").asDouble();
            double minLon = bounds.get("minlon").asDouble();
            double maxLon = bounds.get("maxlon").asDouble();
            if ((maxLat - minLat) < 0.08 && (maxLon - minLon) < 0.08) {
                return List.of(
                        new double[]{minLon, minLat},
                        new double[]{maxLon, minLat},
                        new double[]{maxLon, maxLat},
                        new double[]{minLon, maxLat},
                        new double[]{minLon, minLat}
                );
            }
        }
        return null;
    }

    private static List<double[]> makeOctagon(double clat, double clon, double radiusM) {
        double dLat = radiusM / 111111.0;
        double dLon = radiusM / Math.max(10000.0, 111111.0 * Math.cos(Math.toRadians(clat)));
        List<double[]> ring = new ArrayList<>(9);
        for (int i = 0; i < 8; i++) {
            double ang = (2.0 * Math.PI * i) / 8.0;
            ring.add(new double[]{clon + dLon * Math.cos(ang), clat + dLat * Math.sin(ang)});
        }
        ring.add(ring.get(0));
        return ring;
    }

    private static String tag(JsonNode tags, String key) {
        return tags.has(key) && !tags.get(key).isNull()
                ? tags.get(key).asString("").trim().toLowerCase(Locale.ROOT)
                : "";
    }

    private void persistTileToDisk(Path file, List<RawOsmFeature> features) {
        try {
            Files.createDirectories(file.getParent());
            List<Map<String, Object>> serialized = new ArrayList<>(features.size());
            for (RawOsmFeature f : features) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("osmId", f.osmId());
                m.put("name", f.name());
                m.put("kind", f.kind().name());
                m.put("category", f.category());
                m.put("clat", f.centroidLat());
                m.put("clon", f.centroidLon());
                m.put("floors", f.floors());
                m.put("ring", f.ringLonLat());
                serialized.add(m);
            }
            objectMapper.writeValue(file.toFile(), Map.of("features", serialized));
        } catch (Exception ignored) {
        }
    }

    private List<RawOsmFeature> parseCachedFeatures(JsonNode root) {
        JsonNode arr = root.get("features");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<RawOsmFeature> out = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            List<double[]> ring = new ArrayList<>();
            JsonNode rNode = n.get("ring");
            if (rNode != null && rNode.isArray()) {
                for (JsonNode pt : rNode) {
                    ring.add(new double[]{pt.get(0).asDouble(), pt.get(1).asDouble()});
                }
            }
            if (ring.size() < 4) continue;
            out.add(new RawOsmFeature(
                    n.get("osmId").asString(),
                    n.get("name").asString(),
                    FeatureKind.valueOf(n.get("kind").asString()),
                    n.get("category").asString(),
                    n.get("clat").asDouble(),
                    n.get("clon").asDouble(),
                    n.has("floors") ? n.get("floors").asInt(1) : 1,
                    ring));
        }
        return out;
    }

    public enum FeatureKind {
        INSTITUTIONAL_HARD,
        DENSE_COLONY,
        RURAL_SETTLEMENT,
        BUILDING
    }

    public record RawOsmFeature(
            String osmId,
            String name,
            FeatureKind kind,
            String category,
            double centroidLat,
            double centroidLon,
            int floors,
            List<double[]> ringLonLat
    ) {}

    public record IndexedOsmPoly(
            String osmId,
            String name,
            FeatureKind kind,
            String category,
            GeoPoint centroid,
            MetricPoint centroidMetric,
            int floors,
            double areaSqM,
            double eqRadiusM,
            List<MetricPoint> metricRing,
            double minX,
            double minY,
            double maxX,
            double maxY
    ) {}

    public record DynamicDemolitionSummary(
            int buildingsInDirectRowCount,
            Map<String, Integer> buildingsByType,
            int estimatedDisplacedHouseholds,
            int estimatedDisplacedPopulation,
            List<IndexedOsmPoly> demolishedBuildings
    ) {}

    /**
     * In-memory spatially bucketed OSM index for a Pan-India corridor.
     */
    public static class OsmCorridorIndex {
        private final CoordinateProjection projection;
        private final Map<Long, List<IndexedOsmPoly>> instBuckets = new HashMap<>();
        private final Map<Long, List<IndexedOsmPoly>> colonyBuckets = new HashMap<>();
        private final Map<Long, List<IndexedOsmPoly>> settlementBuckets = new HashMap<>();
        private final Map<Long, List<IndexedOsmPoly>> buildingBuckets = new HashMap<>();
        private final List<IndexedOsmPoly> allBuildings = new ArrayList<>();
        private final int totalCount;

        public static OsmCorridorIndex empty(CoordinateProjection projection) {
            return new OsmCorridorIndex(projection, List.of());
        }

        public OsmCorridorIndex(CoordinateProjection projection, List<RawOsmFeature> rawFeatures) {
            this.projection = projection;
            this.totalCount = rawFeatures != null ? rawFeatures.size() : 0;
            if (projection == null || rawFeatures == null || rawFeatures.isEmpty()) {
                return;
            }
            for (RawOsmFeature rf : rawFeatures) {
                List<MetricPoint> mRing = new ArrayList<>(rf.ringLonLat().size());
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
                for (double[] pt : rf.ringLonLat()) {
                    MetricPoint mp = projection.forward(new GeoPoint(pt[1], pt[0]));
                    mRing.add(mp);
                    if (mp.x() < minX) minX = mp.x();
                    if (mp.y() < minY) minY = mp.y();
                    if (mp.x() > maxX) maxX = mp.x();
                    if (mp.y() > maxY) maxY = mp.y();
                }
                double area2 = 0.0;
                int n = mRing.size() - 1;
                for (int i = 0; i < n; i++) {
                    MetricPoint p1 = mRing.get(i);
                    MetricPoint p2 = mRing.get((i + 1) % n);
                    area2 += p1.x() * p2.y() - p2.x() * p1.y();
                }
                double areaSqM = Math.max(25.0, Math.abs(area2) * 0.5);
                double eqR = Math.sqrt(areaSqM / Math.PI);
                GeoPoint cGeo = new GeoPoint(rf.centroidLat(), rf.centroidLon());
                MetricPoint cMet = projection.forward(cGeo);
                IndexedOsmPoly poly = new IndexedOsmPoly(
                        rf.osmId(), rf.name(), rf.kind(), rf.category(),
                        cGeo, cMet, rf.floors(), areaSqM, eqR, mRing, minX, minY, maxX, maxY);

                switch (rf.kind()) {
                    case INSTITUTIONAL_HARD -> {
                        insertBuckets(poly, instBuckets, 150.0);
                        insertBuckets(poly, settlementBuckets, 50.0);
                    }
                    case DENSE_COLONY -> {
                        insertBuckets(poly, colonyBuckets, 100.0);
                        insertBuckets(poly, settlementBuckets, 50.0);
                    }
                    case RURAL_SETTLEMENT -> insertBuckets(poly, settlementBuckets, 50.0);
                    case BUILDING -> {
                        allBuildings.add(poly);
                        insertBuckets(poly, buildingBuckets, 60.0);
                    }
                }
            }
        }

        public boolean isEmpty() {
            return totalCount == 0;
        }

        public int totalIndexedFeatures() {
            return totalCount;
        }

        public IndexedOsmPoly findInstitutionalExclusion(GeoPoint point, double bufferMeters) {
            if (instBuckets.isEmpty() || point == null || projection == null) return null;
            MetricPoint mp = projection.forward(point);
            return findInBuckets(mp.x(), mp.y(), bufferMeters, instBuckets);
        }

        public IndexedOsmPoly findInstitutionalExclusionForSegment(GeoPoint from, GeoPoint to, double bufferMeters) {
            if (instBuckets.isEmpty() || from == null || to == null || projection == null) return null;
            MetricPoint a = projection.forward(from);
            MetricPoint b = projection.forward(to);
            return findSegmentInBuckets(a, b, bufferMeters, instBuckets);
        }

        public IndexedOsmPoly findDenseColony(GeoPoint point) {
            if (colonyBuckets.isEmpty() || point == null || projection == null) return null;
            MetricPoint mp = projection.forward(point);
            return findInBuckets(mp.x(), mp.y(), 0.0, colonyBuckets);
        }

        public IndexedOsmPoly findDenseColonyForSegment(GeoPoint from, GeoPoint to, double bufferMeters) {
            if (colonyBuckets.isEmpty() || from == null || to == null || projection == null) return null;
            MetricPoint a = projection.forward(from);
            MetricPoint b = projection.forward(to);
            return findSegmentInBuckets(a, b, bufferMeters, colonyBuckets);
        }

        public IndexedOsmPoly findAnySettlement(GeoPoint point) {
            if (settlementBuckets.isEmpty() || point == null || projection == null) return null;
            MetricPoint mp = projection.forward(point);
            return findInBuckets(mp.x(), mp.y(), 0.0, settlementBuckets);
        }

        public IndexedOsmPoly findBuildingHit(GeoPoint point, double bufferMeters) {
            if (buildingBuckets.isEmpty() || point == null || projection == null) return null;
            MetricPoint mp = projection.forward(point);
            return findInBuckets(mp.x(), mp.y(), bufferMeters, buildingBuckets);
        }

        public int countBuildingHitsForSegment(GeoPoint from, GeoPoint to, double halfRowWidthMeters) {
            if (buildingBuckets.isEmpty() || from == null || to == null || projection == null) return 0;
            MetricPoint a = projection.forward(from);
            MetricPoint b = projection.forward(to);
            double pad = halfRowWidthMeters + 15.0;
            int minBx = bCoord(Math.min(a.x(), b.x()) - pad);
            int maxBx = bCoord(Math.max(a.x(), b.x()) + pad);
            int minBy = bCoord(Math.min(a.y(), b.y()) - pad);
            int maxBy = bCoord(Math.max(a.y(), b.y()) + pad);
            Set<String> hitIds = null;
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int by = minBy; by <= maxBy; by++) {
                    List<IndexedOsmPoly> list = buildingBuckets.get(bKey(bx, by));
                    if (list == null) continue;
                    for (IndexedOsmPoly p : list) {
                        double d = ptToSegDist(p.centroidMetric().x(), p.centroidMetric().y(), a.x(), a.y(), b.x(), b.y());
                        if (d <= p.eqRadiusM() + halfRowWidthMeters) {
                            if (hitIds == null) hitIds = new HashSet<>();
                            hitIds.add(p.osmId());
                        }
                    }
                }
            }
            return hitIds != null ? hitIds.size() : 0;
        }

        public String findEndpointObstacleReason(GeoPoint point, double instBufferMeters, double buildingBufferMeters) {
            IndexedOsmPoly inst = findInstitutionalExclusion(point, instBufferMeters);
            if (inst != null) {
                return inst.category() + ":" + inst.name().replaceAll("[^a-zA-Z0-9]+", "_");
            }
            IndexedOsmPoly colony = findDenseColony(point);
            if (colony != null) {
                return "RESIDENTIAL_SETTLEMENT_COLONY:" + colony.name().replaceAll("[^a-zA-Z0-9]+", "_");
            }
            IndexedOsmPoly stl = findAnySettlement(point);
            if (stl != null) {
                return "SETTLEMENT_BOUNDARY:" + stl.name().replaceAll("[^a-zA-Z0-9]+", "_");
            }
            IndexedOsmPoly bld = findBuildingHit(point, buildingBufferMeters);
            if (bld != null) {
                return "BUILDING_FOOTPRINT_" + bld.category() + ":" + bld.name().replaceAll("[^a-zA-Z0-9]+", "_");
            }
            return null;
        }

        public boolean hasSegmentBuildingOrExclusionConflict(
                GeoPoint from, GeoPoint to, double halfRowWidthMeters, double instBufferMeters) {
            if (findInstitutionalExclusionForSegment(from, to, halfRowWidthMeters + instBufferMeters) != null) {
                return true;
            }
            if (findDenseColonyForSegment(from, to, halfRowWidthMeters) != null) {
                return true;
            }
            return countBuildingHitsForSegment(from, to, halfRowWidthMeters) > 0;
        }

        public DynamicDemolitionSummary evaluateRouteDemolitionImpact(List<GeoPoint> mainline, double halfRowWidthMeters) {
            if (mainline == null || mainline.size() < 2 || allBuildings.isEmpty() || projection == null) {
                return new DynamicDemolitionSummary(0, Map.of(), 0, 0, List.of());
            }
            Set<String> seen = new HashSet<>();
            List<IndexedOsmPoly> hitList = new ArrayList<>();
            Map<String, Integer> byType = new LinkedHashMap<>();
            double households = 0.0;

            for (int i = 1; i < mainline.size(); i++) {
                MetricPoint a = projection.forward(mainline.get(i - 1));
                MetricPoint b = projection.forward(mainline.get(i));
                double pad = halfRowWidthMeters + 25.0;
                int minBx = bCoord(Math.min(a.x(), b.x()) - pad);
                int maxBx = bCoord(Math.max(a.x(), b.x()) + pad);
                int minBy = bCoord(Math.min(a.y(), b.y()) - pad);
                int maxBy = bCoord(Math.max(a.y(), b.y()) + pad);
                for (int bx = minBx; bx <= maxBx; bx++) {
                    for (int by = minBy; by <= maxBy; by++) {
                        List<IndexedOsmPoly> list = buildingBuckets.get(bKey(bx, by));
                        if (list == null) continue;
                        for (IndexedOsmPoly p : list) {
                            if (seen.contains(p.osmId())) continue;
                            double d = ptToSegDist(p.centroidMetric().x(), p.centroidMetric().y(), a.x(), a.y(), b.x(), b.y());
                            if (d <= p.eqRadiusM() + halfRowWidthMeters) {
                                seen.add(p.osmId());
                                hitList.add(p);
                                byType.merge(p.category(), 1, Integer::sum);
                                BuildingType bt = switch (p.category()) {
                                    case "COMMERCIAL" -> BuildingType.COMMERCIAL;
                                    case "INDUSTRIAL" -> BuildingType.INDUSTRIAL;
                                    default -> BuildingType.RESIDENTIAL;
                                };
                                households += BuildingFootprintVectorIndex.estimateHouseholdsForDemolishedBuilding(
                                        bt, p.areaSqM(), p.floors());
                            }
                        }
                    }
                }
            }
            int hh = (int) Math.round(households);
            int pop = (int) Math.round(households * BuildingFootprintVectorIndex.RFCTLARR_PERSONS_PER_HOUSEHOLD);
            return new DynamicDemolitionSummary(hitList.size(), Map.copyOf(byType), hh, pop, List.copyOf(hitList));
        }

        private static IndexedOsmPoly findInBuckets(
                double x, double y, double bufferM, Map<Long, List<IndexedOsmPoly>> buckets) {
            int bx = bCoord(x);
            int by = bCoord(y);
            int span = bufferM > 0 ? 1 : 0;
            for (int dx = -span; dx <= span; dx++) {
                for (int dy = -span; dy <= span; dy++) {
                    List<IndexedOsmPoly> list = buckets.get(bKey(bx + dx, by + dy));
                    if (list == null) continue;
                    for (IndexedOsmPoly p : list) {
                        if (x < p.minX() - bufferM || x > p.maxX() + bufferM
                                || y < p.minY() - bufferM || y > p.maxY() + bufferM) {
                            continue;
                        }
                        if (pointInRing(x, y, p.metricRing())) {
                            return p;
                        }
                        if (bufferM > 0.0 && distToRing(x, y, p.metricRing()) <= bufferM) {
                            return p;
                        }
                    }
                }
            }
            return null;
        }

        private static IndexedOsmPoly findSegmentInBuckets(
                MetricPoint a, MetricPoint b, double bufferM, Map<Long, List<IndexedOsmPoly>> buckets) {
            double minX = Math.min(a.x(), b.x()) - bufferM;
            double maxX = Math.max(a.x(), b.x()) + bufferM;
            double minY = Math.min(a.y(), b.y()) - bufferM;
            double maxY = Math.max(a.y(), b.y()) + bufferM;
            int minBx = bCoord(minX), maxBx = bCoord(maxX);
            int minBy = bCoord(minY), maxBy = bCoord(maxY);
            double[] fracs = {0.0, 0.25, 0.5, 0.75, 1.0};
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int by = minBy; by <= maxBy; by++) {
                    List<IndexedOsmPoly> list = buckets.get(bKey(bx, by));
                    if (list == null) continue;
                    for (IndexedOsmPoly p : list) {
                        if (maxX < p.minX() || minX > p.maxX() || maxY < p.minY() || minY > p.maxY()) {
                            continue;
                        }
                        for (double t : fracs) {
                            double sx = a.x() + t * (b.x() - a.x());
                            double sy = a.y() + t * (b.y() - a.y());
                            if (pointInRing(sx, sy, p.metricRing())
                                    || (bufferM > 0.0 && distToRing(sx, sy, p.metricRing()) <= bufferM)) {
                                return p;
                            }
                        }
                        double cDist = ptToSegDist(p.centroidMetric().x(), p.centroidMetric().y(), a.x(), a.y(), b.x(), b.y());
                        if (cDist <= p.eqRadiusM() + bufferM) {
                            return p;
                        }
                    }
                }
            }
            return null;
        }

        private static void insertBuckets(IndexedOsmPoly p, Map<Long, List<IndexedOsmPoly>> buckets, double padM) {
            int minBx = bCoord(p.minX() - padM), maxBx = bCoord(p.maxX() + padM);
            int minBy = bCoord(p.minY() - padM), maxBy = bCoord(p.maxY() + padM);
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int by = minBy; by <= maxBy; by++) {
                    buckets.computeIfAbsent(bKey(bx, by), k -> new ArrayList<>()).add(p);
                }
            }
        }

        private static int bCoord(double m) {
            return (int) Math.floor(m / BUCKET_METERS);
        }

        private static long bKey(int bx, int by) {
            return (((long) bx) << 32) ^ (by & 0xffffffffL);
        }

        private static boolean pointInRing(double x, double y, List<MetricPoint> ring) {
            boolean inside = false;
            int n = ring.size();
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = ring.get(i).x(), yi = ring.get(i).y();
                double xj = ring.get(j).x(), yj = ring.get(j).y();
                if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / ((yj - yi) + 1e-12) + xi)) {
                    inside = !inside;
                }
            }
            return inside;
        }

        private static double distToRing(double x, double y, List<MetricPoint> ring) {
            double best = Double.POSITIVE_INFINITY;
            for (int i = 1; i < ring.size(); i++) {
                double d = ptToSegDist(x, y, ring.get(i - 1).x(), ring.get(i - 1).y(), ring.get(i).x(), ring.get(i).y());
                if (d < best) best = d;
            }
            return best;
        }

        private static double ptToSegDist(double px, double py, double ax, double ay, double bx, double by) {
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            if (len2 <= 1e-12) return Math.hypot(px - ax, py - ay);
            double t = Math.max(0.0, Math.min(1.0, ((px - ax) * dx + (py - ay) * dy) / len2));
            return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
        }
    }
}
