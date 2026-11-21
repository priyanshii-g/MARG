package in.marg.transport;

import in.marg.model.GeoPoint;
import in.marg.routing.CostAssessment;
import in.marg.routing.TransportCostPolicy;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Disk-cached OpenStreetMap (OSM) Dynamic Transport Network (Roads &amp; Railways) Indexer
 * for Tier-1 Pan-India Dynamic Routes (deep=true).
 *
 * <p>Employs a two-stage lightweight fetching strategy:
 * <ul>
 *   <li>Stage A (Corridor Routing &amp; Tie-In Pass): Queries major classified arteries
 *       ({@code motorway, trunk, primary, secondary, tertiary, railway=rail}) along corridor
 *       bounding tiles to provide accurate A* crossing penalties and real NH/SH terminal tie-in geometry.</li>
 *   <li>Stage B (Mainline Grade-Separation Pass): For {@code EXPRESSWAY} mode with {@code deep=true},
 *       identifies narrow strip tiles intersected by the routed mainline, loads/fetches local and
 *       residential ways ({@code unclassified, residential, living_street}), and computes exact 2D
 *       grade-separated structures (Flyovers, VUPs, LVUPs, ROBs).</li>
 * </ul>
 * Responses are persisted to {@code data/cache/osm-transport/} so repeated runs evaluate in milliseconds offline.
 */
public class OsmDynamicTransportNetworkCache {

    private static final Path DEFAULT_CACHE_DIR = Path.of("data/cache/osm-transport");
    private static final List<String> OVERPASS_MIRRORS = List.of(
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
    );
    private static final String OSM_API_MAP = "https://api.openstreetmap.org/api/0.6/map.json";

    private static final double BUCKET_METERS = 1000.0;
    private static final double TILE_SIZE_DEG = 0.20; // ~22 km grid tiles
    private static final int MAX_CORRIDOR_TILES = 8;
    private static final double EPS = 1e-9;

    private static final OsmDynamicTransportNetworkCache DEFAULT_INSTANCE =
            new OsmDynamicTransportNetworkCache(DEFAULT_CACHE_DIR);

    private final Path cacheDir;
    private final ObjectMapper objectMapper;
    private volatile HttpClient httpClient;
    private final ConcurrentHashMap<String, List<OsmTransportWay>> memoryTileCache = new ConcurrentHashMap<>();
    private volatile long lastOfflineDetectionMillis = 0L;

    public record OsmTransportWay(
            String id,
            String ref,
            String name,
            TransportCategory category,
            int hierarchyTier,
            List<GeoPoint> points
    ) {}

    public OsmDynamicTransportNetworkCache(Path cacheDir) {
        this.cacheDir = cacheDir != null ? cacheDir : DEFAULT_CACHE_DIR;
        this.objectMapper = new ObjectMapper();
    }

    public static OsmDynamicTransportNetworkCache getDefaultInstance() {
        return DEFAULT_INSTANCE;
    }

    private HttpClient getClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    try {
                        httpClient = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(4))
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

    /**
     * Stage A: Loads or fetches (with disk caching) major classified arteries and railways
     * for corridor routing and terminal tie-ins.
     */
    public OsmTransportIndex indexArteriesForCorridor(
            GeoPoint start,
            GeoPoint goal,
            CoordinateProjection projection) {
        if (start == null || goal == null || projection == null) {
            return new OsmTransportIndex(projection, Collections.emptyList(), this);
        }
        List<double[]> tiles = buildCorridorTileWindows(start, goal);
        List<OsmTransportWay> merged = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();

        for (double[] win : tiles) {
            List<OsmTransportWay> ways = loadOrFetchTile(win[0], win[1], win[2], win[3], false);
            for (OsmTransportWay w : ways) {
                if (seenIds.add(w.id())) {
                    merged.add(w);
                }
            }
        }
        return new OsmTransportIndex(projection, merged, this);
    }

    /**
     * Loads or fetches local/residential roads for a specific geographic tile.
     */
    public List<OsmTransportWay> loadLocalRoadsForTile(double minLat, double minLon, double maxLat, double maxLon) {
        return loadOrFetchTile(minLat, minLon, maxLat, maxLon, true);
    }

    private List<double[]> buildCorridorTileWindows(GeoPoint start, GeoPoint goal) {
        List<double[]> tiles = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        double spanDeg = Math.hypot(goal.lat() - start.lat(), goal.lon() - start.lon());
        int samples = Math.max(2, Math.min(MAX_CORRIDOR_TILES, (int) Math.ceil(spanDeg / 0.25) + 1));

        for (int i = 0; i < samples; i++) {
            double frac = samples == 1 ? 0.0 : (double) i / (samples - 1);
            double lat = start.lat() + frac * (goal.lat() - start.lat());
            double lon = start.lon() + frac * (goal.lon() - start.lon());
            double minLat = Math.floor(lat / TILE_SIZE_DEG) * TILE_SIZE_DEG;
            double minLon = Math.floor(lon / TILE_SIZE_DEG) * TILE_SIZE_DEG;
            double maxLat = minLat + TILE_SIZE_DEG;
            double maxLon = minLon + TILE_SIZE_DEG;
            String key = String.format(Locale.ROOT, "%.2f_%.2f", minLat, minLon);
            if (keys.add(key)) {
                tiles.add(new double[]{minLat, minLon, maxLat, maxLon});
            }
        }
        return tiles;
    }

    private List<OsmTransportWay> loadOrFetchTile(
            double minLat, double minLon, double maxLat, double maxLon, boolean localOnly) {

        String tierSuffix = localOnly ? "local" : "arteries";
        String key = String.format(Locale.ROOT, "tile_%.2f_%.2f_%.2f_%.2f_%s",
                minLat, minLon, maxLat, maxLon, tierSuffix).replace('.', 'p');

        List<OsmTransportWay> inMem = memoryTileCache.get(key);
        if (inMem != null) {
            return inMem;
        }

        Path diskFile = cacheDir.resolve(key + ".json");
        if (Files.exists(diskFile)) {
            try {
                JsonNode root = objectMapper.readTree(diskFile.toFile());
                List<OsmTransportWay> loaded = parseCachedWays(root);
                memoryTileCache.put(key, loaded);
                return loaded;
            } catch (Exception ignored) {
            }
        }

        List<OsmTransportWay> fetched = fetchTile(minLat, minLon, maxLat, maxLon, localOnly);
        memoryTileCache.put(key, fetched);
        if (!fetched.isEmpty()) {
            persistTileToDisk(diskFile, fetched);
        }
        return fetched;
    }

    private List<OsmTransportWay> fetchTile(
            double minLat, double minLon, double maxLat, double maxLon, boolean localOnly) {

        if (System.currentTimeMillis() - lastOfflineDetectionMillis < 30_000L) {
            return Collections.emptyList();
        }
        HttpClient client = getClient();
        if (client == null) {
            return Collections.emptyList();
        }

        String bbox = String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", minLat, minLon, maxLat, maxLon);
        String query;
        if (localOnly) {
            query = "[out:json][timeout:10];("
                    + "way[\"highway\"~\"^(unclassified|residential|living_street)$\"](" + bbox + ");"
                    + ");out geom 2500;";
        } else {
            query = "[out:json][timeout:10];("
                    + "way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"](" + bbox + ");"
                    + "way[\"railway\"=\"rail\"](" + bbox + ");"
                    + ");out geom 2500;";
        }

        String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        for (String mirror : OVERPASS_MIRRORS) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(mirror))
                        .timeout(Duration.ofSeconds(6))
                        .header("User-Agent", "MARG-PanIndia-TransportOSM/3.0")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200 && resp.body() != null && !resp.body().isBlank()) {
                    JsonNode root = objectMapper.readTree(resp.body());
                    return parseOverpassElements(root.get("elements"));
                }
            } catch (Exception e) {
                lastOfflineDetectionMillis = System.currentTimeMillis();
                break;
            }
        }

        // Secondary fallback to official OSM v0.6 API if Overpass mirror failed
        try {
            String fallbackUrl = String.format(Locale.ROOT,
                    "%s?bbox=%.4f,%.4f,%.4f,%.4f", OSM_API_MAP, minLon, minLat, maxLon, maxLat);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(fallbackUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "MARG-PanIndia-TransportOSM/3.0")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() == 200) {
                try (InputStream is = resp.body()) {
                    JsonNode root = objectMapper.readTree(is);
                    return parseOsmApiMapElements(root.get("elements"), localOnly);
                }
            }
        } catch (Exception ignored) {
        }

        return Collections.emptyList();
    }

    private List<OsmTransportWay> parseOverpassElements(JsonNode elements) {
        if (elements == null || !elements.isArray()) {
            return Collections.emptyList();
        }
        List<OsmTransportWay> out = new ArrayList<>();
        for (JsonNode el : elements) {
            if (!"way".equals(el.path("type").asText())) {
                continue;
            }
            long id = el.path("id").asLong(0);
            JsonNode tags = el.get("tags");
            if (tags == null) continue;

            JsonNode geomNode = el.get("geometry");
            if (geomNode == null || !geomNode.isArray() || geomNode.size() < 2) {
                continue;
            }

            Classification cls = classifyHighwayOrRailway(tags);
            if (cls == null) {
                continue;
            }

            List<GeoPoint> pts = new ArrayList<>();
            for (JsonNode ptNode : geomNode) {
                pts.add(new GeoPoint(ptNode.path("lat").asDouble(), ptNode.path("lon").asDouble()));
            }

            String name = tag(tags, "name");
            String ref = tag(tags, "ref");
            if (ref.isBlank()) {
                ref = !name.isBlank() ? name : ("OSM-" + id);
            }
            if (name.isBlank()) {
                name = cls.category == TransportCategory.RAILWAY ? "Indian Railways Trunk Line" : ("Road " + ref);
            }

            out.add(new OsmTransportWay("OSM-WAY-" + id, ref, name, cls.category, cls.tier, pts));
        }
        return out;
    }

    private List<OsmTransportWay> parseOsmApiMapElements(JsonNode elements, boolean localOnly) {
        if (elements == null || !elements.isArray()) {
            return Collections.emptyList();
        }
        Map<Long, double[]> nodes = new HashMap<>();
        List<JsonNode> wayNodes = new ArrayList<>();

        for (JsonNode el : elements) {
            String type = el.path("type").asText();
            if ("node".equals(type)) {
                nodes.put(el.path("id").asLong(),
                        new double[]{el.path("lat").asDouble(), el.path("lon").asDouble()});
            } else if ("way".equals(type)) {
                wayNodes.add(el);
            }
        }

        List<OsmTransportWay> out = new ArrayList<>();
        for (JsonNode w : wayNodes) {
            JsonNode tags = w.get("tags");
            if (tags == null) continue;
            Classification cls = classifyHighwayOrRailway(tags);
            if (cls == null) continue;
            if (localOnly && cls.category != TransportCategory.LOCAL_ROAD) continue;
            if (!localOnly && cls.category == TransportCategory.LOCAL_ROAD) continue;

            JsonNode nodeIds = w.get("nodes");
            if (nodeIds == null || nodeIds.size() < 2) continue;

            List<GeoPoint> pts = new ArrayList<>();
            for (JsonNode nid : nodeIds) {
                double[] latlon = nodes.get(nid.asLong());
                if (latlon != null) {
                    pts.add(new GeoPoint(latlon[0], latlon[1]));
                }
            }
            if (pts.size() < 2) continue;

            long id = w.path("id").asLong();
            String name = tag(tags, "name");
            String ref = tag(tags, "ref");
            if (ref.isBlank()) ref = !name.isBlank() ? name : ("OSM-" + id);
            if (name.isBlank()) name = cls.category == TransportCategory.RAILWAY ? "Railway Line" : ("Road " + ref);

            out.add(new OsmTransportWay("OSM-WAY-" + id, ref, name, cls.category, cls.tier, pts));
        }
        return out;
    }

    private record Classification(TransportCategory category, int tier) {}

    private static Classification classifyHighwayOrRailway(JsonNode tags) {
        String railway = tag(tags, "railway");
        if ("rail".equals(railway)) {
            return new Classification(TransportCategory.RAILWAY, 1);
        }

        String hw = tag(tags, "highway");
        String ref = tag(tags, "ref").toUpperCase(Locale.ROOT);
        String name = tag(tags, "name").toUpperCase(Locale.ROOT);

        if (ref.contains("NH") || name.contains("NATIONAL HIGHWAY")) {
            return new Classification(TransportCategory.NATIONAL_HIGHWAY, 1);
        }
        if (ref.contains("SH") || name.contains("STATE HIGHWAY")) {
            return new Classification(TransportCategory.STATE_HIGHWAY, 2);
        }
        if (ref.contains("MDR") || ref.contains("ODR") || name.contains("DISTRICT ROAD")) {
            return new Classification(TransportCategory.MAJOR_DISTRICT_ROAD, 3);
        }

        return switch (hw) {
            case "motorway", "trunk", "primary", "motorway_link", "trunk_link", "primary_link" ->
                    new Classification(TransportCategory.NATIONAL_HIGHWAY, 1);
            case "secondary", "secondary_link" ->
                    new Classification(TransportCategory.STATE_HIGHWAY, 2);
            case "tertiary", "tertiary_link" ->
                    new Classification(TransportCategory.MAJOR_DISTRICT_ROAD, 3);
            case "unclassified", "residential", "living_street", "road" ->
                    new Classification(TransportCategory.LOCAL_ROAD, 4);
            case "service" -> {
                String svc = tag(tags, "service");
                if ("parking_aisle".equals(svc) || "driveway".equals(svc)) yield null;
                yield new Classification(TransportCategory.LOCAL_ROAD, 4);
            }
            default -> null;
        };
    }

    private static String tag(JsonNode tags, String key) {
        if (tags == null || !tags.has(key)) return "";
        return tags.get(key).asText("").trim();
    }

    private void persistTileToDisk(Path diskFile, List<OsmTransportWay> ways) {
        try {
            Files.createDirectories(diskFile.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("tileFeaturesCount", ways.size());
            List<Map<String, Object>> list = new ArrayList<>();
            for (OsmTransportWay w : ways) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", w.id());
                m.put("ref", w.ref());
                m.put("name", w.name());
                m.put("category", w.category().name());
                m.put("tier", w.hierarchyTier());
                List<List<Double>> pts = new ArrayList<>();
                for (GeoPoint p : w.points()) {
                    pts.add(List.of(p.lat(), p.lon()));
                }
                m.put("pts", pts);
                list.add(m);
            }
            root.put("ways", list);
            objectMapper.writeValue(diskFile.toFile(), root);
        } catch (Exception ignored) {
        }
    }

    private List<OsmTransportWay> parseCachedWays(JsonNode root) {
        JsonNode waysNode = root.get("ways");
        if (waysNode == null || !waysNode.isArray()) {
            return Collections.emptyList();
        }
        List<OsmTransportWay> out = new ArrayList<>();
        for (JsonNode w : waysNode) {
            String id = w.path("id").asText();
            String ref = w.path("ref").asText();
            String name = w.path("name").asText();
            String catStr = w.path("category").asText();
            TransportCategory cat;
            try {
                cat = TransportCategory.valueOf(catStr);
            } catch (Exception ex) {
                cat = TransportCategory.NATIONAL_HIGHWAY;
            }
            int tier = w.path("tier").asInt(1);
            List<GeoPoint> pts = new ArrayList<>();
            JsonNode ptsNode = w.get("pts");
            if (ptsNode != null && ptsNode.isArray()) {
                for (JsonNode p : ptsNode) {
                    pts.add(new GeoPoint(p.get(0).asDouble(), p.get(1).asDouble()));
                }
            }
            if (pts.size() >= 2) {
                out.add(new OsmTransportWay(id, ref, name, cat, tier, pts));
            }
        }
        return out;
    }

    // =========================================================================
    // Lightweight In-Memory Spatial Vector Index for Dynamic Pan-India Routes
    // =========================================================================

    public static class OsmTransportIndex {

        public record IndexedWaySegment(
                String corridorId,
                String corridorRef,
                String corridorName,
                TransportCategory category,
                double ax, double ay,
                double bx, double by,
                double tx, double ty,
                double lengthMeters
        ) {}

        private final CoordinateProjection projection;
        private final List<OsmTransportWay> ways = new ArrayList<>();
        private final List<IndexedWaySegment> allSegments = new ArrayList<>();
        private final Map<Long, List<IndexedWaySegment>> spatialBuckets = new HashMap<>();
        private final Set<String> loadedLocalTiles = new HashSet<>();
        private final OsmDynamicTransportNetworkCache parentCache;

        public OsmTransportIndex(
                CoordinateProjection projection,
                List<OsmTransportWay> initialWays,
                OsmDynamicTransportNetworkCache parentCache) {
            this.projection = projection;
            this.parentCache = parentCache;
            addWays(initialWays);
        }

        public synchronized void addWays(List<OsmTransportWay> newWays) {
            if (newWays == null || newWays.isEmpty()) return;
            for (OsmTransportWay w : newWays) {
                this.ways.add(w);
                List<MetricPoint> mpts = new ArrayList<>();
                for (GeoPoint p : w.points()) {
                    mpts.add(projection.forward(p));
                }
                for (int i = 1; i < mpts.size(); i++) {
                    MetricPoint a = mpts.get(i - 1);
                    MetricPoint b = mpts.get(i);
                    double dx = b.x() - a.x();
                    double dy = b.y() - a.y();
                    double len = Math.hypot(dx, dy);
                    if (len <= EPS) continue;
                    IndexedWaySegment seg = new IndexedWaySegment(
                            w.id(), w.ref(), w.name(), w.category(),
                            a.x(), a.y(), b.x(), b.y(),
                            dx / len, dy / len, len);
                    allSegments.add(seg);
                    insertSegmentIntoBuckets(seg);
                }
            }
        }

        /**
         * Stage B: Identifies narrow strip tiles along the routed mainline and loads local/residential roads.
         */
        public synchronized void loadMainlineLocalStreets(List<GeoPoint> mainline) {
            if (mainline == null || mainline.isEmpty() || parentCache == null) return;
            List<OsmTransportWay> additional = new ArrayList<>();
            for (GeoPoint pt : mainline) {
                double minLat = Math.floor(pt.lat() / TILE_SIZE_DEG) * TILE_SIZE_DEG;
                double minLon = Math.floor(pt.lon() / TILE_SIZE_DEG) * TILE_SIZE_DEG;
                String key = String.format(Locale.ROOT, "%.2f_%.2f", minLat, minLon);
                if (loadedLocalTiles.add(key)) {
                    List<OsmTransportWay> locals = parentCache.loadLocalRoadsForTile(
                            minLat, minLon, minLat + TILE_SIZE_DEG, minLon + TILE_SIZE_DEG);
                    additional.addAll(locals);
                }
            }
            if (!additional.isEmpty()) {
                addWays(additional);
            }
        }

        public boolean isEmpty() {
            return allSegments.isEmpty();
        }

        public int totalIndexedSegments() {
            return allSegments.size();
        }

        public List<OsmTransportWay> allWays() {
            return Collections.unmodifiableList(ways);
        }

        private static long bucketKey(int bx, int by) {
            return (((long) bx) << 32) ^ (by & 0xFFFFFFFFL);
        }

        private static int bucketCoord(double metric) {
            return (int) Math.floor(metric / BUCKET_METERS);
        }

        private void insertSegmentIntoBuckets(IndexedWaySegment seg) {
            int minBx = bucketCoord(Math.min(seg.ax(), seg.bx()));
            int maxBx = bucketCoord(Math.max(seg.ax(), seg.bx()));
            int minBy = bucketCoord(Math.min(seg.ay(), seg.by()));
            int maxBy = bucketCoord(Math.max(seg.ay(), seg.by()));
            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int by = minBy; by <= maxBy; by++) {
                    spatialBuckets.computeIfAbsent(bucketKey(bx, by), k -> new ArrayList<>()).add(seg);
                }
            }
        }

        /**
         * Finds 2D intersections along the step [a -> b] with skew angle calculation.
         */
        public List<TransportNetworkVectorIndex.SegmentCrossing> findStepCrossingsMetric(MetricPoint a, MetricPoint b) {
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double stepLen = Math.hypot(dx, dy);
            if (stepLen <= EPS) return Collections.emptyList();

            double ux = dx / stepLen;
            double uy = dy / stepLen;

            int minBx = bucketCoord(Math.min(a.x(), b.x()));
            int maxBx = bucketCoord(Math.max(a.x(), b.x()));
            int minBy = bucketCoord(Math.min(a.y(), b.y()));
            int maxBy = bucketCoord(Math.max(a.y(), b.y()));

            Map<String, TransportNetworkVectorIndex.SegmentCrossing> byCorridor = null;
            Set<IndexedWaySegment> checked = new HashSet<>();

            for (int bx = minBx; bx <= maxBx; bx++) {
                for (int by = minBy; by <= maxBy; by++) {
                    List<IndexedWaySegment> bucket = spatialBuckets.get(bucketKey(bx, by));
                    if (bucket == null) continue;
                    for (IndexedWaySegment seg : bucket) {
                        if (!checked.add(seg)) continue;
                        double sx = seg.bx() - seg.ax();
                        double sy = seg.by() - seg.ay();
                        double denom = dx * sy - dy * sx;
                        if (Math.abs(denom) <= EPS) continue;

                        double qpx = seg.ax() - a.x();
                        double qpy = seg.ay() - a.y();
                        double t = (qpx * sy - qpy * sx) / denom;
                        double u = (qpx * dy - qpy * dx) / denom;

                        if (t > 1e-9 && t <= 1.0 + 1e-9 && u >= -1e-9 && u <= 1.0 + 1e-9) {
                            double clampedT = Math.max(0.0, Math.min(1.0, t));
                            double ix = a.x() + clampedT * dx;
                            double iy = a.y() + clampedT * dy;
                            double sinAngle = Math.min(1.0, Math.abs(ux * seg.ty() - uy * seg.tx()));
                            double angleDeg = Math.toDegrees(Math.asin(sinAngle));

                            if (byCorridor == null) byCorridor = new LinkedHashMap<>(2);
                            String refKey = normalizeRoadRef(seg.corridorRef(), seg.corridorId());
                            var existing = byCorridor.get(refKey);
                            boolean close = false;
                            if (existing == null) {
                                for (var other : byCorridor.values()) {
                                    if (other.category() == seg.category()
                                            && Math.hypot(ix - other.metricPoint().x(), iy - other.metricPoint().y()) < 50.0) {
                                        close = true;
                                        break;
                                    }
                                }
                            }
                            if (!close && (existing == null || categoryPriority(seg.category()) < categoryPriority(existing.category()))) {
                                byCorridor.put(refKey, new TransportNetworkVectorIndex.SegmentCrossing(
                                        seg.corridorId(), seg.corridorRef(), seg.corridorName(),
                                        seg.category(), new MetricPoint(ix, iy),
                                        projection.inverse(new MetricPoint(ix, iy)),
                                        clampedT, sinAngle, angleDeg));
                            }
                        }
                    }
                }
            }
            return byCorridor == null ? Collections.emptyList() : List.copyOf(byCorridor.values());
        }

        /**
         * Detects all road/railway crossings along the routed mainline, sorted by chainage and
         * deduplicated within 100 m for dual-carriageway OSM ways.
         */
        public List<TransportNetworkVectorIndex.RouteCrossing> findRouteCrossings(List<GeoPoint> route) {
            if (route == null || route.size() < 2) return Collections.emptyList();
            List<TransportNetworkVectorIndex.RouteCrossing> raw = new ArrayList<>();
            double cumulativeKm = 0.0;
            for (int i = 1; i < route.size(); i++) {
                MetricPoint a = projection.forward(route.get(i - 1));
                MetricPoint b = projection.forward(route.get(i));
                double segKm = Math.hypot(b.x() - a.x(), b.y() - a.y()) / 1000.0;
                if (segKm <= EPS) continue;
                var crossings = findStepCrossingsMetric(a, b);
                for (var sc : crossings) {
                    double chainageKm = cumulativeKm + sc.stepFraction() * segKm;
                    raw.add(new TransportNetworkVectorIndex.RouteCrossing(
                            sc.corridorId(), sc.corridorRef(), sc.corridorName(),
                            sc.category(), sc.point(), sc.metricPoint(),
                            chainageKm, sc.crossingAngleDegrees(), sc.sinAngle()));
                }
                cumulativeKm += segKm;
            }

            raw.sort(Comparator.comparingDouble(TransportNetworkVectorIndex.RouteCrossing::chainageKm));
            List<TransportNetworkVectorIndex.RouteCrossing> deduped = new ArrayList<>();
            Map<String, Double> lastChainageByCorridor = new HashMap<>();
            for (var rc : raw) {
                Double last = lastChainageByCorridor.get(rc.corridorId());
                if (last != null && Math.abs(rc.chainageKm() - last) < 0.40) continue;
                if (!deduped.isEmpty()) {
                    var prev = deduped.get(deduped.size() - 1);
                    if (Math.abs(rc.chainageKm() - prev.chainageKm()) < 0.10) {
                        if (categoryPriority(rc.category()) < categoryPriority(prev.category())) {
                            deduped.set(deduped.size() - 1, rc);
                            lastChainageByCorridor.put(rc.corridorId(), rc.chainageKm());
                        }
                        continue;
                    }
                }
                deduped.add(rc);
                lastChainageByCorridor.put(rc.corridorId(), rc.chainageKm());
            }
            return List.copyOf(deduped);
        }

        /**
         * Evaluates step edge crossing cost during dynamic A* routing.
         */
        public CostAssessment assessEdge(
                GeoPoint from,
                GeoPoint to,
                FacilityMode facilityMode,
                TransportCostPolicy policy) {
            if (policy == null) return CostAssessment.free("osm:transport");
            MetricPoint a = projection.forward(from);
            MetricPoint b = projection.forward(to);
            double stepKm = Math.hypot(b.x() - a.x(), b.y() - a.y()) / 1000.0;
            if (stepKm <= EPS) return CostAssessment.free("osm:transport");

            var crossings = findStepCrossingsMetric(a, b);
            if (crossings.isEmpty()) return CostAssessment.free("osm:transport");

            double crossingCostKmEq = 0.0;
            for (var sc : crossings) {
                if (facilityMode == FacilityMode.NATIONAL_HIGHWAY && sc.category() != TransportCategory.RAILWAY) {
                    continue; // National Highway road crossings stay at-grade
                }
                crossingCostKmEq += policy.crossingStructureCostKmEq(sc.category(), sc.sinAngle());
            }

            if (crossingCostKmEq <= 0.0) return CostAssessment.free("osm:transport");
            double penaltyMult = crossingCostKmEq / stepKm;
            return CostAssessment.penalized(penaltyMult, "osm:transport", "OSM_GRADE_SEPARATED_CROSSINGS");
        }

        /**
         * Finds the nearest existing road tie-in point from real OSM infrastructure.
         */
        public TransportNetworkVectorIndex.NearestRoadTieIn findNearestRoadTieIn(
                GeoPoint target,
                Predicate<GeoPoint> navigablePredicate,
                FacilityMode facilityMode) {
            if (target == null || allSegments.isEmpty()) return null;
            MetricPoint m = projection.forward(target);

            TransportNetworkVectorIndex.NearestRoadTieIn bestTie = null;
            double bestDist = Double.POSITIVE_INFINITY;

            for (IndexedWaySegment seg : allSegments) {
                if (seg.category() == TransportCategory.RAILWAY) continue;
                // For EXPRESSWAY, strictly avoid local roads as terminal tie-in points
                if (facilityMode == FacilityMode.EXPRESSWAY && seg.category() == TransportCategory.LOCAL_ROAD) {
                    continue;
                }

                double dx = seg.bx() - seg.ax();
                double dy = seg.by() - seg.ay();
                double len2 = dx * dx + dy * dy;
                double t = len2 <= EPS ? 0.0 : ((m.x() - seg.ax()) * dx + (m.y() - seg.ay()) * dy) / len2;
                t = Math.max(0.0, Math.min(1.0, t));
                double px = seg.ax() + t * dx;
                double py = seg.ay() + t * dy;
                double dist = Math.hypot(m.x() - px, m.y() - py);

                if (dist < bestDist) {
                    MetricPoint tieMetric = new MetricPoint(px, py);
                    GeoPoint tieGeo = projection.inverse(tieMetric);
                    if (navigablePredicate == null || navigablePredicate.test(tieGeo)) {
                        bestDist = dist;
                        bestTie = new TransportNetworkVectorIndex.NearestRoadTieIn(
                                seg.corridorId(), seg.corridorRef(), seg.corridorName(),
                                seg.category(), tieGeo, tieMetric, Math.round(dist * 10.0) / 10.0);
                    }
                }
            }
            return bestTie;
        }

        private static int categoryPriority(TransportCategory c) {
            if (c == null) return 99;
            return switch (c) {
                case RAILWAY -> 0;
                case NATIONAL_HIGHWAY -> 1;
                case STATE_HIGHWAY -> 2;
                case MAJOR_DISTRICT_ROAD -> 3;
                case LOCAL_ROAD -> 4;
            };
        }

        private static String normalizeRoadRef(String ref, String corridorId) {
            if (ref == null || ref.isBlank()) {
                return corridorId != null ? corridorId : "";
            }
            String norm = ref.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
            return norm.isEmpty() ? corridorId : norm;
        }
    }
}
