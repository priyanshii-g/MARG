package in.marg.hydrology;

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
import java.util.Map;

/**
 * In-memory EPSG:32646 spatial vector index for perennial river & stream centerlines,
 * providing exact 2D bridge crossing detection, hydraulic approach angles,
 * effective skew bridge span estimation, and parallel riverbank encroachment checks.
 */
@Component
public class RiverNetworkVectorIndex {

    private static final double BUCKET_SIZE_METERS = 2000.0;
    private static final double EPS = 1e-9;

    private final CoordinateProjection projection;
    private final String riversGeoJsonPath;
    private final ObjectMapper objectMapper;

    private boolean initialized = false;
    private final List<RiverCorridor> rivers = new ArrayList<>();
    private final Map<Long, List<IndexedRiverSegment>> spatialBuckets = new HashMap<>();

    @Autowired
    public RiverNetworkVectorIndex(
            CoordinateProjection projection,
            @Value("${marg.hydrology.rivers-geojson-path:data/processed/hydrology/assam_perennial_rivers.geojson}")
            String riversGeoJsonPath,
            ObjectMapper objectMapper) {
        this.projection = projection;
        this.riversGeoJsonPath = riversGeoJsonPath;
        this.objectMapper = objectMapper;
    }

    public RiverNetworkVectorIndex(
            CoordinateProjection projection,
            String riversGeoJsonPath) {
        this(projection, riversGeoJsonPath, new ObjectMapper());
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        loadRivers();
        initialized = true;
    }

    private void loadRivers() {
        File file = new File(riversGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Perennial rivers GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + riversGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                String id = p.get("id").asString();
                String name = p.get("name").asString();
                RiverHierarchyClass riverClass =
                        RiverHierarchyClass.fromString(p.get("riverClass").asString());
                String basin = p.get("basin").asString();
                double designWidthMeters = p.get("designChannelWidthMeters").asDouble(50.0);

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

                    rivers.add(new RiverCorridor(
                            id, name, riverClass, basin, designWidthMeters, List.copyOf(geoPoints)));

                    for (int i = 1; i < metricPoints.size(); i++) {
                        MetricPoint a = metricPoints.get(i - 1);
                        MetricPoint b = metricPoints.get(i);
                        double dx = b.x() - a.x();
                        double dy = b.y() - a.y();
                        double len = Math.hypot(dx, dy);
                        if (len <= EPS) {
                            continue;
                        }
                        IndexedRiverSegment seg = new IndexedRiverSegment(
                                id,
                                name,
                                riverClass,
                                basin,
                                designWidthMeters,
                                a.x(),
                                a.y(),
                                b.x(),
                                b.y(),
                                dx / len,
                                dy / len);
                        insertSegmentIntoBuckets(seg);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load perennial rivers GeoJSON: " + riversGeoJsonPath, e);
        }
    }

    private void insertSegmentIntoBuckets(IndexedRiverSegment seg) {
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

    public List<RiverCorridor> allRivers() {
        ensureInitialized();
        return List.copyOf(rivers);
    }

    public List<RiverSegmentCrossing> findStepCrossings(GeoPoint from, GeoPoint to) {
        ensureInitialized();
        return findStepCrossingsMetric(projection.forward(from), projection.forward(to));
    }

    public List<RiverSegmentCrossing> findStepCrossingsMetric(MetricPoint a, MetricPoint b) {
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

        Map<String, RiverSegmentCrossing> byRiver = null;

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                List<IndexedRiverSegment> candidates = spatialBuckets.get(bucketKey(bx, by));
                if (candidates == null) {
                    continue;
                }
                for (IndexedRiverSegment seg : candidates) {
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

                    if (t > 1e-9 && t <= 1.0 + 1e-9 && u >= -1e-9 && u <= 1.0 + 1e-9) {
                        double clampedT = Math.max(0.0, Math.min(1.0, t));
                        double ix = a.x() + clampedT * dx;
                        double iy = a.y() + clampedT * dy;

                        double sinAngle = Math.min(1.0, Math.abs(ux * seg.ty() - uy * seg.tx()));
                        double angleDeg = Math.toDegrees(Math.asin(sinAngle));
                        double effectiveSpanMeters =
                                seg.designChannelWidthMeters() / Math.max(sinAngle, 0.35);

                        if (byRiver == null) {
                            byRiver = new LinkedHashMap<>(2);
                        }
                        RiverSegmentCrossing existing = byRiver.get(seg.riverId());
                        if (existing == null || clampedT < existing.stepFraction()) {
                            byRiver.put(
                                    seg.riverId(),
                                    new RiverSegmentCrossing(
                                            seg.riverId(),
                                            seg.riverName(),
                                            seg.riverClass(),
                                            seg.basin(),
                                            seg.designChannelWidthMeters(),
                                            effectiveSpanMeters,
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

        if (byRiver == null) {
            return List.of();
        }
        return List.copyOf(byRiver.values());
    }

    public ParallelBankMatch findParallelBankProximity(
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
        List<IndexedRiverSegment> candidates = spatialBuckets.get(bucketKey(bx, by));
        if (candidates == null) {
            return null;
        }

        double bestDist = maxDistanceMeters;
        IndexedRiverSegment bestSeg = null;
        for (IndexedRiverSegment seg : candidates) {
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
        return new ParallelBankMatch(
                bestSeg.riverId(),
                bestSeg.riverName(),
                bestSeg.riverClass(),
                bestDist,
                cosAlignment);
    }

    public List<RouteRiverCrossing> findRouteRiverCrossings(List<GeoPoint> route) {
        ensureInitialized();
        if (route == null || route.size() < 2) {
            return List.of();
        }
        List<RouteRiverCrossing> raw = new ArrayList<>();
        double cumulativeKm = 0.0;
        for (int i = 1; i < route.size(); i++) {
            MetricPoint a = projection.forward(route.get(i - 1));
            MetricPoint b = projection.forward(route.get(i));
            double segKm = Math.hypot(b.x() - a.x(), b.y() - a.y()) / 1000.0;
            if (segKm <= EPS) {
                continue;
            }
            List<RiverSegmentCrossing> crossings = findStepCrossingsMetric(a, b);
            for (RiverSegmentCrossing sc : crossings) {
                double chainageKm = cumulativeKm + sc.stepFraction() * segKm;
                raw.add(new RouteRiverCrossing(
                        sc.riverId(),
                        sc.riverName(),
                        sc.riverClass(),
                        sc.basin(),
                        sc.designChannelWidthMeters(),
                        sc.effectiveSkewSpanMeters(),
                        sc.point(),
                        sc.metricPoint(),
                        chainageKm,
                        sc.crossingAngleDegrees(),
                        sc.sinAngle()));
            }
            cumulativeKm += segKm;
        }

        raw.sort(Comparator.comparingDouble(RouteRiverCrossing::chainageKm));
        List<RouteRiverCrossing> deduped = new ArrayList<>();
        Map<String, Double> lastChainageByRiver = new HashMap<>();
        for (RouteRiverCrossing rc : raw) {
            Double last = lastChainageByRiver.get(rc.riverId());
            if (last != null && Math.abs(rc.chainageKm() - last) < 0.45) {
                continue;
            }
            deduped.add(rc);
            lastChainageByRiver.put(rc.riverId(), rc.chainageKm());
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

    public record RiverCorridor(
            String id,
            String name,
            RiverHierarchyClass riverClass,
            String basin,
            double designChannelWidthMeters,
            List<GeoPoint> points
    ) {}

    private record IndexedRiverSegment(
            String riverId,
            String riverName,
            RiverHierarchyClass riverClass,
            String basin,
            double designChannelWidthMeters,
            double ax,
            double ay,
            double bx,
            double by,
            double tx,
            double ty
    ) {}

    public record RiverSegmentCrossing(
            String riverId,
            String riverName,
            RiverHierarchyClass riverClass,
            String basin,
            double designChannelWidthMeters,
            double effectiveSkewSpanMeters,
            MetricPoint metricPoint,
            GeoPoint point,
            double stepFraction,
            double sinAngle,
            double crossingAngleDegrees
    ) {}

    public record ParallelBankMatch(
            String riverId,
            String riverName,
            RiverHierarchyClass riverClass,
            double distanceMeters,
            double cosAlignment
    ) {}

    public record RouteRiverCrossing(
            String riverId,
            String riverName,
            RiverHierarchyClass riverClass,
            String basin,
            double designChannelWidthMeters,
            double effectiveSkewSpanMeters,
            GeoPoint point,
            MetricPoint metricPoint,
            double chainageKm,
            double crossingAngleDegrees,
            double sinAngle
    ) {}
}
