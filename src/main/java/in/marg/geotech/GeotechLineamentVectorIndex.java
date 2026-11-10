package in.marg.geotech;

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
 * In-memory EPSG:32646 spatial vector index for tectonic fault/shear zones, unstable colluvial
 * landslide scarps, and Seismic Zone V liquefiable alluvial troughs.
 * <p>
 * Provides exact 2D lineament crossing detection, strike/scarp approach angles (90 deg optimal
 * minimum fractured-rock traverse vs acute toe-undercutting), stabilization length sizing,
 * and longitudinal side-hill scarp undercutting detection.
 */
@Component
public class GeotechLineamentVectorIndex {

    private static final double BUCKET_SIZE_METERS = 2000.0;
    private static final double EPS = 1e-9;

    private final CoordinateProjection projection;
    private final String lineamentsGeoJsonPath;
    private final ObjectMapper objectMapper;

    private boolean initialized = false;
    private final List<GeotechHazardLineament> lineaments = new ArrayList<>();
    private final Map<Long, List<IndexedLineamentSegment>> spatialBuckets = new HashMap<>();

    @Autowired
    public GeotechLineamentVectorIndex(
            CoordinateProjection projection,
            @Value("${marg.geotech.lineaments-geojson-path:data/processed/geotech/assam_fault_landslide_zones.geojson}")
            String lineamentsGeoJsonPath,
            ObjectMapper objectMapper) {
        this.projection = projection;
        this.lineamentsGeoJsonPath = lineamentsGeoJsonPath;
        this.objectMapper = objectMapper;
    }

    public GeotechLineamentVectorIndex(
            CoordinateProjection projection,
            String lineamentsGeoJsonPath) {
        this(projection, lineamentsGeoJsonPath, new ObjectMapper());
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        loadLineaments();
        initialized = true;
    }

    private void loadLineaments() {
        File file = new File(lineamentsGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Geotechnical lineaments GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + lineamentsGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                String id = p.get("id").asString();
                String name = p.get("name").asString();
                GeotechHazardTier tier =
                        GeotechHazardTier.valueOf(p.get("hazardTier").asString());
                String lithologyAndSoil = p.get("lithologyAndSoil").asString();
                String engineeringHazard = p.get("engineeringHazard").asString();
                double designWidthMeters = p.get("designHazardWidthMeters").asDouble(200.0);
                double minStabilizationLengthMeters =
                        p.get("minimumStabilizationLengthMeters").asDouble(140.0);

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

                lineaments.add(new GeotechHazardLineament(
                        id,
                        name,
                        tier,
                        lithologyAndSoil,
                        engineeringHazard,
                        designWidthMeters,
                        minStabilizationLengthMeters,
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
                    IndexedLineamentSegment seg = new IndexedLineamentSegment(
                            id,
                            name,
                            tier,
                            lithologyAndSoil,
                            engineeringHazard,
                            designWidthMeters,
                            minStabilizationLengthMeters,
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
                    "Failed to load geotechnical lineaments GeoJSON: " + lineamentsGeoJsonPath, e);
        }
    }

    private void insertSegmentIntoBuckets(IndexedLineamentSegment seg) {
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

    public List<GeotechHazardLineament> allLineaments() {
        ensureInitialized();
        return List.copyOf(lineaments);
    }

    public List<LineamentSegmentCrossing> findStepCrossings(GeoPoint from, GeoPoint to) {
        ensureInitialized();
        return findStepCrossingsMetric(projection.forward(from), projection.forward(to));
    }

    public List<LineamentSegmentCrossing> findStepCrossingsMetric(MetricPoint a, MetricPoint b) {
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

        Map<String, LineamentSegmentCrossing> byLineament = null;

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                List<IndexedLineamentSegment> candidates = spatialBuckets.get(bucketKey(bx, by));
                if (candidates == null) {
                    continue;
                }
                for (IndexedLineamentSegment seg : candidates) {
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
                        double stabilizationLengthMeters = Math.max(
                                seg.minimumStabilizationLengthMeters(),
                                seg.designHazardWidthMeters() / Math.max(sinAngle, 0.30));

                        if (byLineament == null) {
                            byLineament = new LinkedHashMap<>(2);
                        }
                        LineamentSegmentCrossing existing = byLineament.get(seg.lineamentId());
                        if (existing == null || clampedT < existing.stepFraction()) {
                            byLineament.put(
                                    seg.lineamentId(),
                                    new LineamentSegmentCrossing(
                                            seg.lineamentId(),
                                            seg.lineamentName(),
                                            seg.hazardTier(),
                                            seg.lithologyAndSoil(),
                                            seg.engineeringHazard(),
                                            seg.designHazardWidthMeters(),
                                            stabilizationLengthMeters,
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

        if (byLineament == null) {
            return List.of();
        }
        return List.copyOf(byLineament.values());
    }

    public ParallelScarpMatch findParallelScarpProximity(
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
        List<IndexedLineamentSegment> candidates = spatialBuckets.get(bucketKey(bx, by));
        if (candidates == null) {
            return null;
        }

        double bestDist = maxDistanceMeters;
        IndexedLineamentSegment bestSeg = null;
        for (IndexedLineamentSegment seg : candidates) {
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
        return new ParallelScarpMatch(
                bestSeg.lineamentId(),
                bestSeg.lineamentName(),
                bestSeg.hazardTier(),
                bestDist,
                cosAlignment);
    }

    public List<RouteGeotechCrossing> findRouteLineamentCrossings(List<GeoPoint> route) {
        ensureInitialized();
        if (route == null || route.size() < 2) {
            return List.of();
        }
        List<RouteGeotechCrossing> raw = new ArrayList<>();
        double cumulativeKm = 0.0;
        for (int i = 1; i < route.size(); i++) {
            MetricPoint a = projection.forward(route.get(i - 1));
            MetricPoint b = projection.forward(route.get(i));
            double segKm = Math.hypot(b.x() - a.x(), b.y() - a.y()) / 1000.0;
            if (segKm <= EPS) {
                continue;
            }
            List<LineamentSegmentCrossing> crossings = findStepCrossingsMetric(a, b);
            for (LineamentSegmentCrossing sc : crossings) {
                double chainageKm = cumulativeKm + sc.stepFraction() * segKm;
                raw.add(new RouteGeotechCrossing(
                        sc.lineamentId(),
                        sc.lineamentName(),
                        sc.hazardTier(),
                        sc.lithologyAndSoil(),
                        sc.engineeringHazard(),
                        sc.designHazardWidthMeters(),
                        sc.recommendedStabilizationLengthMeters(),
                        sc.point(),
                        sc.metricPoint(),
                        chainageKm,
                        sc.crossingAngleDegrees(),
                        sc.sinAngle()));
            }
            cumulativeKm += segKm;
        }

        raw.sort(Comparator.comparingDouble(RouteGeotechCrossing::chainageKm));
        List<RouteGeotechCrossing> deduped = new ArrayList<>();
        Map<String, Double> lastChainageByLineament = new HashMap<>();
        for (RouteGeotechCrossing rc : raw) {
            Double last = lastChainageByLineament.get(rc.lineamentId());
            if (last != null && Math.abs(rc.chainageKm() - last) < 0.60) {
                continue;
            }
            deduped.add(rc);
            lastChainageByLineament.put(rc.lineamentId(), rc.chainageKm());
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

    public record GeotechHazardLineament(
            String id,
            String name,
            GeotechHazardTier hazardTier,
            String lithologyAndSoil,
            String engineeringHazard,
            double designHazardWidthMeters,
            double minimumStabilizationLengthMeters,
            List<GeoPoint> points
    ) {}

    private record IndexedLineamentSegment(
            String lineamentId,
            String lineamentName,
            GeotechHazardTier hazardTier,
            String lithologyAndSoil,
            String engineeringHazard,
            double designHazardWidthMeters,
            double minimumStabilizationLengthMeters,
            double ax,
            double ay,
            double bx,
            double by,
            double tx,
            double ty
    ) {}

    public record LineamentSegmentCrossing(
            String lineamentId,
            String lineamentName,
            GeotechHazardTier hazardTier,
            String lithologyAndSoil,
            String engineeringHazard,
            double designHazardWidthMeters,
            double recommendedStabilizationLengthMeters,
            MetricPoint metricPoint,
            GeoPoint point,
            double stepFraction,
            double sinAngle,
            double crossingAngleDegrees
    ) {}

    public record ParallelScarpMatch(
            String lineamentId,
            String lineamentName,
            GeotechHazardTier hazardTier,
            double distanceMeters,
            double cosAlignment
    ) {}

    public record RouteGeotechCrossing(
            String lineamentId,
            String lineamentName,
            GeotechHazardTier hazardTier,
            String lithologyAndSoil,
            String engineeringHazard,
            double designHazardWidthMeters,
            double recommendedStabilizationLengthMeters,
            GeoPoint point,
            MetricPoint metricPoint,
            double chainageKm,
            double crossingAngleDegrees,
            double sinAngle
    ) {}
}
