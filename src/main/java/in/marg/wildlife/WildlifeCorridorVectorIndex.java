package in.marg.wildlife;

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
 * In-memory EPSG:32646 spatial vector index for wildlife migration corridor axes,
 * providing exact 2D corridor crossing detection, barrier approach angles,
 * WII-compliant elevated wildlife viaduct / animal underpass span sizing,
 * and longitudinal corridor fragmentation checks.
 */
@Component
public class WildlifeCorridorVectorIndex {

    private static final double BUCKET_SIZE_METERS = 2000.0;
    private static final double EPS = 1e-9;

    private final CoordinateProjection projection;
    private final String corridorsGeoJsonPath;
    private final ObjectMapper objectMapper;

    private boolean initialized = false;
    private final List<WildlifeMigrationCorridor> corridors = new ArrayList<>();
    private final Map<Long, List<IndexedCorridorSegment>> spatialBuckets = new HashMap<>();

    @Autowired
    public WildlifeCorridorVectorIndex(
            CoordinateProjection projection,
            @Value("${marg.wildlife.corridors-geojson-path:data/processed/wildlife/assam_wildlife_corridors.geojson}")
            String corridorsGeoJsonPath,
            ObjectMapper objectMapper) {
        this.projection = projection;
        this.corridorsGeoJsonPath = corridorsGeoJsonPath;
        this.objectMapper = objectMapper;
    }

    public WildlifeCorridorVectorIndex(
            CoordinateProjection projection,
            String corridorsGeoJsonPath) {
        this(projection, corridorsGeoJsonPath, new ObjectMapper());
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        loadCorridors();
        initialized = true;
    }

    private void loadCorridors() {
        File file = new File(corridorsGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Wildlife corridors GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + corridorsGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                String id = p.get("id").asString();
                String name = p.get("name").asString();
                WildlifeCorridorTier tier =
                        WildlifeCorridorTier.valueOf(p.get("corridorTier").asString());
                String targetSpecies = p.get("targetSpecies").asString();
                double designWidthMeters = p.get("designCorridorWidthMeters").asDouble(400.0);
                double minPassageSpanMeters = p.get("minimumPassageSpanMeters").asDouble(200.0);
                double minClearHeightMeters = p.get("minimumClearHeightMeters").asDouble(6.0);

                JsonNode coordsNode = feat.get("geometry").get("coordinates");
                List<GeoPoint> geoPoints = new ArrayList<>();
                List<MetricPoint> metricPoints = new ArrayList<>();
                for (JsonNode pt : coordsNode) {
                    double lon = pt.get(0).asDouble();
                    double lat = pt.get(1).asDouble();
                    GeoPoint gp = new GeoPoint(lat, lon);
                    geoPoints.add(gp);
                    metricPoints.add(projection.forward(gp));
                }

                corridors.add(new WildlifeMigrationCorridor(
                        id,
                        name,
                        tier,
                        targetSpecies,
                        designWidthMeters,
                        minPassageSpanMeters,
                        minClearHeightMeters,
                        List.copyOf(geoPoints)));

                for (int i = 1; i < metricPoints.size(); i++) {
                    MetricPoint a = metricPoints.get(i - 1);
                    MetricPoint b = metricPoints.get(i);
                    double dx = b.x() - a.x();
                    double dy = b.y() - a.y();
                    double len = Math.hypot(dx, dy);
                    if (len <= EPS) {
                        continue;
                    }
                    IndexedCorridorSegment seg = new IndexedCorridorSegment(
                            id,
                            name,
                            tier,
                            targetSpecies,
                            designWidthMeters,
                            minPassageSpanMeters,
                            minClearHeightMeters,
                            a.x(),
                            a.y(),
                            b.x(),
                            b.y(),
                            dx / len,
                            dy / len);
                    insertSegmentIntoBuckets(seg);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load wildlife corridors GeoJSON: " + corridorsGeoJsonPath, e);
        }
    }

    private void insertSegmentIntoBuckets(IndexedCorridorSegment seg) {
        int minBx = bucketCoord(Math.min(seg.ax(), seg.bx()) - 400.0);
        int maxBx = bucketCoord(Math.max(seg.ax(), seg.bx()) + 400.0);
        int minBy = bucketCoord(Math.min(seg.ay(), seg.by()) - 400.0);
        int maxBy = bucketCoord(Math.max(seg.ay(), seg.by()) + 400.0);
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

    public List<WildlifeMigrationCorridor> allCorridors() {
        ensureInitialized();
        return List.copyOf(corridors);
    }

    public List<CorridorSegmentCrossing> findStepCrossings(GeoPoint from, GeoPoint to) {
        ensureInitialized();
        return findStepCrossingsMetric(projection.forward(from), projection.forward(to));
    }

    public List<CorridorSegmentCrossing> findStepCrossingsMetric(MetricPoint a, MetricPoint b) {
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

        Map<String, CorridorSegmentCrossing> byCorridor = null;

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                List<IndexedCorridorSegment> candidates = spatialBuckets.get(bucketKey(bx, by));
                if (candidates == null) {
                    continue;
                }
                for (IndexedCorridorSegment seg : candidates) {
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
                        double recommendedSpanMeters = Math.max(
                                seg.minimumPassageSpanMeters(),
                                (seg.designCorridorWidthMeters() * 0.75) / Math.max(sinAngle, 0.35));

                        if (byCorridor == null) {
                            byCorridor = new LinkedHashMap<>(2);
                        }
                        CorridorSegmentCrossing existing = byCorridor.get(seg.corridorId());
                        if (existing == null || clampedT < existing.stepFraction()) {
                            byCorridor.put(
                                    seg.corridorId(),
                                    new CorridorSegmentCrossing(
                                            seg.corridorId(),
                                            seg.corridorName(),
                                            seg.corridorTier(),
                                            seg.targetSpecies(),
                                            seg.designCorridorWidthMeters(),
                                            recommendedSpanMeters,
                                            seg.minimumClearHeightMeters(),
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

    public ParallelCorridorMatch findParallelCorridorProximity(
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
        List<IndexedCorridorSegment> candidates = spatialBuckets.get(bucketKey(bx, by));
        if (candidates == null) {
            return null;
        }

        double bestDist = maxDistanceMeters;
        IndexedCorridorSegment bestSeg = null;
        for (IndexedCorridorSegment seg : candidates) {
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
        return new ParallelCorridorMatch(
                bestSeg.corridorId(),
                bestSeg.corridorName(),
                bestSeg.corridorTier(),
                bestDist,
                cosAlignment);
    }

    public List<RouteWildlifeCrossing> findRouteCorridorCrossings(List<GeoPoint> route) {
        ensureInitialized();
        if (route == null || route.size() < 2) {
            return List.of();
        }
        List<RouteWildlifeCrossing> raw = new ArrayList<>();
        double cumulativeKm = 0.0;
        for (int i = 1; i < route.size(); i++) {
            MetricPoint a = projection.forward(route.get(i - 1));
            MetricPoint b = projection.forward(route.get(i));
            double segKm = Math.hypot(b.x() - a.x(), b.y() - a.y()) / 1000.0;
            if (segKm <= EPS) {
                continue;
            }
            List<CorridorSegmentCrossing> crossings = findStepCrossingsMetric(a, b);
            for (CorridorSegmentCrossing sc : crossings) {
                double chainageKm = cumulativeKm + sc.stepFraction() * segKm;
                raw.add(new RouteWildlifeCrossing(
                        sc.corridorId(),
                        sc.corridorName(),
                        sc.corridorTier(),
                        sc.targetSpecies(),
                        sc.designCorridorWidthMeters(),
                        sc.recommendedPassageSpanMeters(),
                        sc.minimumClearHeightMeters(),
                        sc.point(),
                        sc.metricPoint(),
                        chainageKm,
                        sc.crossingAngleDegrees(),
                        sc.sinAngle()));
            }
            cumulativeKm += segKm;
        }

        raw.sort(Comparator.comparingDouble(RouteWildlifeCrossing::chainageKm));
        List<RouteWildlifeCrossing> deduped = new ArrayList<>();
        Map<String, Double> lastChainageByCorridor = new HashMap<>();
        for (RouteWildlifeCrossing rc : raw) {
            Double last = lastChainageByCorridor.get(rc.corridorId());
            if (last != null && Math.abs(rc.chainageKm() - last) < 0.60) {
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

    public record WildlifeMigrationCorridor(
            String id,
            String name,
            WildlifeCorridorTier corridorTier,
            String targetSpecies,
            double designCorridorWidthMeters,
            double minimumPassageSpanMeters,
            double minimumClearHeightMeters,
            List<GeoPoint> points
    ) {}

    private record IndexedCorridorSegment(
            String corridorId,
            String corridorName,
            WildlifeCorridorTier corridorTier,
            String targetSpecies,
            double designCorridorWidthMeters,
            double minimumPassageSpanMeters,
            double minimumClearHeightMeters,
            double ax,
            double ay,
            double bx,
            double by,
            double tx,
            double ty
    ) {}

    public record CorridorSegmentCrossing(
            String corridorId,
            String corridorName,
            WildlifeCorridorTier corridorTier,
            String targetSpecies,
            double designCorridorWidthMeters,
            double recommendedPassageSpanMeters,
            double minimumClearHeightMeters,
            MetricPoint metricPoint,
            GeoPoint point,
            double stepFraction,
            double sinAngle,
            double crossingAngleDegrees
    ) {}

    public record ParallelCorridorMatch(
            String corridorId,
            String corridorName,
            WildlifeCorridorTier corridorTier,
            double distanceMeters,
            double cosAlignment
    ) {}

    public record RouteWildlifeCrossing(
            String corridorId,
            String corridorName,
            WildlifeCorridorTier corridorTier,
            String targetSpecies,
            double designCorridorWidthMeters,
            double recommendedPassageSpanMeters,
            double minimumClearHeightMeters,
            GeoPoint point,
            MetricPoint metricPoint,
            double chainageKm,
            double crossingAngleDegrees,
            double sinAngle
    ) {}
}
