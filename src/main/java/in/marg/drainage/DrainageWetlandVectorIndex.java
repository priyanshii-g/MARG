package in.marg.drainage;

import in.marg.model.GeoPoint;
import in.marg.row.RightOfWayCorridorEngine;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
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
 * In-memory EPSG:32646 spatial vector index for MARG Layer 13:
 * Wetlands, Seasonal Streams &amp; Drainage Network.
 * <p>
 * Indexes all 5 hydrological sublayers:
 * <ol>
 *   <li><b>Wetlands ({@code WETLAND}):</b> 2D natural and designated wetland polygons
 *       (inland beels, oxbow swamps, seasonal marshes, community fishery tanks).</li>
 *   <li><b>Seasonal Streams ({@code SEASONAL_STREAM}):</b> Non-perennial monsoon hill torrents
 *       (<i>Jan</i> / <i>Langso</i> / <i>Nullah</i>).</li>
 *   <li><b>Drainage Network ({@code NATURAL_DRAINAGE_CHANNEL} &amp; {@code LOCAL_WATERCOURSE}):</b>
 *       Natural valley thalwegs, sheet-flow drainage swales, and local watercourses.</li>
 *   <li><b>Irrigation Canals ({@code IRRIGATION_CANAL}):</b> Existing Jamuna, Kaliani, and Dhansiri
 *       main, branch, and distributary irrigation canals.</li>
 *   <li><b>Watersheds ({@code WATERSHED}):</b> Contiguous 2D hydrologic catchment boundaries
 *       and sub-basins.</li>
 * </ol>
 */
@Component
public class DrainageWetlandVectorIndex {

    private static final double BUCKET_SIZE_METERS = 2000.0;
    private static final double SQ_METERS_PER_HECTARE = 10_000.0;
    private static final double EPS = 1e-9;

    private final CoordinateProjection projection;
    private final String wetlandsGeoJsonPath;
    private final String drainageNetworkGeoJsonPath;
    private final String watershedsGeoJsonPath;
    private final ObjectMapper objectMapper;
    private final GeometryFactory geometryFactory;

    private boolean initialized = false;
    private final List<WetlandPolygon> wetlands = new ArrayList<>();
    private final List<DrainageChannelLine> drainageLines = new ArrayList<>();
    private final List<WatershedBasinPolygon> watersheds = new ArrayList<>();

    private final Map<Long, List<WetlandPolygon>> wetlandBuckets = new HashMap<>();
    private final Map<Long, List<IndexedDrainageSegment>> segmentBuckets = new HashMap<>();

    @Autowired
    public DrainageWetlandVectorIndex(
            CoordinateProjection projection,
            @Value("${marg.drainage.wetlands-geojson-path:data/processed/drainage/assam_wetlands_polygons.geojson}")
            String wetlandsGeoJsonPath,
            @Value("${marg.drainage.network-geojson-path:data/processed/drainage/assam_drainage_network.geojson}")
            String drainageNetworkGeoJsonPath,
            @Value("${marg.drainage.watersheds-geojson-path:data/processed/drainage/assam_watershed_basins.geojson}")
            String watershedsGeoJsonPath,
            ObjectMapper objectMapper) {
        this.projection = projection;
        this.wetlandsGeoJsonPath = wetlandsGeoJsonPath;
        this.drainageNetworkGeoJsonPath = drainageNetworkGeoJsonPath;
        this.watershedsGeoJsonPath = watershedsGeoJsonPath;
        this.objectMapper = objectMapper;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 32646);
    }

    public DrainageWetlandVectorIndex(
            CoordinateProjection projection,
            String wetlandsGeoJsonPath,
            String drainageNetworkGeoJsonPath,
            String watershedsGeoJsonPath) {
        this(
                projection,
                wetlandsGeoJsonPath,
                drainageNetworkGeoJsonPath,
                watershedsGeoJsonPath,
                new ObjectMapper());
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        loadWatersheds();
        loadWetlands();
        loadDrainageNetwork();
        initialized = true;
    }

    private void loadWatersheds() {
        File file = new File(watershedsGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Watersheds GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + watershedsGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                String watershedId = p.has("watershedId") ? p.get("watershedId").asString() : "WS-" + watersheds.size();
                String watershedCode = p.has("watershedCode") ? p.get("watershedCode").asString() : watershedId;
                String name = p.has("name") ? p.get("name").asString() : watershedId;
                String majorRiverSystem = p.has("majorRiverSystem") ? p.get("majorRiverSystem").asString("General") : "General";
                double latMin = p.has("latMin") ? p.get("latMin").asDouble() : 0.0;
                double latMax = p.has("latMax") ? p.get("latMax").asDouble() : 90.0;
                double areaSqKm = p.has("areaSqKm") ? p.get("areaSqKm").asDouble(150.0) : 150.0;
                double runoffCoeff = p.has("runoffCoefficient") ? p.get("runoffCoefficient").asDouble(0.55) : 0.55;
                double tcMinutes = p.has("timeOfConcentrationMinutes") ? p.get("timeOfConcentrationMinutes").asDouble(85.0) : 85.0;
                double meanSlopeDeg = p.has("meanSlopeDegrees") ? p.get("meanSlopeDegrees").asDouble(8.0) : 8.0;
                double drainageDensity = p.has("drainageDensityKmPerSqKm") ? p.get("drainageDensityKmPerSqKm").asDouble(2.4) : 2.4;
                String sensitivity = p.has("floodAndBackwaterSensitivity") ? p.get("floodAndBackwaterSensitivity").asString("MODERATE") : "MODERATE";

                JsonNode ringNode = feat.get("geometry").get("coordinates").get(0);
                Coordinate[] coords = new Coordinate[ringNode.size()];
                for (int i = 0; i < ringNode.size(); i++) {
                    JsonNode pt = ringNode.get(i);
                    MetricPoint mp = projection.forward(new GeoPoint(pt.get(1).asDouble(), pt.get(0).asDouble()));
                    coords[i] = new Coordinate(mp.x(), mp.y());
                }
                if (coords.length >= 4 && !coords[0].equals2D(coords[coords.length - 1])) {
                    Coordinate[] closed = new Coordinate[coords.length + 1];
                    System.arraycopy(coords, 0, closed, 0, coords.length);
                    closed[coords.length] = new Coordinate(coords[0].x, coords[0].y);
                    coords = closed;
                }
                Polygon jtsPoly = geometryFactory.createPolygon(geometryFactory.createLinearRing(coords));

                watersheds.add(new WatershedBasinPolygon(
                        watershedId,
                        watershedCode,
                        name,
                        majorRiverSystem,
                        latMin,
                        latMax,
                        areaSqKm,
                        runoffCoeff,
                        tcMinutes,
                        meanSlopeDeg,
                        drainageDensity,
                        sensitivity,
                        jtsPoly));
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load watersheds GeoJSON: " + watershedsGeoJsonPath, e);
        }
    }

    private void loadWetlands() {
        File file = new File(wetlandsGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Wetlands polygons GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + wetlandsGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                String id = p.has("id") ? p.get("id").asString() : "WET-" + wetlands.size();
                String name = p.has("name") ? p.get("name").asString() : id;
                String wetlandCategory = p.has("wetlandCategory") ? p.get("wetlandCategory").asString("OXBOW_FLOODPLAIN_BEEL") : "OXBOW_FLOODPLAIN_BEEL";
                String district = p.has("district") ? p.get("district").asString("Karbi Anglong") : "Karbi Anglong";
                String watershedId = p.has("watershedId") ? p.get("watershedId").asString("WS-01-KOHORA-DIPHLU") : "WS-01-KOHORA-DIPHLU";
                String watershedName = p.has("watershedName") ? p.get("watershedName").asString("") : "";
                double areaHa = p.has("areaHectares") ? p.get("areaHectares").asDouble(12.0) : 12.0;
                double eqRadMeters = p.has("equivalentRadiusMeters") ? p.get("equivalentRadiusMeters").asDouble(200.0) : Math.sqrt(areaHa * 10000.0 / Math.PI);
                double seasonalDepthMeters = p.has("seasonalDepthMeters") ? p.get("seasonalDepthMeters").asDouble(2.5) : 2.5;
                String sensitivity = p.has("ecologicalAndHydrologicalSensitivity")
                        ? p.get("ecologicalAndHydrologicalSensitivity").asString("HIGH")
                        : (p.has("hydrologicalSensitivity") ? p.get("hydrologicalSensitivity").asString("HIGH") : "HIGH");
                boolean isProtectedBeel = p.has("isProtectedBeel") ? p.get("isProtectedBeel").asBoolean(false) : false;
                String recommendedMitigation = p.has("recommendedMitigation")
                        ? p.get("recommendedMitigation").asString("WETLAND_AVOIDANCE_OR_PILE_VIADUCT_EQUALIZER_CAUSEWAY")
                        : "WETLAND_AVOIDANCE_OR_PILE_VIADUCT_EQUALIZER_CAUSEWAY";

                JsonNode ringNode = feat.get("geometry").get("coordinates").get(0);
                List<GeoPoint> geoRing = new ArrayList<>(ringNode.size());
                Coordinate[] coords = new Coordinate[ringNode.size()];
                double minX = Double.POSITIVE_INFINITY;
                double minY = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY;
                double maxY = Double.NEGATIVE_INFINITY;

                for (int i = 0; i < ringNode.size(); i++) {
                    JsonNode pt = ringNode.get(i);
                    double lon = pt.get(0).asDouble();
                    double lat = pt.get(1).asDouble();
                    GeoPoint gp = new GeoPoint(lat, lon);
                    MetricPoint mp = projection.forward(gp);
                    geoRing.add(gp);
                    coords[i] = new Coordinate(mp.x(), mp.y());
                    if (mp.x() < minX) minX = mp.x();
                    if (mp.y() < minY) minY = mp.y();
                    if (mp.x() > maxX) maxX = mp.x();
                    if (mp.y() > maxY) maxY = mp.y();
                }
                if (coords.length >= 4 && !coords[0].equals2D(coords[coords.length - 1])) {
                    Coordinate[] closed = new Coordinate[coords.length + 1];
                    System.arraycopy(coords, 0, closed, 0, coords.length);
                    closed[coords.length] = new Coordinate(coords[0].x, coords[0].y);
                    coords = closed;
                }
                LinearRing lr = geometryFactory.createLinearRing(coords);
                Polygon jtsPoly = geometryFactory.createPolygon(lr);
                var jtsCentroid = jtsPoly.getCentroid();
                double utmX = p.has("utmX") && !p.get("utmX").isNull() ? p.get("utmX").asDouble() : jtsCentroid.getX();
                double utmY = p.has("utmY") && !p.get("utmY").isNull() ? p.get("utmY").asDouble() : jtsCentroid.getY();
                MetricPoint centroidMetric = new MetricPoint(utmX, utmY);
                GeoPoint centroidGeo = projection.inverse(centroidMetric);

                WetlandPolygon wp = new WetlandPolygon(
                        id,
                        name,
                        wetlandCategory,
                        district,
                        watershedId,
                        watershedName,
                        areaHa,
                        eqRadMeters,
                        seasonalDepthMeters,
                        sensitivity,
                        isProtectedBeel,
                        recommendedMitigation,
                        centroidGeo,
                        centroidMetric,
                        List.copyOf(geoRing),
                        jtsPoly,
                        minX,
                        minY,
                        maxX,
                        maxY);

                wetlands.add(wp);
                insertWetlandIntoBuckets(wp);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load wetlands polygons GeoJSON: " + wetlandsGeoJsonPath, e);
        }
    }

    private void loadDrainageNetwork() {
        File file = new File(drainageNetworkGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Drainage network GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + drainageNetworkGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                String id = p.has("id") ? p.get("id").asString() : "DRN-" + drainageLines.size();
                String name = p.has("name") ? p.get("name").asString() : id;
                DrainageSublayerType sublayerType = p.has("sublayerType")
                        ? DrainageSublayerType.valueOf(p.get("sublayerType").asString())
                        : DrainageSublayerType.SEASONAL_STREAM;
                int strahlerOrder = p.has("strahlerOrder") ? p.get("strahlerOrder").asInt(1) : 1;
                String watershedId = p.has("watershedId") ? p.get("watershedId").asString("WS-01-KOHORA-DIPHLU") : "WS-01-KOHORA-DIPHLU";
                String watershedName = p.has("watershedName") ? p.get("watershedName").asString("") : "";
                double designWidthMeters = p.has("designWidthMeters") ? p.get("designWidthMeters").asDouble(12.0) : 12.0;
                double peakDischargeM3PerSec = p.has("peakDischargeM3PerSec") ? p.get("peakDischargeM3PerSec").asDouble(25.0) : 25.0;
                double lengthKm = p.has("lengthKm") ? p.get("lengthKm").asDouble(5.0) : 5.0;
                String obstructionSensitivity = p.has("obstructionSensitivity") ? p.get("obstructionSensitivity").asString("MODERATE") : "MODERATE";
                String recommendedStructureType = p.has("recommendedStructureType")
                        ? p.get("recommendedStructureType").asString(sublayerType.defaultInfrastructureStructure())
                        : sublayerType.defaultInfrastructureStructure();

                JsonNode coordsNode = feat.get("geometry").get("coordinates");
                List<GeoPoint> geoPoints = new ArrayList<>(coordsNode.size());
                List<MetricPoint> metricPoints = new ArrayList<>(coordsNode.size());
                Coordinate[] jtsCoords = new Coordinate[coordsNode.size()];

                for (int i = 0; i < coordsNode.size(); i++) {
                    JsonNode pt = coordsNode.get(i);
                    double lon = pt.get(0).asDouble();
                    double lat = pt.get(1).asDouble();
                    GeoPoint gp = new GeoPoint(lat, lon);
                    MetricPoint mp = projection.forward(gp);
                    geoPoints.add(gp);
                    metricPoints.add(mp);
                    jtsCoords[i] = new Coordinate(mp.x(), mp.y());
                }
                LineString jtsLine = geometryFactory.createLineString(jtsCoords);

                DrainageChannelLine line = new DrainageChannelLine(
                        id,
                        name,
                        sublayerType,
                        strahlerOrder,
                        watershedId,
                        watershedName,
                        designWidthMeters,
                        peakDischargeM3PerSec,
                        lengthKm,
                        obstructionSensitivity,
                        recommendedStructureType,
                        List.copyOf(geoPoints),
                        jtsLine);
                drainageLines.add(line);

                for (int i = 1; i < metricPoints.size(); i++) {
                    MetricPoint a = metricPoints.get(i - 1);
                    MetricPoint b = metricPoints.get(i);
                    double dx = b.x() - a.x();
                    double dy = b.y() - a.y();
                    double len = Math.hypot(dx, dy);
                    if (len <= EPS) {
                        continue;
                    }
                    IndexedDrainageSegment seg = new IndexedDrainageSegment(
                            id,
                            name,
                            sublayerType,
                            strahlerOrder,
                            watershedId,
                            designWidthMeters,
                            peakDischargeM3PerSec,
                            obstructionSensitivity,
                            recommendedStructureType,
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
                    "Failed to load drainage network GeoJSON: " + drainageNetworkGeoJsonPath, e);
        }
    }

    private void insertWetlandIntoBuckets(WetlandPolygon wp) {
        double reach = 350.0;
        int minBx = bucketCoord(wp.minX() - reach);
        int maxBx = bucketCoord(wp.maxX() + reach);
        int minBy = bucketCoord(wp.minY() - reach);
        int maxBy = bucketCoord(wp.maxY() + reach);
        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                wetlandBuckets
                        .computeIfAbsent(bucketKey(bx, by), k -> new ArrayList<>())
                        .add(wp);
            }
        }
    }

    private void insertSegmentIntoBuckets(IndexedDrainageSegment seg) {
        double reach = 250.0;
        int minBx = bucketCoord(Math.min(seg.ax(), seg.bx()) - reach);
        int maxBx = bucketCoord(Math.max(seg.ax(), seg.bx()) + reach);
        int minBy = bucketCoord(Math.min(seg.ay(), seg.by()) - reach);
        int maxBy = bucketCoord(Math.max(seg.ay(), seg.by()) + reach);
        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                segmentBuckets
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

    public List<WetlandPolygon> allWetlands() {
        ensureInitialized();
        return List.copyOf(wetlands);
    }

    public List<DrainageChannelLine> allDrainageLines() {
        ensureInitialized();
        return List.copyOf(drainageLines);
    }

    public List<WatershedBasinPolygon> allWatersheds() {
        ensureInitialized();
        return List.copyOf(watersheds);
    }

    public WatershedBasinPolygon findWatershedForPoint(GeoPoint point) {
        ensureInitialized();
        double lat = point.lat();
        for (WatershedBasinPolygon ws : watersheds) {
            if (lat >= ws.latMin() && lat <= ws.latMax()) {
                return ws;
            }
        }
        WatershedBasinPolygon closest = watersheds.get(0);
        double bestDist = Double.POSITIVE_INFINITY;
        for (WatershedBasinPolygon ws : watersheds) {
            double d = lat < ws.latMin() ? (ws.latMin() - lat) : (lat - ws.latMax());
            if (d < bestDist) {
                bestDist = d;
                closest = ws;
            }
        }
        return closest;
    }

    /**
     * Fast step-level evaluation for the A* / Dijkstra routing engine.
     * Detects water crossings (seasonal streams, natural drainage channels, irrigation canals,
     * local watercourses), parallel thalweg/canal embankment obstruction risk, 2D wetland polygon
     * encroachment, and watershed divide crossings.
     */
    public StepDrainageAssessment assessStepDrainageAndWetlandImpact(
            GeoPoint from,
            GeoPoint to,
            double rowHalfWidthMeters) {
        ensureInitialized();
        MetricPoint a = projection.forward(from);
        MetricPoint b = projection.forward(to);
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double stepLen = Math.hypot(dx, dy);
        if (stepLen <= EPS) {
            WatershedBasinPolygon ws = findWatershedForPoint(from);
            return new StepDrainageAssessment(
                    List.of(),
                    false,
                    Double.POSITIVE_INFINITY,
                    null,
                    DrainageObstructionRiskTier.LOW_OBSTRUCTION_RISK,
                    false,
                    false,
                    false,
                    Double.POSITIVE_INFINITY,
                    false,
                    false,
                    ws.watershedId(),
                    ws.watershedId());
        }

        double ux = dx / stepLen;
        double uy = dy / stepLen;
        double midX = 0.5 * (a.x() + b.x());
        double midY = 0.5 * (a.y() + b.y());

        int minBx = bucketCoord(Math.min(a.x(), b.x()));
        int maxBx = bucketCoord(Math.max(a.x(), b.x()));
        int minBy = bucketCoord(Math.min(a.y(), b.y()));
        int maxBy = bucketCoord(Math.max(a.y(), b.y()));

        Map<String, DrainageLineCrossing> crossingsById = null;
        double minChannelClearance = Double.POSITIVE_INFINITY;
        DrainageSublayerType nearestChannelSublayer = null;
        boolean parallelEncroachment = false;

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                List<IndexedDrainageSegment> segs = segmentBuckets.get(bucketKey(bx, by));
                if (segs == null) {
                    continue;
                }
                for (IndexedDrainageSegment seg : segs) {
                    // 1. Check exact segment intersection
                    if (Math.max(a.x(), b.x()) >= Math.min(seg.ax(), seg.bx()) - 1e-6
                            && Math.min(a.x(), b.x()) <= Math.max(seg.ax(), seg.bx()) + 1e-6
                            && Math.max(a.y(), b.y()) >= Math.min(seg.ay(), seg.by()) - 1e-6
                            && Math.min(a.y(), b.y()) <= Math.max(seg.ay(), seg.by()) + 1e-6) {
                        double sx = seg.bx() - seg.ax();
                        double sy = seg.by() - seg.ay();
                        double denom = dx * sy - dy * sx;
                        if (Math.abs(denom) > EPS) {
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
                                double skewSpan = seg.designWidthMeters() / Math.max(sinAngle, 0.35);

                                if (crossingsById == null) {
                                    crossingsById = new LinkedHashMap<>(2);
                                }
                                DrainageLineCrossing existing = crossingsById.get(seg.id());
                                if (existing == null || clampedT < existing.stepFraction()) {
                                    MetricPoint crossMetric = new MetricPoint(ix, iy);
                                    crossingsById.put(
                                            seg.id(),
                                            new DrainageLineCrossing(
                                                    seg.id(),
                                                    seg.name(),
                                                    seg.sublayerType(),
                                                    seg.strahlerOrder(),
                                                    seg.watershedId(),
                                                    seg.designWidthMeters(),
                                                    skewSpan,
                                                    seg.peakDischargeM3PerSec(),
                                                    seg.recommendedStructureType(),
                                                    crossMetric,
                                                    projection.inverse(crossMetric),
                                                    clampedT,
                                                    sinAngle,
                                                    angleDeg));
                                }
                            }
                        }
                    }

                    // 2. Check proximity and sub-parallel thalweg/canal embankment damming risk
                    double distMid = pointToSegmentDistance(midX, midY, seg.ax(), seg.ay(), seg.bx(), seg.by());
                    if (distMid < minChannelClearance) {
                        minChannelClearance = distMid;
                        nearestChannelSublayer = seg.sublayerType();
                    }
                    if (distMid <= 95.0) {
                        double sinAngle = Math.min(1.0, Math.abs(ux * seg.ty() - uy * seg.tx()));
                        double angleDeg = Math.toDegrees(Math.asin(sinAngle));
                        if (angleDeg < 34.0
                                || seg.obstructionSensitivity().contains("LONGITUDINAL")
                                || seg.obstructionSensitivity().contains("EMBANKMENT_FLOW_DAMMING")) {
                            parallelEncroachment = true;
                        }
                    }
                }
            }
        }

        // 3. Check 2D wetland polygon distance / buffer encroachment
        boolean inWetlandDirectRow = false;
        boolean inWetland100m = false;
        boolean inWetland250m = false;
        boolean inProtectedBeel = false;
        double minWetlandDist = Double.POSITIVE_INFINITY;

        Coordinate[] stepCoords = new Coordinate[]{
                new Coordinate(a.x(), a.y()),
                new Coordinate(b.x(), b.y())
        };
        LineString stepLine = geometryFactory.createLineString(stepCoords);

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                List<WetlandPolygon> wps = wetlandBuckets.get(bucketKey(bx, by));
                if (wps == null) {
                    continue;
                }
                for (WetlandPolygon wp : wps) {
                    if (Math.max(a.x(), b.x()) < wp.minX() - 260.0
                            || Math.min(a.x(), b.x()) > wp.maxX() + 260.0
                            || Math.max(a.y(), b.y()) < wp.minY() - 260.0
                            || Math.min(a.y(), b.y()) > wp.maxY() + 260.0) {
                        continue;
                    }
                    double d = wp.jtsPolygon().distance(stepLine);
                    if (d < minWetlandDist) {
                        minWetlandDist = d;
                    }
                    if (d <= rowHalfWidthMeters) {
                        inWetlandDirectRow = true;
                        inWetland100m = true;
                        inWetland250m = true;
                        if (wp.isProtectedBeel()) {
                            inProtectedBeel = true;
                        }
                    } else if (d <= 100.0) {
                        inWetland100m = true;
                        inWetland250m = true;
                    } else if (d <= 250.0) {
                        inWetland250m = true;
                    }
                }
            }
        }

        // 4. Check watershed divide crossing
        WatershedBasinPolygon wsFrom = findWatershedForPoint(from);
        WatershedBasinPolygon wsTo = findWatershedForPoint(to);
        boolean crossesWatershedDivide = !wsFrom.watershedId().equals(wsTo.watershedId());

        // 5. Classify step drainage obstruction risk tier
        DrainageObstructionRiskTier riskTier;
        if (inWetlandDirectRow
                || (parallelEncroachment && nearestChannelSublayer == DrainageSublayerType.IRRIGATION_CANAL)) {
            riskTier = DrainageObstructionRiskTier.CRITICAL_WETLAND_OR_CANAL_SEVERANCE_RISK;
        } else if (parallelEncroachment || inWetland100m) {
            riskTier = DrainageObstructionRiskTier.HIGH_THALWEG_EMBANKMENT_DAMMING_RISK;
        } else if (inWetland250m || minChannelClearance <= 85.0) {
            riskTier = DrainageObstructionRiskTier.MODERATE_SHEET_FLOW_PONDING_RISK;
        } else {
            riskTier = DrainageObstructionRiskTier.LOW_OBSTRUCTION_RISK;
        }

        List<DrainageLineCrossing> crossings = crossingsById == null
                ? List.of()
                : List.copyOf(crossingsById.values());

        return new StepDrainageAssessment(
                crossings,
                parallelEncroachment,
                minChannelClearance,
                nearestChannelSublayer,
                riskTier,
                inWetlandDirectRow,
                inWetland100m,
                inWetland250m,
                minWetlandDist,
                inProtectedBeel,
                crossesWatershedDivide,
                wsFrom.watershedId(),
                wsTo.watershedId());
    }

    /**
     * Full 2D corridor evaluation against all 5 sublayers of Layer 13, computing all 5 required
     * MARG engineering calculations:
     * <ol>
     *   <li>Water Crossings</li>
     *   <li>Wetland Intersections</li>
     *   <li>Drainage Obstruction Risks</li>
     *   <li>Watershed Crossings</li>
     *   <li>Potential Drainage Infrastructure Requirements</li>
     * </ol>
     */
    public RouteDrainageWetlandImpact evaluateCorridorDrainageAndWetlandImpact(
            List<GeoPoint> mainline,
            RightOfWayCorridorEngine.CorridorFootprint corridor) {
        ensureInitialized();
        if (mainline == null || mainline.size() < 2) {
            return RouteDrainageWetlandImpact.empty();
        }

        double rowHalfWidthMeters = corridor != null ? corridor.mainlineRowWidthMeters() / 2.0 : 30.0;

        // Densify mainline steps if any segment exceeds 250 m (e.g. simplified collinear baselines)
        // to ensure accurate watershed divide detection, localized thalweg proximity, and water crossing capture.
        List<GeoPoint> evalPath = new ArrayList<>();
        evalPath.add(mainline.get(0));
        for (int i = 1; i < mainline.size(); i++) {
            GeoPoint p0 = mainline.get(i - 1);
            GeoPoint p1 = mainline.get(i);
            MetricPoint m0 = projection.forward(p0);
            MetricPoint m1 = projection.forward(p1);
            double dist = Math.hypot(m1.x() - m0.x(), m1.y() - m0.y());
            if (dist > 250.0) {
                int subSteps = (int) Math.ceil(dist / 250.0);
                for (int s = 1; s <= subSteps; s++) {
                    double frac = (double) s / subSteps;
                    double curLat = p0.lat() + frac * (p1.lat() - p0.lat());
                    double curLon = p0.lon() + frac * (p1.lon() - p0.lon());
                    evalPath.add(new GeoPoint(curLat, curLon));
                }
            } else {
                evalPath.add(p1);
            }
        }

        // 1. Walk mainline steps for water crossings, watershed traversals, and obstruction zones
        List<WaterCrossingEvent> waterCrossings = new ArrayList<>();
        List<DrainageObstructionZone> obstructionZones = new ArrayList<>();
        Map<String, Double> watershedLengthsMeters = new LinkedHashMap<>();

        int seasonalStreamCrossings = 0;
        int naturalDrainageCrossings = 0;
        int irrigationCanalCrossings = 0;
        int localWatercourseCrossings = 0;
        double sumCrossingAngles = 0.0;

        int watershedDivideCrossings = 0;
        double parallelThalwegLengthMeters = 0.0;
        int highOrCriticalObstructionSteps = 0;
        DrainageObstructionRiskTier worstTier = DrainageObstructionRiskTier.LOW_OBSTRUCTION_RISK;

        double cumulativeMeters = 0.0;
        for (int i = 1; i < evalPath.size(); i++) {
            GeoPoint p0 = evalPath.get(i - 1);
            GeoPoint p1 = evalPath.get(i);
            MetricPoint m0 = projection.forward(p0);
            MetricPoint m1 = projection.forward(p1);
            double stepMeters = Math.hypot(m1.x() - m0.x(), m1.y() - m0.y());
            if (stepMeters <= EPS) {
                continue;
            }

            StepDrainageAssessment step = assessStepDrainageAndWetlandImpact(p0, p1, rowHalfWidthMeters);
            watershedLengthsMeters.merge(step.fromWatershedId(), stepMeters, Double::sum);
            if (step.crossesWatershedBoundary()) {
                watershedDivideCrossings++;
            }

            if (step.stepObstructionRiskTier().ordinal() > worstTier.ordinal()) {
                worstTier = step.stepObstructionRiskTier();
            }

            if (step.parallelThalwegOrCanalEncroachment()
                    || step.stepObstructionRiskTier()
                            == DrainageObstructionRiskTier.HIGH_THALWEG_EMBANKMENT_DAMMING_RISK
                    || step.stepObstructionRiskTier()
                            == DrainageObstructionRiskTier.CRITICAL_WETLAND_OR_CANAL_SEVERANCE_RISK) {
                highOrCriticalObstructionSteps++;
                if (step.parallelThalwegOrCanalEncroachment()) {
                    parallelThalwegLengthMeters += stepMeters;
                }
                GeoPoint midGeo = new GeoPoint(
                        0.5 * (p0.lat() + p1.lat()),
                        0.5 * (p0.lon() + p1.lon()));
                obstructionZones.add(new DrainageObstructionZone(
                        "OBST-STEP-" + i,
                        round2((cumulativeMeters + 0.5 * stepMeters) / 1000.0),
                        midGeo,
                        step.fromWatershedId(),
                        step.nearestChannelSublayer() != null
                                ? step.nearestChannelSublayer()
                                : DrainageSublayerType.NATURAL_DRAINAGE_CHANNEL,
                        step.stepObstructionRiskTier(),
                        round2(stepMeters),
                        round2(step.minChannelClearanceMeters()),
                        step.stepObstructionRiskTier().recommendedDrainageMitigation()));
            }

            for (DrainageLineCrossing c : step.crossings()) {
                double chainageKm = round2((cumulativeMeters + c.stepFraction() * stepMeters) / 1000.0);
                switch (c.sublayerType()) {
                    case SEASONAL_STREAM -> seasonalStreamCrossings++;
                    case NATURAL_DRAINAGE_CHANNEL -> naturalDrainageCrossings++;
                    case IRRIGATION_CANAL -> irrigationCanalCrossings++;
                    case LOCAL_WATERCOURSE -> localWatercourseCrossings++;
                    default -> { }
                }
                sumCrossingAngles += c.crossingAngleDegrees();
                waterCrossings.add(new WaterCrossingEvent(
                        c.featureId(),
                        c.featureName(),
                        c.sublayerType(),
                        c.strahlerOrder(),
                        c.watershedId(),
                        chainageKm,
                        c.crossingPoint(),
                        round2(c.crossingAngleDegrees()),
                        round2(c.designWidthMeters()),
                        round2(c.effectiveSkewSpanMeters()),
                        round2(c.peakDischargeM3PerSec()),
                        c.recommendedStructureType()));
            }

            cumulativeMeters += stepMeters;
        }

        int totalWaterCrossings = seasonalStreamCrossings
                + naturalDrainageCrossings
                + irrigationCanalCrossings
                + localWatercourseCrossings;
        double avgCrossingAngle = totalWaterCrossings > 0
                ? round2(sumCrossingAngles / totalWaterCrossings)
                : 90.0;

        // 2. Exact 2D JTS Polygon Intersections against Direct RoW, 100 m, and 250 m Corridor Buffers
        Geometry directRowGeom = corridor != null ? corridor.combinedDirectRowMetric() : null;
        Geometry clearance100mGeom = corridor != null ? corridor.constructionZoneMetric() : null;
        Geometry env250mGeom = corridor != null ? corridor.environmentalBufferMetric() : null;

        if (directRowGeom == null) {
            Coordinate[] lineCoords = new Coordinate[mainline.size()];
            for (int i = 0; i < mainline.size(); i++) {
                MetricPoint mp = projection.forward(mainline.get(i));
                lineCoords[i] = new Coordinate(mp.x(), mp.y());
            }
            LineString ls = geometryFactory.createLineString(lineCoords);
            directRowGeom = ls.buffer(rowHalfWidthMeters);
            clearance100mGeom = ls.buffer(100.0);
            env250mGeom = ls.buffer(250.0);
        }

        int wetlandsDirectRow = 0;
        int wetlands100m = 0;
        int wetlands250m = 0;
        int protectedBeelsDirectRow = 0;
        double wetlandAreaDirectSqM = 0.0;
        double wetlandArea100mSqM = 0.0;
        double wetlandArea250mSqM = 0.0;
        double minWetlandClearanceFromRowEdge = Double.POSITIVE_INFINITY;
        List<WetlandIntersectionSummary> affectedWetlands = new ArrayList<>();

        for (WetlandPolygon wp : wetlands) {
            double distFromRowEdge = directRowGeom.distance(wp.jtsPolygon());
            if (distFromRowEdge < minWetlandClearanceFromRowEdge) {
                minWetlandClearanceFromRowEdge = distFromRowEdge;
            }
            if (!env250mGeom.intersects(wp.jtsPolygon())) {
                continue;
            }

            double area250SqM = env250mGeom.intersection(wp.jtsPolygon()).getArea();
            if (area250SqM <= 1.0) {
                continue;
            }
            wetlands250m++;
            wetlandArea250mSqM += area250SqM;

            double area100SqM = clearance100mGeom.intersects(wp.jtsPolygon())
                    ? clearance100mGeom.intersection(wp.jtsPolygon()).getArea()
                    : 0.0;
            if (area100SqM > 1.0) {
                wetlands100m++;
                wetlandArea100mSqM += area100SqM;
            }

            double areaDirectSqM = directRowGeom.intersects(wp.jtsPolygon())
                    ? directRowGeom.intersection(wp.jtsPolygon()).getArea()
                    : 0.0;
            if (areaDirectSqM > 1.0) {
                wetlandsDirectRow++;
                wetlandAreaDirectSqM += areaDirectSqM;
                if (wp.isProtectedBeel()) {
                    protectedBeelsDirectRow++;
                }
            }

            double totalPolySqM = Math.max(1.0, wp.jtsPolygon().getArea());
            double pctDirect = Math.min(100.0, (areaDirectSqM / totalPolySqM) * 100.0);
            String zoneTier = areaDirectSqM > 1.0
                    ? "DIRECT_ROW_ENCROACHMENT"
                    : (area100SqM > 1.0 ? "CONSTRUCTION_100M_BUFFER" : "ENVIRONMENTAL_250M_BUFFER");

            affectedWetlands.add(new WetlandIntersectionSummary(
                    wp.id(),
                    wp.name(),
                    wp.wetlandCategory(),
                    wp.district(),
                    wp.watershedId(),
                    wp.isProtectedBeel(),
                    zoneTier,
                    round2(wp.areaHectares()),
                    round2(areaDirectSqM),
                    round2(areaDirectSqM / SQ_METERS_PER_HECTARE),
                    round2(area100SqM / SQ_METERS_PER_HECTARE),
                    round2(area250SqM / SQ_METERS_PER_HECTARE),
                    round2(pctDirect),
                    round2(distFromRowEdge),
                    wp.recommendedMitigation()));
        }

        affectedWetlands.sort(Comparator
                .comparingDouble(WetlandIntersectionSummary::directRowAreaSqMeters).reversed()
                .thenComparingDouble(WetlandIntersectionSummary::buffer100mAreaHectares).reversed());

        // 3. Build Watershed Traversal Summary
        Map<String, WatershedBasinPolygon> wsById = new HashMap<>();
        for (WatershedBasinPolygon ws : watersheds) {
            wsById.put(ws.watershedId(), ws);
        }
        List<WatershedTraversalSummary> watershedTraversals = new ArrayList<>();
        for (Map.Entry<String, Double> entry : watershedLengthsMeters.entrySet()) {
            WatershedBasinPolygon ws = wsById.get(entry.getKey());
            if (ws == null) {
                continue;
            }
            double lenKm = round2(entry.getValue() / 1000.0);
            watershedTraversals.add(new WatershedTraversalSummary(
                    ws.watershedId(),
                    ws.watershedCode(),
                    ws.name(),
                    ws.majorRiverSystem(),
                    lenKm,
                    ws.runoffCoefficient(),
                    ws.timeOfConcentrationMinutes(),
                    ws.drainageDensityKmPerSqKm(),
                    ws.floodAndBackwaterSensitivity()));
        }

        // 4. Synthesize Potential Drainage Infrastructure Requirements (IRC:SP:13 & IRC:SP:42)
        List<DrainageInfrastructureRequirement> infrastructureList = new ArrayList<>();
        int pipeCulverts = 0;
        int boxCulverts = 0;
        int multiCellOrMinorBridges = 0;
        int canalSyphonAqueducts = 0;
        int wetlandViaducts = 0;
        double totalDrainageSpanMeters = 0.0;
        double totalPeakRunoffM3s = 0.0;

        int structSeq = 1;
        for (WaterCrossingEvent wc : waterCrossings) {
            String structType = wc.recommendedStructureType();
            switch (structType) {
                case "PIPE_CULVERT_IRC_SP13" -> pipeCulverts++;
                case "SLAB_OR_SINGLE_BOX_CULVERT" -> boxCulverts++;
                case "MULTI_CELL_BOX_CULVERT_OR_MINOR_BRIDGE" -> multiCellOrMinorBridges++;
                case "CANAL_SYPHON_AQUEDUCT_OR_CLEAR_SPAN_BRIDGE" -> canalSyphonAqueducts++;
                default -> boxCulverts++;
            }
            totalDrainageSpanMeters += wc.effectiveSkewSpanMeters();
            totalPeakRunoffM3s += wc.peakDischargeM3PerSec();

            infrastructureList.add(new DrainageInfrastructureRequirement(
                    String.format("DRN-INFRA-%03d", structSeq++),
                    wc.featureId(),
                    wc.featureName(),
                    wc.sublayerType(),
                    structType,
                    wc.chainageKm(),
                    wc.crossingPoint(),
                    wc.effectiveSkewSpanMeters(),
                    wc.peakDischargeM3PerSec(),
                    "IRC:SP:13 / IRC:SP:42 Cross-Drainage Structure (" + wc.watershedId() + ")"));
        }

        for (WetlandIntersectionSummary wi : affectedWetlands) {
            if (wi.directRowAreaSqMeters() > 1.0 || wi.buffer100mAreaHectares() >= 0.25) {
                wetlandViaducts++;
                double eqSpan = Math.max(30.0, round2(Math.sqrt(Math.max(900.0, wi.directRowAreaSqMeters())) * 1.35));
                double estFlow = round2(18.0 + wi.buffer100mAreaHectares() * 6.5);
                totalDrainageSpanMeters += eqSpan;
                totalPeakRunoffM3s += estFlow;
                WetlandPolygon wp = wetlands.stream()
                        .filter(w -> w.id().equals(wi.wetlandId()))
                        .findFirst()
                        .orElse(null);
                GeoPoint loc = wp != null ? wp.centroidGeo() : mainline.get(mainline.size() / 2);

                infrastructureList.add(new DrainageInfrastructureRequirement(
                        String.format("DRN-INFRA-%03d", structSeq++),
                        wi.wetlandId(),
                        wi.wetlandName(),
                        DrainageSublayerType.WETLAND,
                        "WETLAND_PILE_VIADUCT_OR_EQUALIZER_CAUSEWAY",
                        0.0,
                        loc,
                        eqSpan,
                        estFlow,
                        "Unobstructed Sheet-Flow Equalizer Culverts / Pile Viaduct across Wetland Zone"));
            }
        }

        int totalStructures = pipeCulverts
                + boxCulverts
                + multiCellOrMinorBridges
                + canalSyphonAqueducts
                + wetlandViaducts;

        double obstructionIndex = round2(
                wetlandsDirectRow * 18.0
                        + protectedBeelsDirectRow * 12.0
                        + (wetlandAreaDirectSqM / SQ_METERS_PER_HECTARE) * 8.5
                        + (parallelThalwegLengthMeters / 1000.0) * 14.0
                        + highOrCriticalObstructionSteps * 3.5
                        + irrigationCanalCrossings * 4.0);

        return new RouteDrainageWetlandImpact(
                seasonalStreamCrossings,
                naturalDrainageCrossings,
                irrigationCanalCrossings,
                localWatercourseCrossings,
                totalWaterCrossings,
                avgCrossingAngle,
                List.copyOf(waterCrossings),
                wetlandsDirectRow,
                wetlands100m,
                wetlands250m,
                protectedBeelsDirectRow,
                round2(wetlandAreaDirectSqM),
                round2(wetlandAreaDirectSqM / SQ_METERS_PER_HECTARE),
                round2(wetlandArea100mSqM / SQ_METERS_PER_HECTARE),
                round2(wetlandArea250mSqM / SQ_METERS_PER_HECTARE),
                Double.isInfinite(minWetlandClearanceFromRowEdge)
                        ? 9999.0
                        : round2(minWetlandClearanceFromRowEdge),
                List.copyOf(affectedWetlands),
                obstructionZones.size(),
                highOrCriticalObstructionSteps,
                round2(parallelThalwegLengthMeters),
                round2(parallelThalwegLengthMeters / 1000.0),
                obstructionIndex,
                worstTier,
                List.copyOf(obstructionZones),
                watershedDivideCrossings,
                watershedTraversals.size(),
                List.copyOf(watershedTraversals),
                pipeCulverts,
                boxCulverts,
                multiCellOrMinorBridges,
                canalSyphonAqueducts,
                wetlandViaducts,
                totalStructures,
                round2(totalDrainageSpanMeters),
                round2(totalPeakRunoffM3s),
                List.copyOf(infrastructureList));
    }

    /**
     * Compares two candidate alignments on hydrological disruption, wetland encroachment,
     * drainage obstruction risk, and cross-drainage infrastructure requirements.
     */
    public CandidateDrainageComparison compareCandidateAlignments(
            String candidateAName,
            RouteDrainageWetlandImpact impactA,
            String candidateBName,
            RouteDrainageWetlandImpact impactB) {
        int wetlandDirectDelta = impactB.wetlandsInDirectRowCount() - impactA.wetlandsInDirectRowCount();
        double wetlandHaDelta = round2(
                impactB.wetlandAreaInDirectRowHectares() - impactA.wetlandAreaInDirectRowHectares());
        int obstructionZonesDelta =
                impactB.highOrCriticalObstructionZonesCount() - impactA.highOrCriticalObstructionZonesCount();
        double parallelThalwegKmDelta = round2(
                impactB.parallelThalwegEncroachmentLengthKm() - impactA.parallelThalwegEncroachmentLengthKm());
        double obstructionIndexDelta = round2(
                impactB.drainageObstructionRiskIndex() - impactA.drainageObstructionRiskIndex());
        double spanDeltaMeters = round2(
                impactB.totalRecommendedDrainageSpanMeters() - impactA.totalRecommendedDrainageSpanMeters());

        String preferred = impactB.drainageObstructionRiskIndex() <= impactA.drainageObstructionRiskIndex()
                ? candidateBName
                : candidateAName;

        String engineeringVerdict = String.format(
                "%s achieves lower hydrological & wetland disruption than %s "
                        + "(Direct RoW wetlands: %d vs %d [%.2f ha vs %.2f ha], "
                        + "parallel thalweg damming exposure: %.2f km vs %.2f km, "
                        + "drainage obstruction risk index: %.2f vs %.2f).",
                preferred,
                preferred.equals(candidateBName) ? candidateAName : candidateBName,
                impactB.wetlandsInDirectRowCount(),
                impactA.wetlandsInDirectRowCount(),
                impactB.wetlandAreaInDirectRowHectares(),
                impactA.wetlandAreaInDirectRowHectares(),
                impactB.parallelThalwegEncroachmentLengthKm(),
                impactA.parallelThalwegEncroachmentLengthKm(),
                impactB.drainageObstructionRiskIndex(),
                impactA.drainageObstructionRiskIndex());

        return new CandidateDrainageComparison(
                candidateAName,
                candidateBName,
                preferred,
                wetlandDirectDelta,
                wetlandHaDelta,
                obstructionZonesDelta,
                parallelThalwegKmDelta,
                obstructionIndexDelta,
                spanDeltaMeters,
                engineeringVerdict);
    }

    private static double pointToSegmentDistance(
            double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq <= EPS) {
            return Math.hypot(px - ax, py - ay);
        }
        double t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double projX = ax + t * dx;
        double projY = ay + t * dy;
        return Math.hypot(px - projX, py - projY);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // --- Records ---

    public record WetlandPolygon(
            String id,
            String name,
            String wetlandCategory,
            String district,
            String watershedId,
            String watershedName,
            double areaHectares,
            double equivalentRadiusMeters,
            double seasonalDepthMeters,
            String ecologicalAndHydrologicalSensitivity,
            boolean isProtectedBeel,
            String recommendedMitigation,
            GeoPoint centroidGeo,
            MetricPoint centroidMetric,
            List<GeoPoint> boundaryRing,
            Polygon jtsPolygon,
            double minX,
            double minY,
            double maxX,
            double maxY) {
    }

    public record DrainageChannelLine(
            String id,
            String name,
            DrainageSublayerType sublayerType,
            int strahlerOrder,
            String watershedId,
            String watershedName,
            double designWidthMeters,
            double peakDischargeM3PerSec,
            double lengthKm,
            String obstructionSensitivity,
            String recommendedStructureType,
            List<GeoPoint> coordinates,
            LineString jtsLine) {
    }

    public record WatershedBasinPolygon(
            String watershedId,
            String watershedCode,
            String name,
            String majorRiverSystem,
            double latMin,
            double latMax,
            double areaSqKm,
            double runoffCoefficient,
            double timeOfConcentrationMinutes,
            double meanSlopeDegrees,
            double drainageDensityKmPerSqKm,
            String floodAndBackwaterSensitivity,
            Polygon jtsPolygon) {
    }

    private record IndexedDrainageSegment(
            String id,
            String name,
            DrainageSublayerType sublayerType,
            int strahlerOrder,
            String watershedId,
            double designWidthMeters,
            double peakDischargeM3PerSec,
            String obstructionSensitivity,
            String recommendedStructureType,
            double ax,
            double ay,
            double bx,
            double by,
            double tx,
            double ty) {
    }

    public record DrainageLineCrossing(
            String featureId,
            String featureName,
            DrainageSublayerType sublayerType,
            int strahlerOrder,
            String watershedId,
            double designWidthMeters,
            double effectiveSkewSpanMeters,
            double peakDischargeM3PerSec,
            String recommendedStructureType,
            MetricPoint metricCrossingPoint,
            GeoPoint crossingPoint,
            double stepFraction,
            double sinApproachAngle,
            double crossingAngleDegrees) {
    }

    public record StepDrainageAssessment(
            List<DrainageLineCrossing> crossings,
            boolean parallelThalwegOrCanalEncroachment,
            double minChannelClearanceMeters,
            DrainageSublayerType nearestChannelSublayer,
            DrainageObstructionRiskTier stepObstructionRiskTier,
            boolean intersectsWetlandDirectRow,
            boolean intersectsWetland100mBuffer,
            boolean intersectsWetland250mBuffer,
            double minWetlandDistanceMeters,
            boolean intersectsProtectedBeel,
            boolean crossesWatershedBoundary,
            String fromWatershedId,
            String toWatershedId) {
    }

    public record WaterCrossingEvent(
            String featureId,
            String featureName,
            DrainageSublayerType sublayerType,
            int strahlerOrder,
            String watershedId,
            double chainageKm,
            GeoPoint crossingPoint,
            double crossingAngleDegrees,
            double designChannelWidthMeters,
            double effectiveSkewSpanMeters,
            double peakDischargeM3PerSec,
            String recommendedStructureType) {
    }

    public record WetlandIntersectionSummary(
            String wetlandId,
            String wetlandName,
            String wetlandCategory,
            String district,
            String watershedId,
            boolean isProtectedBeel,
            String highestExposureZone,
            double totalWetlandAreaHectares,
            double directRowAreaSqMeters,
            double directRowAreaHectares,
            double buffer100mAreaHectares,
            double buffer250mAreaHectares,
            double directRowEncroachmentPercent,
            double clearanceFromDirectRowEdgeMeters,
            String recommendedMitigation) {
    }

    public record DrainageObstructionZone(
            String zoneId,
            double chainageKm,
            GeoPoint location,
            String watershedId,
            DrainageSublayerType sublayerType,
            DrainageObstructionRiskTier obstructionRiskTier,
            double affectedStepLengthMeters,
            double clearanceToThalwegMeters,
            String recommendedMitigation) {
    }

    public record WatershedTraversalSummary(
            String watershedId,
            String watershedCode,
            String watershedName,
            String majorRiverSystem,
            double traversedLengthKm,
            double runoffCoefficient,
            double timeOfConcentrationMinutes,
            double drainageDensityKmPerSqKm,
            String floodAndBackwaterSensitivity) {
    }

    public record DrainageInfrastructureRequirement(
            String requirementId,
            String targetFeatureId,
            String targetFeatureName,
            DrainageSublayerType sublayerType,
            String structureType,
            double chainageKm,
            GeoPoint location,
            double recommendedClearSpanOrWidthMeters,
            double designDischargeM3PerSec,
            String engineeringSpecification) {
    }

    public record RouteDrainageWetlandImpact(
            int seasonalStreamCrossingsCount,
            int naturalDrainageChannelCrossingsCount,
            int irrigationCanalCrossingsCount,
            int localWatercourseCrossingsCount,
            int totalWaterCrossingsCount,
            double averageWaterCrossingAngleDegrees,
            List<WaterCrossingEvent> waterCrossings,
            int wetlandsInDirectRowCount,
            int wetlandsIn100mBufferCount,
            int wetlandsIn250mBufferCount,
            int protectedBeelsInDirectRowCount,
            double wetlandAreaInDirectRowSqMeters,
            double wetlandAreaInDirectRowHectares,
            double wetlandAreaIn100mBufferHectares,
            double wetlandAreaIn250mBufferHectares,
            double minWetlandClearanceFromRowEdgeMeters,
            List<WetlandIntersectionSummary> affectedWetlands,
            int drainageObstructionRisksCount,
            int highOrCriticalObstructionZonesCount,
            double parallelThalwegEncroachmentLengthMeters,
            double parallelThalwegEncroachmentLengthKm,
            double drainageObstructionRiskIndex,
            DrainageObstructionRiskTier worstObstructionRiskTier,
            List<DrainageObstructionZone> obstructionRiskZones,
            int watershedBoundaryCrossingsCount,
            int watershedsTraversedCount,
            List<WatershedTraversalSummary> watershedTraversals,
            int pipeCulvertsRequiredCount,
            int boxCulvertsRequiredCount,
            int multiCellCulvertsOrMinorBridgesCount,
            int canalSyphonAqueductsCount,
            int wetlandViaductOrCausewaysCount,
            int totalDrainageStructuresRequiredCount,
            double totalRecommendedDrainageSpanMeters,
            double totalEstimatedPeakRunoffM3PerSec,
            List<DrainageInfrastructureRequirement> drainageInfrastructureRequirements) {

        public static RouteDrainageWetlandImpact empty() {
            return new RouteDrainageWetlandImpact(
                    0, 0, 0, 0, 0, 90.0, List.of(),
                    0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 9999.0, List.of(),
                    0, 0, 0.0, 0.0, 0.0, DrainageObstructionRiskTier.LOW_OBSTRUCTION_RISK, List.of(),
                    0, 0, List.of(),
                    0, 0, 0, 0, 0, 0, 0.0, 0.0, List.of());
        }

        public Map<String, Object> toSummaryMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("waterCrossingsSummary", Map.of(
                    "seasonalStreamCrossingsCount", seasonalStreamCrossingsCount,
                    "naturalDrainageChannelCrossingsCount", naturalDrainageChannelCrossingsCount,
                    "irrigationCanalCrossingsCount", irrigationCanalCrossingsCount,
                    "localWatercourseCrossingsCount", localWatercourseCrossingsCount,
                    "totalWaterCrossingsCount", totalWaterCrossingsCount,
                    "averageWaterCrossingAngleDegrees", averageWaterCrossingAngleDegrees,
                    "waterCrossings", waterCrossings));
            map.put("wetlandIntersectionsSummary", Map.of(
                    "wetlandsInDirectRowCount", wetlandsInDirectRowCount,
                    "wetlandsIn100mBufferCount", wetlandsIn100mBufferCount,
                    "wetlandsIn250mBufferCount", wetlandsIn250mBufferCount,
                    "protectedBeelsInDirectRowCount", protectedBeelsInDirectRowCount,
                    "wetlandAreaInDirectRowSqMeters", wetlandAreaInDirectRowSqMeters,
                    "wetlandAreaInDirectRowHectares", wetlandAreaInDirectRowHectares,
                    "wetlandAreaIn100mBufferHectares", wetlandAreaIn100mBufferHectares,
                    "wetlandAreaIn250mBufferHectares", wetlandAreaIn250mBufferHectares,
                    "minWetlandClearanceFromRowEdgeMeters", minWetlandClearanceFromRowEdgeMeters,
                    "affectedWetlands", affectedWetlands));
            map.put("drainageObstructionRisksSummary", Map.of(
                    "drainageObstructionRisksCount", drainageObstructionRisksCount,
                    "highOrCriticalObstructionZonesCount", highOrCriticalObstructionZonesCount,
                    "parallelThalwegEncroachmentLengthMeters", parallelThalwegEncroachmentLengthMeters,
                    "parallelThalwegEncroachmentLengthKm", parallelThalwegEncroachmentLengthKm,
                    "drainageObstructionRiskIndex", drainageObstructionRiskIndex,
                    "worstObstructionRiskTier", worstObstructionRiskTier.name(),
                    "obstructionRiskZones", obstructionRiskZones));
            map.put("watershedCrossingsSummary", Map.of(
                    "watershedBoundaryCrossingsCount", watershedBoundaryCrossingsCount,
                    "watershedsTraversedCount", watershedsTraversedCount,
                    "watershedTraversals", watershedTraversals));
            map.put("potentialDrainageInfrastructureRequirements", Map.of(
                    "pipeCulvertsRequiredCount", pipeCulvertsRequiredCount,
                    "boxCulvertsRequiredCount", boxCulvertsRequiredCount,
                    "multiCellCulvertsOrMinorBridgesCount", multiCellCulvertsOrMinorBridgesCount,
                    "canalSyphonAqueductsCount", canalSyphonAqueductsCount,
                    "wetlandViaductOrCausewaysCount", wetlandViaductOrCausewaysCount,
                    "totalDrainageStructuresRequiredCount", totalDrainageStructuresRequiredCount,
                    "totalRecommendedDrainageSpanMeters", totalRecommendedDrainageSpanMeters,
                    "totalEstimatedPeakRunoffM3PerSec", totalEstimatedPeakRunoffM3PerSec,
                    "drainageInfrastructureRequirements", drainageInfrastructureRequirements));
            return map;
        }
    }

    public record CandidateDrainageComparison(
            String candidateAName,
            String candidateBName,
            String preferredAlignment,
            int wetlandDirectRowDelta,
            double wetlandDirectRowHectaresDelta,
            int highObstructionZonesDelta,
            double parallelThalwegLengthKmDelta,
            double drainageObstructionRiskIndexDelta,
            double totalDrainageSpanMetersDelta,
            String engineeringComparisonVerdict) {
    }
}
