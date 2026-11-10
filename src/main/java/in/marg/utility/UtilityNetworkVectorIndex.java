package in.marg.utility;

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
 * In-memory EPSG:32646 spatial vector index for overhead high-voltage transmission lines,
 * electrical substations, and transmission tower assets.
 * <p>
 * Provides exact 2D transmission line crossing detection, approach skew angles (90 deg optimal
 * perpendicular crossing vs acute longitudinal spans under charged conductors), protective span sizing,
 * nearest transmission tower clearance detection, and longitudinal parallel corridor encroachment penalty.
 */
@Component
public class UtilityNetworkVectorIndex {

    private static final double BUCKET_SIZE_METERS = 2000.0;
    private static final double EPS = 1e-9;

    private final CoordinateProjection projection;
    private final String linesGeoJsonPath;
    private final String assetsGeoJsonPath;
    private final ObjectMapper objectMapper;

    private boolean initialized = false;
    private final List<TransmissionLineCorridor> lines = new ArrayList<>();
    private final List<UtilityAsset> assets = new ArrayList<>();
    private final List<UtilityAsset> towers = new ArrayList<>();
    private final Map<Long, List<IndexedLineSegment>> lineBuckets = new HashMap<>();
    private final Map<Long, List<UtilityAsset>> assetBuckets = new HashMap<>();

    @Autowired
    public UtilityNetworkVectorIndex(
            CoordinateProjection projection,
            @Value("${marg.utility.lines-geojson-path:data/processed/utilities/assam_power_transmission_lines.geojson}")
            String linesGeoJsonPath,
            @Value("${marg.utility.assets-geojson-path:data/processed/utilities/assam_utility_assets.geojson}")
            String assetsGeoJsonPath,
            ObjectMapper objectMapper) {
        this.projection = projection;
        this.linesGeoJsonPath = linesGeoJsonPath;
        this.assetsGeoJsonPath = assetsGeoJsonPath;
        this.objectMapper = objectMapper;
    }

    public UtilityNetworkVectorIndex(
            CoordinateProjection projection,
            String linesGeoJsonPath,
            String assetsGeoJsonPath) {
        this(projection, linesGeoJsonPath, assetsGeoJsonPath, new ObjectMapper());
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        loadLines();
        loadAssets();
        initialized = true;
    }

    private void loadLines() {
        File file = new File(linesGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Transmission lines GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + linesGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                String id = p.get("id").asString();
                String name = p.get("name").asString();
                UtilityCorridorTier tier =
                        UtilityCorridorTier.valueOf(p.get("utilityTier").asString());
                Integer voltageKv = p.has("voltageKv") && !p.get("voltageKv").isNull()
                        ? p.get("voltageKv").asInt()
                        : null;
                boolean voltageInferred = p.get("voltageInferred").asBoolean(false);
                String circuit = p.get("circuitConfiguration").asString("S/C");
                double rowWidth = p.get("designRowWidthMeters").asDouble(tier.designRowWidthMeters());
                double clearance = p.get("minimumConductorClearanceMeters").asDouble(tier.minimumConductorClearanceMeters());
                double span = p.get("typicalSpanMeters").asDouble(tier.typicalSpanMeters());

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

                lines.add(new TransmissionLineCorridor(
                        id,
                        name,
                        tier,
                        voltageKv,
                        voltageInferred,
                        circuit,
                        rowWidth,
                        clearance,
                        span,
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
                    IndexedLineSegment seg = new IndexedLineSegment(
                            id,
                            name,
                            tier,
                            voltageKv,
                            circuit,
                            rowWidth,
                            clearance,
                            span,
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
                    "Failed to load transmission lines GeoJSON: " + linesGeoJsonPath, e);
        }
    }

    private void loadAssets() {
        File file = new File(assetsGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Utility assets GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + assetsGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                String id = p.get("id").asString();
                String type = p.get("assetType").asString();
                String name = p.has("name") && !p.get("name").isNull() ? p.get("name").asString() : id;
                double footprintRadius = p.get("footprintRadiusMeters").asDouble(30.0);
                double exclusionRadius = p.has("exclusionRadiusMeters")
                        ? p.get("exclusionRadiusMeters").asDouble(footprintRadius + 50.0)
                        : footprintRadius + 50.0;

                JsonNode coordsNode = feat.get("geometry").get("coordinates");
                double lon = coordsNode.get(0).asDouble();
                double lat = coordsNode.get(1).asDouble();
                GeoPoint pt = new GeoPoint(lat, lon);
                MetricPoint mpt = projection.forward(pt);

                UtilityAsset asset = new UtilityAsset(id, type, name, pt, mpt, footprintRadius, exclusionRadius);
                assets.add(asset);
                if ("TRANSMISSION_TOWER".equals(type)) {
                    towers.add(asset);
                }

                int bx = bucketCoord(mpt.x());
                int by = bucketCoord(mpt.y());
                assetBuckets.computeIfAbsent(bucketKey(bx, by), k -> new ArrayList<>()).add(asset);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load utility assets GeoJSON: " + assetsGeoJsonPath, e);
        }
    }

    private void insertSegmentIntoBuckets(IndexedLineSegment seg) {
        int minBx = bucketCoord(Math.min(seg.ax(), seg.bx()) - 200.0);
        int maxBx = bucketCoord(Math.max(seg.ax(), seg.bx()) + 200.0);
        int minBy = bucketCoord(Math.min(seg.ay(), seg.by()) - 200.0);
        int maxBy = bucketCoord(Math.max(seg.ay(), seg.by()) + 200.0);
        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                lineBuckets
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

    public List<TransmissionLineCorridor> allLines() {
        ensureInitialized();
        return List.copyOf(lines);
    }

    public List<UtilityAsset> allAssets() {
        ensureInitialized();
        return List.copyOf(assets);
    }

    public List<UtilityAsset> allTowers() {
        ensureInitialized();
        return List.copyOf(towers);
    }

    public double findNearestTowerDistanceMeters(MetricPoint p) {
        ensureInitialized();
        int centerBx = bucketCoord(p.x());
        int centerBy = bucketCoord(p.y());
        double minDistance = Double.POSITIVE_INFINITY;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                List<UtilityAsset> inBucket = assetBuckets.get(bucketKey(centerBx + dx, centerBy + dy));
                if (inBucket == null) continue;
                for (UtilityAsset asset : inBucket) {
                    if ("TRANSMISSION_TOWER".equals(asset.assetType())) {
                        double d = Math.hypot(asset.metricPoint().x() - p.x(), asset.metricPoint().y() - p.y());
                        if (d < minDistance) {
                            minDistance = d;
                        }
                    }
                }
            }
        }
        return minDistance;
    }

    public List<LineSegmentCrossing> findStepCrossings(GeoPoint from, GeoPoint to) {
        ensureInitialized();
        return findStepCrossingsMetric(projection.forward(from), projection.forward(to));
    }

    public List<LineSegmentCrossing> findStepCrossingsMetric(MetricPoint a, MetricPoint b) {
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

        Map<String, LineSegmentCrossing> byLine = null;

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                List<IndexedLineSegment> candidates = lineBuckets.get(bucketKey(bx, by));
                if (candidates == null) {
                    continue;
                }
                for (IndexedLineSegment seg : candidates) {
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
                        MetricPoint crossMetric = new MetricPoint(ix, iy);

                        double sinAngle = Math.min(1.0, Math.abs(ux * seg.ty() - uy * seg.tx()));
                        double angleDeg = Math.toDegrees(Math.asin(sinAngle));
                        double nearestTowerDist = findNearestTowerDistanceMeters(crossMetric);

                        double protectiveSpanMeters = Math.max(
                                seg.designRowWidthMeters(),
                                seg.designRowWidthMeters() / Math.max(sinAngle, 0.30));

                        if (byLine == null) {
                            byLine = new LinkedHashMap<>(2);
                        }
                        LineSegmentCrossing existing = byLine.get(seg.lineId());
                        if (existing == null || clampedT < existing.stepFraction()) {
                            byLine.put(
                                    seg.lineId(),
                                    new LineSegmentCrossing(
                                            seg.lineId(),
                                            seg.lineName(),
                                            seg.tier(),
                                            seg.voltageKv(),
                                            seg.circuit(),
                                            seg.designRowWidthMeters(),
                                            seg.minimumConductorClearanceMeters(),
                                            protectiveSpanMeters,
                                            crossMetric,
                                            projection.inverse(crossMetric),
                                            clampedT,
                                            sinAngle,
                                            angleDeg,
                                            nearestTowerDist));
                        }
                    }
                }
            }
        }

        if (byLine == null) {
            return List.of();
        }
        return List.copyOf(byLine.values());
    }

    public ParallelUtilityMatch findParallelCorridorProximity(
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
        List<IndexedLineSegment> candidates = lineBuckets.get(bucketKey(bx, by));
        if (candidates == null) {
            return null;
        }

        double bestDist = maxDistanceMeters;
        IndexedLineSegment bestSeg = null;
        for (IndexedLineSegment seg : candidates) {
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
        return new ParallelUtilityMatch(
                bestSeg.lineId(),
                bestSeg.lineName(),
                bestSeg.tier(),
                bestDist,
                cosAlignment);
    }

    public List<RouteUtilityCrossing> findRouteUtilityCrossings(List<GeoPoint> route) {
        ensureInitialized();
        if (route == null || route.size() < 2) {
            return List.of();
        }
        List<RouteUtilityCrossing> raw = new ArrayList<>();
        double cumulativeKm = 0.0;
        for (int i = 1; i < route.size(); i++) {
            MetricPoint a = projection.forward(route.get(i - 1));
            MetricPoint b = projection.forward(route.get(i));
            double segKm = Math.hypot(b.x() - a.x(), b.y() - a.y()) / 1000.0;
            if (segKm <= EPS) {
                continue;
            }
            List<LineSegmentCrossing> crossings = findStepCrossingsMetric(a, b);
            for (LineSegmentCrossing sc : crossings) {
                double chainageKm = cumulativeKm + sc.stepFraction() * segKm;
                raw.add(new RouteUtilityCrossing(
                        sc.lineId(),
                        sc.lineName(),
                        sc.tier(),
                        sc.voltageKv(),
                        sc.circuit(),
                        sc.designRowWidthMeters(),
                        sc.minimumConductorClearanceMeters(),
                        sc.recommendedProtectiveSpanMeters(),
                        sc.point(),
                        sc.metricPoint(),
                        chainageKm,
                        sc.crossingAngleDegrees(),
                        sc.sinAngle(),
                        sc.nearestTowerDistanceMeters()));
            }
            cumulativeKm += segKm;
        }

        raw.sort(Comparator.comparingDouble(RouteUtilityCrossing::chainageKm));
        List<RouteUtilityCrossing> deduped = new ArrayList<>();
        Map<String, Double> lastChainageByLine = new HashMap<>();
        for (RouteUtilityCrossing rc : raw) {
            Double last = lastChainageByLine.get(rc.lineId());
            if (last != null && Math.abs(rc.chainageKm() - last) < 0.50) {
                continue;
            }
            deduped.add(rc);
            lastChainageByLine.put(rc.lineId(), rc.chainageKm());
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

    public record TransmissionLineCorridor(
            String id,
            String name,
            UtilityCorridorTier tier,
            Integer voltageKv,
            boolean voltageInferred,
            String circuit,
            double designRowWidthMeters,
            double minimumConductorClearanceMeters,
            double typicalSpanMeters,
            List<GeoPoint> points
    ) {}

    public record UtilityAsset(
            String id,
            String assetType,
            String name,
            GeoPoint point,
            MetricPoint metricPoint,
            double footprintRadiusMeters,
            double exclusionRadiusMeters
    ) {}

    private record IndexedLineSegment(
            String lineId,
            String lineName,
            UtilityCorridorTier tier,
            Integer voltageKv,
            String circuit,
            double designRowWidthMeters,
            double minimumConductorClearanceMeters,
            double typicalSpanMeters,
            double ax,
            double ay,
            double bx,
            double by,
            double tx,
            double ty
    ) {}

    public record LineSegmentCrossing(
            String lineId,
            String lineName,
            UtilityCorridorTier tier,
            Integer voltageKv,
            String circuit,
            double designRowWidthMeters,
            double minimumConductorClearanceMeters,
            double recommendedProtectiveSpanMeters,
            MetricPoint metricPoint,
            GeoPoint point,
            double stepFraction,
            double sinAngle,
            double crossingAngleDegrees,
            double nearestTowerDistanceMeters
    ) {}

    public record ParallelUtilityMatch(
            String lineId,
            String lineName,
            UtilityCorridorTier tier,
            double distanceMeters,
            double cosAlignment
    ) {}

    public record RouteUtilityCrossing(
            String lineId,
            String lineName,
            UtilityCorridorTier tier,
            Integer voltageKv,
            String circuit,
            double designRowWidthMeters,
            double minimumConductorClearanceMeters,
            double recommendedProtectiveSpanMeters,
            GeoPoint point,
            MetricPoint metricPoint,
            double chainageKm,
            double crossingAngleDegrees,
            double sinAngle,
            double nearestTowerDistanceMeters
    ) {}
}
