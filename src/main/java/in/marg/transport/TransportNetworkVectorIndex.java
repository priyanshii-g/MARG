package in.marg.transport;

import in.marg.model.GeoPoint;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
                String name = props.get("name").asString();
                TransportCategory category =
                        TransportCategory.valueOf(props.get("category").asString());
                int tier = props.get("hierarchyTier").asInt(1);

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
                            || (h.name().toLowerCase(Locale.ROOT) + " town").equals(token))
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                String valid = hubs.stream()
                        .map(h -> h.name() + " (" + h.hubId() + ")")
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException(
                        "Unknown target hub '" + raw + "'. Available regional hubs: " + valid);
            }
            if (!resolved.contains(match)) {
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
                        SegmentCrossing existing = byCorridor.get(seg.corridorId());
                        if (existing == null || clampedT < existing.stepFraction()) {
                            byCorridor.put(
                                    seg.corridorId(),
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
            deduped.add(rc);
            lastChainageByCorridor.put(rc.corridorId(), rc.chainageKm());
        }
        return List.copyOf(deduped);
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
