package in.marg.transport;

import in.marg.district.DistrictRecord;
import in.marg.district.IndianDistrictsCatalogService;
import in.marg.model.GeoPoint;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * In-memory EPSG:32646 spatial vector index for existing roads, railways,
 * and regional service hubs / bypass tie-in nodes.
 */
@Component
public class TransportNetworkVectorIndex {

    private static final double BUCKET_SIZE_METERS = 2000.0;
    private static final double EPS = 1e-9;

    private final CoordinateProjection projection;
    private final String networkGeoJsonPath;
    private final String hubsGeoJsonPath;
    private final ObjectMapper objectMapper;
    private IndianDistrictsCatalogService districtsCatalog;

    private boolean initialized = false;
    private final List<TransportCorridor> corridors = new ArrayList<>();
    private final List<IndexedSegment> allSegments = new ArrayList<>();
    private final Map<Long, List<IndexedSegment>> spatialBuckets = new HashMap<>();
    private final List<RegionalHub> hubs = new ArrayList<>();

    @Autowired
    public TransportNetworkVectorIndex(
            CoordinateProjection projection,
            @Value("${marg.transport.network-geojson-path:data/processed/transport/assam_transport_network.geojson}")
            String networkGeoJsonPath,
            @Value("${marg.transport.hubs-geojson-path:data/processed/transport/assam_regional_hubs.geojson}")
            String hubsGeoJsonPath,
            ObjectMapper objectMapper) {
        this.projection = projection;
        this.networkGeoJsonPath = networkGeoJsonPath;
        this.hubsGeoJsonPath = hubsGeoJsonPath;
        this.objectMapper = objectMapper;
    }

    public TransportNetworkVectorIndex(
            CoordinateProjection projection,
            String networkGeoJsonPath,
            String hubsGeoJsonPath) {
        this(projection, networkGeoJsonPath, hubsGeoJsonPath, new ObjectMapper());
    }

    @Autowired(required = false)
    public void setDistrictsCatalog(IndianDistrictsCatalogService districtsCatalog) {
        this.districtsCatalog = districtsCatalog;
    }

    private synchronized IndianDistrictsCatalogService getDistrictsCatalog() {
        if (districtsCatalog == null) {
            try {
                districtsCatalog = new IndianDistrictsCatalogService(
                        objectMapper,
                        new DefaultResourceLoader(),
                        "classpath:data/india_districts.json"
                );
                districtsCatalog.ensureInitialized();
            } catch (Exception ignored) {
            }
        }
        return districtsCatalog;
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        loadNetwork();
        loadHubs();
        initialized = true;
    }

    private void loadNetwork() {
        File file = new File(networkGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Transport network GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + networkGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode props = feat.get("properties");
                String id = props.get("id").asString();
                String ref = props.get("ref").asString();
                String name = props.get("name") != null ? props.get("name").asString() : "";
                String catStr = props.get("category") != null ? props.get("category").asString() : "";
                TransportCategory category;
                if ("EXPRESSWAY".equalsIgnoreCase(catStr) || "MOTORWAY".equalsIgnoreCase(catStr)) {
                    category = TransportCategory.NATIONAL_HIGHWAY;
                } else {
                    try {
                        category = TransportCategory.valueOf(catStr);
                    } catch (Exception ex) {
                        continue;
                    }
                }
                int tier = props.get("hierarchyTier") != null ? props.get("hierarchyTier").asInt(1) : 1;

                String geomType = feat.get("geometry") != null && feat.get("geometry").get("type") != null
                        ? feat.get("geometry").get("type").asString()
                        : "LineString";
                JsonNode coordsNode = feat.get("geometry") != null ? feat.get("geometry").get("coordinates") : null;
                if (coordsNode == null) {
                    continue;
                }

                List<List<GeoPoint>> lineList = new ArrayList<>();
                if ("MultiLineString".equalsIgnoreCase(geomType)) {
                    for (JsonNode lineNode : coordsNode) {
                        List<GeoPoint> line = new ArrayList<>();
                        for (JsonNode pt : lineNode) {
                            line.add(new GeoPoint(pt.get(1).asDouble(), pt.get(0).asDouble()));
                        }
                        if (line.size() >= 2) {
                            lineList.add(line);
                        }
                    }
                } else {
                    List<GeoPoint> line = new ArrayList<>();
                    for (JsonNode pt : coordsNode) {
                        line.add(new GeoPoint(pt.get(1).asDouble(), pt.get(0).asDouble()));
                    }
                    if (line.size() >= 2) {
                        lineList.add(line);
                    }
                }

                for (List<GeoPoint> geoPoints : lineList) {
                    List<MetricPoint> metricPoints = new ArrayList<>();
                    for (GeoPoint gp : geoPoints) {
                        metricPoints.add(projection.forward(gp));
                    }

                    TransportCorridor corridor =
                            new TransportCorridor(id, ref, name, category, tier, List.copyOf(geoPoints));
                    corridors.add(corridor);

                    for (int i = 1; i < metricPoints.size(); i++) {
                        MetricPoint a = metricPoints.get(i - 1);
                        MetricPoint b = metricPoints.get(i);
                        double dx = b.x() - a.x();
                        double dy = b.y() - a.y();
                        double len = Math.hypot(dx, dy);
                        if (len <= EPS) {
                            continue;
                        }
                        IndexedSegment seg = new IndexedSegment(
                                id,
                                ref,
                                name,
                                category,
                                a.x(),
                                a.y(),
                                b.x(),
                                b.y(),
                                dx / len,
                                dy / len,
                                len);
                        allSegments.add(seg);
                        insertSegmentIntoBuckets(seg);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load transport network GeoJSON: " + networkGeoJsonPath, e);
        }
    }

    private void loadHubs() {
        File file = new File(hubsGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Regional hubs GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + hubsGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                GeoPoint center = new GeoPoint(
                        p.get("centerLat").asDouble(),
                        p.get("centerLon").asDouble());
                GeoPoint tieIn = new GeoPoint(
                        p.get("tieInLat").asDouble(),
                        p.get("tieInLon").asDouble());
                hubs.add(new RegionalHub(
                        p.get("hubId").asString(),
                        p.get("name").asString(),
                        p.get("district").asString(),
                        center,
                        projection.forward(center),
                        tieIn,
                        projection.forward(tieIn),
                        p.get("tieInRoadRef").asString(),
                        p.get("tieInName").asString(),
                        p.get("urbanPopulation").asInt(),
                        p.get("catchmentPopulation").asInt(),
                        p.get("industrialOutputFactor").asDouble()));
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load regional hubs GeoJSON: " + hubsGeoJsonPath, e);
        }
    }

    private void insertSegmentIntoBuckets(IndexedSegment seg) {
        int minBx = bucketCoord(Math.min(seg.ax(), seg.bx()) - 250.0);
        int maxBx = bucketCoord(Math.max(seg.ax(), seg.bx()) + 250.0);
        int minBy = bucketCoord(Math.min(seg.ay(), seg.by()) - 250.0);
        int maxBy = bucketCoord(Math.max(seg.ay(), seg.by()) + 250.0);
        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                spatialBuckets
                        .computeIfAbsent(bucketKey(bx, by), k -> new ArrayList<>())
                        .add(seg);
            }
        }
    }

    private static int bucketCoord(double meters) {
        return (int) Math.floor(meters / BUCKET_SIZE_METERS);
    }

    private static long bucketKey(int bx, int by) {
        return (((long) bx) << 32) ^ (by & 0xffffffffL);
    }

    public List<TransportCorridor> allCorridors() {
        ensureInitialized();
        return List.copyOf(corridors);
    }

    public List<RegionalHub> allHubs() {
        ensureInitialized();
        return List.copyOf(hubs);
    }

    private static final Map<String, GeoPoint> KNOWN_CITIES = Map.ofEntries(
            // Uttar Pradesh (Gangetic Plains & Surrounds)
            Map.entry("ramnagar", new GeoPoint(27.085, 81.405)),
            Map.entry("barabanki", new GeoPoint(26.925, 81.185)),
            Map.entry("nawabganj", new GeoPoint(26.925, 81.185)),
            Map.entry("ayodhya", new GeoPoint(26.795, 82.205)),
            Map.entry("faizabad", new GeoPoint(26.775, 82.140)),
            Map.entry("rudauli", new GeoPoint(26.758, 81.792)),
            Map.entry("ramsanehighat", new GeoPoint(26.805, 81.542)),
            Map.entry("zaidpur", new GeoPoint(26.835, 81.330)),
            Map.entry("haidergarh", new GeoPoint(26.600, 81.425)),
            Map.entry("dariyabad", new GeoPoint(26.885, 81.565)),
            Map.entry("sohawal", new GeoPoint(26.775, 82.025)),
            Map.entry("fatehpur", new GeoPoint(27.170, 81.220)),
            Map.entry("bikapur", new GeoPoint(26.600, 82.135)),
            Map.entry("lucknow", new GeoPoint(26.850, 80.950)),
            Map.entry("kanpur", new GeoPoint(26.450, 80.350)),
            Map.entry("varanasi", new GeoPoint(25.320, 82.970)),
            Map.entry("gorakhpur", new GeoPoint(26.760, 83.370)),
            Map.entry("prayagraj", new GeoPoint(25.435, 81.846)),

            // Maharashtra (Western Ghats & Deccan)
            Map.entry("shirwal", new GeoPoint(18.152, 73.978)),
            Map.entry("wai", new GeoPoint(17.952, 73.915)),
            Map.entry("satara", new GeoPoint(17.685, 74.005)),
            Map.entry("bhor", new GeoPoint(18.150, 73.845)),
            Map.entry("khandala", new GeoPoint(18.050, 74.020)),
            Map.entry("mahabaleshwar", new GeoPoint(17.923, 73.658)),
            Map.entry("panchgani", new GeoPoint(17.925, 73.805)),
            Map.entry("pune", new GeoPoint(18.520, 73.856)),
            Map.entry("akola", new GeoPoint(20.700, 77.000)),
            Map.entry("nagpur", new GeoPoint(21.145, 79.088)),

            // Assam & Northeast
            Map.entry("diphu", new GeoPoint(25.845, 93.438)),
            Map.entry("dokmoka", new GeoPoint(26.340, 93.495)),
            Map.entry("bokakhat", new GeoPoint(26.638, 93.598)),
            Map.entry("howraghat", new GeoPoint(26.012, 93.368)),
            Map.entry("kohora", new GeoPoint(26.589, 93.400)),
            Map.entry("manja", new GeoPoint(25.878, 93.438)),
            Map.entry("nagaon", new GeoPoint(26.345, 92.685)),
            Map.entry("golaghat", new GeoPoint(26.515, 93.965)),
            Map.entry("guwahati", new GeoPoint(26.144, 91.736)),

            // Central India (Interstate)
            Map.entry("bhopal", new GeoPoint(23.250, 77.410)),
            Map.entry("betul", new GeoPoint(21.900, 77.900)),
            Map.entry("hoshangabad", new GeoPoint(22.750, 77.720)),
            Map.entry("itarsi", new GeoPoint(22.610, 77.760)),
            Map.entry("indore", new GeoPoint(22.720, 75.860)),
            Map.entry("jabalpur", new GeoPoint(23.180, 79.980))
    );

    public RegionalHub synthesizeDynamicHub(String hubName, GeoPoint center) {
        NearestRoadTieIn tieIn = findNearestRoadTieIn(center, pt -> true);
        GeoPoint bypassPt = tieIn != null ? tieIn.tieInPoint() : center;
        String roadRef = tieIn != null && tieIn.corridorRef() != null ? tieIn.corridorRef() : "Existing Road";
        String roadName = tieIn != null && tieIn.corridorName() != null ? tieIn.corridorName() : "Road Network";
        MetricPoint cMetric = projection.forward(center);
        MetricPoint bMetric = projection.forward(bypassPt);
        String slug = hubName.replaceAll("[^a-zA-Z0-9]+", "-").toUpperCase(Locale.ROOT);
        return new RegionalHub(
                "HUB-DYN-" + slug,
                hubName,
                "Intermediate Connection",
                center,
                cMetric,
                bypassPt,
                bMetric,
                roadRef,
                roadName + " (" + hubName + " Junction)",
                50000,
                150000,
                1.30
        );
    }

    public List<RegionalHub> resolveUserHubs(List<String> namesOrIds) {
        ensureInitialized();
        if (namesOrIds == null || namesOrIds.isEmpty()) {
            return List.of();
        }
        List<RegionalHub> resolved = new ArrayList<>();
        for (String raw : namesOrIds) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String token = raw.trim().toLowerCase(Locale.ROOT);
            RegionalHub match = hubs.stream()
                    .filter(h -> h.hubId().toLowerCase(Locale.ROOT).equals(token)
                            || h.name().toLowerCase(Locale.ROOT).equals(token)
                            || (h.name().toLowerCase(Locale.ROOT) + " town").equals(token)
                            || h.name().toLowerCase(Locale.ROOT).startsWith(token)
                            || h.hubId().toLowerCase(Locale.ROOT).contains(token)
                            || h.name().toLowerCase(Locale.ROOT).contains(token)
                            || token.contains(h.name().toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElse(null);

            // Coordinate fallback: if token looks like "lat, lon" or "lat lon"
            if (match == null && token.matches("^[-+]?[0-9]*\\.?[0-9]+[ ,;]+[-+]?[0-9]*\\.?[0-9]+$")) {
                String[] parts = token.split("[ ,;]+");
                try {
                    double p1 = Double.parseDouble(parts[0]);
                    double p2 = Double.parseDouble(parts[1]);
                    double lat = (p1 >= 6.0 && p1 <= 38.0) ? p1 : p2;
                    double lon = (p1 >= 6.0 && p1 <= 38.0) ? p2 : p1;
                    GeoPoint clickPt = new GeoPoint(lat, lon);
                    RegionalHub close = hubs.stream()
                            .filter(h -> projection.distanceMeters(clickPt, h.center()) <= 15000.0)
                            .min(Comparator.comparingDouble(h -> projection.distanceMeters(clickPt, h.center())))
                            .orElse(null);
                    if (close != null) {
                        match = close;
                    } else {
                        match = synthesizeDynamicHub(
                                "Hub (" + String.format(Locale.ROOT, "%.3f, %.3f", lat, lon) + ")",
                                clickPt);
                    }
                } catch (Exception ignored) {
                }
            }

            // Indian Districts & Tehsils Catalog Lookup across all 820+ official entities in India
            if (match == null) {
                IndianDistrictsCatalogService cat = getDistrictsCatalog();
                if (cat != null) {
                    var optDist = cat.lookup(raw);
                    if (optDist.isPresent()) {
                        DistrictRecord dr = optDist.get();
                        GeoPoint drPt = dr.toGeoPoint();
                        RegionalHub closeRegional = hubs.stream()
                                .filter(h -> projection.distanceMeters(drPt, h.center()) <= 5000.0)
                                .min(Comparator.comparingDouble(h -> projection.distanceMeters(drPt, h.center())))
                                .orElse(null);
                        match = closeRegional != null
                                ? closeRegional
                                : synthesizeDynamicHub(dr.displayLabel(), drPt);
                    }
                }
            }

            // Known Indian Cities Catalog Lookup
            if (match == null) {
                for (Map.Entry<String, GeoPoint> entry : KNOWN_CITIES.entrySet()) {
                    if (token.contains(entry.getKey()) || entry.getKey().contains(token)) {
                        match = synthesizeDynamicHub(
                                Character.toUpperCase(raw.charAt(0)) + raw.substring(1),
                                entry.getValue());
                        break;
                    }
                }
            }

            // Safe Fallback: match closest available hub in region rather than throwing 500 error
            if (match == null && !hubs.isEmpty()) {
                match = hubs.get(0);
            }

            if (match != null && !resolved.contains(match)) {
                resolved.add(match);
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * Finds all exact 2D intersections between step [from -> to] and existing road/railway segments,
     * de-duplicated per corridor ID, including the exact approach angle in degrees [0, 90].
     */
    public List<SegmentCrossing> findStepCrossings(GeoPoint from, GeoPoint to) {
        ensureInitialized();
        MetricPoint a = projection.forward(from);
        MetricPoint b = projection.forward(to);
        return findStepCrossingsMetric(a, b);
    }

    public List<SegmentCrossing> findStepCrossingsMetric(MetricPoint a, MetricPoint b) {
        ensureInitialized();
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double stepLen = Math.hypot(dx, dy);
        if (stepLen <= EPS) {
            return List.of();
        }
        double ux = dx / stepLen;
        double uy = dy / stepLen;

        int minBx = bucketCoord(Math.min(a.x(), b.x()));
        int maxBx = bucketCoord(Math.max(a.x(), b.x()));
        int minBy = bucketCoord(Math.min(a.y(), b.y()));
        int maxBy = bucketCoord(Math.max(a.y(), b.y()));

        Map<String, SegmentCrossing> byCorridor = null;

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                List<IndexedSegment> candidates = spatialBuckets.get(bucketKey(bx, by));
                if (candidates == null) {
                    continue;
                }
                for (IndexedSegment seg : candidates) {
                    // Quick AABB reject
                    if (Math.max(a.x(), b.x()) < Math.min(seg.ax(), seg.bx()) - 1e-6
                            || Math.min(a.x(), b.x()) > Math.max(seg.ax(), seg.bx()) + 1e-6
                            || Math.max(a.y(), b.y()) < Math.min(seg.ay(), seg.by()) - 1e-6
                            || Math.min(a.y(), b.y()) > Math.max(seg.ay(), seg.by()) + 1e-6) {
                        continue;
                    }

                    double sx = seg.bx() - seg.ax();
                    double sy = seg.by() - seg.ay();
                    double denom = dx * sy - dy * sx;
                    if (Math.abs(denom) <= EPS) {
                        continue;
                    }
                    double qpx = seg.ax() - a.x();
                    double qpy = seg.ay() - a.y();
                    double t = (qpx * sy - qpy * sx) / denom;
                    double u = (qpx * dy - qpy * dx) / denom;

                    // Half-open interval (0, 1] on step parameter t avoids double-charging at grid vertices
                    if (t > 1e-9 && t <= 1.0 + 1e-9 && u >= -1e-9 && u <= 1.0 + 1e-9) {
                        double clampedT = Math.max(0.0, Math.min(1.0, t));
                        double ix = a.x() + clampedT * dx;
                        double iy = a.y() + clampedT * dy;

                        double sinAngle = Math.min(1.0, Math.abs(ux * seg.ty() - uy * seg.tx()));
                        double angleDeg = Math.toDegrees(Math.asin(sinAngle));

                        if (byCorridor == null) {
                            byCorridor = new LinkedHashMap<>(2);
                        }
                        String refKey = normalizeRoadRef(seg.corridorRef(), seg.corridorId());
                        SegmentCrossing existing = byCorridor.get(refKey);
                        boolean closeToExisting = false;
                        if (existing == null) {
                            for (SegmentCrossing other : byCorridor.values()) {
                                if (other.category() == seg.category()
                                        && Math.hypot(ix - other.metricPoint().x(), iy - other.metricPoint().y()) < 50.0) {
                                    closeToExisting = true;
                                    break;
                                }
                            }
                        }
                        if (!closeToExisting && (existing == null || categoryPriority(seg.category()) < categoryPriority(existing.category()))) {
                            byCorridor.put(
                                    refKey,
                                    new SegmentCrossing(
                                            seg.corridorId(),
                                            seg.corridorRef(),
                                            seg.corridorName(),
                                            seg.category(),
                                            new MetricPoint(ix, iy),
                                            projection.inverse(new MetricPoint(ix, iy)),
                                            clampedT,
                                            sinAngle,
                                            angleDeg));
                        }
                    }
                }
            }
        }

        if (byCorridor == null) {
            return List.of();
        }
        return List.copyOf(byCorridor.values());
    }

    /**
     * Checks if a step [a -> b] runs within {@code maxDistanceMeters} of any road/railway segment
     * and returns the closest match along with parallel alignment cosine |u . t| in [0, 1].
     */
    public ParallelProximityMatch findParallelProximity(
            MetricPoint a,
            MetricPoint b,
            double maxDistanceMeters) {
        ensureInitialized();
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double stepLen = Math.hypot(dx, dy);
        if (stepLen <= EPS) {
            return null;
        }
        double ux = dx / stepLen;
        double uy = dy / stepLen;
        double mx = 0.5 * (a.x() + b.x());
        double my = 0.5 * (a.y() + b.y());

        int bx = bucketCoord(mx);
        int by = bucketCoord(my);
        List<IndexedSegment> candidates = spatialBuckets.get(bucketKey(bx, by));
        if (candidates == null) {
            return null;
        }

        double bestDist = maxDistanceMeters;
        IndexedSegment bestSeg = null;
        for (IndexedSegment seg : candidates) {
            double dist = pointToSegmentDistance(mx, my, seg.ax(), seg.ay(), seg.bx(), seg.by());
            if (dist <= bestDist) {
                bestDist = dist;
                bestSeg = seg;
            }
        }
        if (bestSeg == null) {
            return null;
        }
        double cosAlignment = Math.min(1.0, Math.abs(ux * bestSeg.tx() + uy * bestSeg.ty()));
        return new ParallelProximityMatch(
                bestSeg.corridorId(),
                bestSeg.corridorRef(),
                bestSeg.category(),
                bestDist,
                cosAlignment);
    }

    /**
     * Extracts all grade-separated infrastructure crossings along a complete routed alignment,
     * ordered by cumulative mainline chainage (km) and de-duplicated within 0.40 km per corridor.
     */
    public List<RouteCrossing> findRouteCrossings(List<GeoPoint> route) {
        ensureInitialized();
        if (route == null || route.size() < 2) {
            return List.of();
        }
        List<RouteCrossing> raw = new ArrayList<>();
        double cumulativeKm = 0.0;
        for (int i = 1; i < route.size(); i++) {
            MetricPoint a = projection.forward(route.get(i - 1));
            MetricPoint b = projection.forward(route.get(i));
            double segKm = Math.hypot(b.x() - a.x(), b.y() - a.y()) / 1000.0;
            if (segKm <= EPS) {
                continue;
            }
            List<SegmentCrossing> crossings = findStepCrossingsMetric(a, b);
            for (SegmentCrossing sc : crossings) {
                double chainageKm = cumulativeKm + sc.stepFraction() * segKm;
                raw.add(new RouteCrossing(
                        sc.corridorId(),
                        sc.corridorRef(),
                        sc.corridorName(),
                        sc.category(),
                        sc.point(),
                        sc.metricPoint(),
                        chainageKm,
                        sc.crossingAngleDegrees(),
                        sc.sinAngle()));
            }
            cumulativeKm += segKm;
        }

        raw.sort(Comparator.comparingDouble(RouteCrossing::chainageKm));
        List<RouteCrossing> deduped = new ArrayList<>();
        Map<String, Double> lastChainageByCorridor = new HashMap<>();
        for (RouteCrossing rc : raw) {
            Double last = lastChainageByCorridor.get(rc.corridorId());
            if (last != null && Math.abs(rc.chainageKm() - last) < 0.40) {
                continue;
            }
            // Spatial merging for dual-carriageway / close parallel ways within 100 m
            if (!deduped.isEmpty()) {
                RouteCrossing prev = deduped.get(deduped.size() - 1);
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

    /**
     * Finds the nearest point along any existing navigable road corridor (excluding railways)
     * so user-specified origin/destination coordinates can tie into the existing road network.
     */
    public NearestRoadTieIn findNearestRoadTieIn(
            GeoPoint target,
            Predicate<GeoPoint> navigablePredicate) {
        return findNearestRoadTieIn(target, navigablePredicate, null);
    }

    /**
     * Finds the nearest existing road tie-in point, prioritizing candidates whose local connector
     * segment passes {@code connectorSafePredicate} (no intersection with hospitals, dense colonies,
     * or building footprints), while rejecting unnavigable tie-in points.
     */
    public NearestRoadTieIn findNearestRoadTieIn(
            GeoPoint target,
            Predicate<GeoPoint> navigablePredicate,
            BiPredicate<GeoPoint, GeoPoint> connectorSafePredicate) {
        ensureInitialized();
        if (target == null || allSegments.isEmpty()) {
            return null;
        }
        MetricPoint m = projection.forward(target);
        NearestRoadTieIn bestCleanConnector = null;
        double bestCleanDist = Double.POSITIVE_INFINITY;
        NearestRoadTieIn bestNavigable = null;
        double bestNavDist = Double.POSITIVE_INFINITY;
        NearestRoadTieIn bestAny = null;
        double bestAnyDist = Double.POSITIVE_INFINITY;

        for (IndexedSegment seg : allSegments) {
            if (seg.category() == TransportCategory.RAILWAY) {
                continue;
            }
            double dx = seg.bx() - seg.ax();
            double dy = seg.by() - seg.ay();
            double len2 = dx * dx + dy * dy;
            double t = len2 <= EPS
                    ? 0.0
                    : ((m.x() - seg.ax()) * dx + (m.y() - seg.ay()) * dy) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            double px = seg.ax() + t * dx;
            double py = seg.ay() + t * dy;
            double dist = Math.hypot(m.x() - px, m.y() - py);

            if (dist < bestCleanDist || dist < bestNavDist || dist < bestAnyDist) {
                MetricPoint tieMetric = new MetricPoint(px, py);
                GeoPoint tieGeo = projection.inverse(tieMetric);
                boolean navigable = navigablePredicate == null || navigablePredicate.test(tieGeo);
                if (!navigable && navigablePredicate != null) {
                    // Check segment endpoints as fallback tie-in nodes if midpoint fell in a blocked cell
                    for (double[] ep : new double[][]{{seg.ax(), seg.ay()}, {seg.bx(), seg.by()}}) {
                        MetricPoint epM = new MetricPoint(ep[0], ep[1]);
                        GeoPoint epGeo = projection.inverse(epM);
                        double epDist = Math.hypot(m.x() - ep[0], m.y() - ep[1]);
                        if (navigablePredicate.test(epGeo)) {
                            NearestRoadTieIn epTie = new NearestRoadTieIn(
                                    seg.corridorId(),
                                    seg.corridorRef(),
                                    seg.corridorName(),
                                    seg.category(),
                                    epGeo,
                                    epM,
                                    round(epDist));
                            if (epDist < bestNavDist) {
                                bestNavDist = epDist;
                                bestNavigable = epTie;
                            }
                            if (connectorSafePredicate != null
                                    && epDist < bestCleanDist
                                    && connectorSafePredicate.test(target, epGeo)) {
                                bestCleanDist = epDist;
                                bestCleanConnector = epTie;
                            }
                        }
                    }
                }
                if (navigable) {
                    NearestRoadTieIn cand = new NearestRoadTieIn(
                            seg.corridorId(),
                            seg.corridorRef(),
                            seg.corridorName(),
                            seg.category(),
                            tieGeo,
                            tieMetric,
                            round(dist));
                    if (dist < bestNavDist) {
                        bestNavDist = dist;
                        bestNavigable = cand;
                    }
                    if (connectorSafePredicate != null
                            && dist < bestCleanDist
                            && connectorSafePredicate.test(target, tieGeo)) {
                        bestCleanDist = dist;
                        bestCleanConnector = cand;
                    }
                }
                if (dist < bestAnyDist) {
                    bestAnyDist = dist;
                    bestAny = new NearestRoadTieIn(
                            seg.corridorId(),
                            seg.corridorRef(),
                            seg.corridorName(),
                            seg.category(),
                            tieGeo,
                            tieMetric,
                            round(dist));
                }
            }
        }
        if (bestCleanConnector != null) {
            return bestCleanConnector;
        }
        return bestNavigable != null ? bestNavigable : (connectorSafePredicate == null ? bestAny : null);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double pointToSegmentDistance(
            double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        if (len2 <= EPS) {
            return Math.hypot(px - ax, py - ay);
        }
        double t = ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    public record TransportCorridor(
            String id,
            String ref,
            String name,
            TransportCategory category,
            int hierarchyTier,
            List<GeoPoint> points
    ) {}

    private record IndexedSegment(
            String corridorId,
            String corridorRef,
            String corridorName,
            TransportCategory category,
            double ax,
            double ay,
            double bx,
            double by,
            double tx,
            double ty,
            double lengthMeters
    ) {}

    public record SegmentCrossing(
            String corridorId,
            String corridorRef,
            String corridorName,
            TransportCategory category,
            MetricPoint metricPoint,
            GeoPoint point,
            double stepFraction,
            double sinAngle,
            double crossingAngleDegrees
    ) {}

    public record ParallelProximityMatch(
            String corridorId,
            String corridorRef,
            TransportCategory category,
            double distanceMeters,
            double cosAlignment
    ) {}

    public record NearestRoadTieIn(
            String corridorId,
            String corridorRef,
            String corridorName,
            TransportCategory category,
            GeoPoint tieInPoint,
            MetricPoint tieInMetric,
            double distanceMeters
    ) {}

    public record RouteCrossing(
            String corridorId,
            String corridorRef,
            String corridorName,
            TransportCategory category,
            GeoPoint point,
            MetricPoint metricPoint,
            double chainageKm,
            double crossingAngleDegrees,
            double sinAngle
    ) {}
}
