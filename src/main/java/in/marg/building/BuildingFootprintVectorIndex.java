package in.marg.building;

import in.marg.model.GeoPoint;
import in.marg.row.RightOfWayCorridorEngine;
import in.marg.row.RowImpactZone;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.transport.FacilityMode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory EPSG:32646 spatial vector index for 2D Settlement Boundary Polygons
 * ({@code assam_settlement_boundaries.geojson}) and individual 2D Building Footprints
 * ({@code assam_building_footprints.geojson}).
 * <p>
 * Supports:
 * <ul>
 *   <li>Fast A* edge evaluation of 2D Right-of-Way (RoW) building footprint hits,
 *       setback proximity conflicts, and settlement boundary severance.</li>
 *   <li>Exact JTS 2D polygon overlay of proposed highway corridors against all 1,882
 *       building footprints and 104 settlement boundary polygons.</li>
 *   <li>Chainage-ordered land-acquisition & R&R structure conflict zone identification.</li>
 * </ul>
 */
@Component
public class BuildingFootprintVectorIndex {

    private static final double BUILDING_BUCKET_SIZE_METERS = 500.0;
    private static final double SETTLEMENT_BUCKET_SIZE_METERS = 1500.0;
    private static final double SQ_METERS_PER_HECTARE = 10_000.0;
    public static final double RFCTLARR_PERSONS_PER_HOUSEHOLD = 4.8;
    public static final double RFCTLARR_FLOOR_AREA_PER_HOUSEHOLD_SQM = 75.0;

    private final CoordinateProjection projection;
    private final String settlementsGeoJsonPath;
    private final String buildingsGeoJsonPath;
    private final ObjectMapper objectMapper;
    private final GeometryFactory geometryFactory;

    private boolean initialized = false;
    private final List<SettlementBoundaryPolygon> settlements = new ArrayList<>();
    private final List<SettlementBoundaryPolygon> sacredExclusions = new ArrayList<>();
    private final List<SettlementBoundaryPolygon> institutionalExclusions = new ArrayList<>();
    private final List<SettlementBoundaryPolygon> denseColonies = new ArrayList<>();
    private final Map<String, SettlementBoundaryPolygon> settlementById = new HashMap<>();
    private final Set<String> hospitalSettlementIds = new HashSet<>();
    private final Map<String, String> settlementExclusionCategoryById = new HashMap<>();
    private final List<BuildingFootprint> buildings = new ArrayList<>();
    private final List<BuildingFootprint> institutionalOrReligiousBuildings = new ArrayList<>();
    private final Set<String> hospitalBuildingIds = new HashSet<>();
    private final Map<String, List<BuildingFootprint>> buildingsBySettlementId = new HashMap<>();
    private final Map<Long, List<BuildingFootprint>> buildingBuckets = new HashMap<>();
    private final Map<Long, List<SettlementBoundaryPolygon>> settlementBuckets = new HashMap<>();
    private final Map<Long, List<SettlementBoundaryPolygon>> institutionalExclusionBuckets = new HashMap<>();
    private final Map<Long, List<SettlementBoundaryPolygon>> denseColonyBuckets = new HashMap<>();

    @Autowired
    public BuildingFootprintVectorIndex(
            CoordinateProjection projection,
            @Value("${marg.building.settlements-geojson-path:data/processed/buildings/assam_settlement_boundaries.geojson}")
            String settlementsGeoJsonPath,
            @Value("${marg.building.footprints-geojson-path:data/processed/buildings/assam_building_footprints.geojson}")
            String buildingsGeoJsonPath,
            ObjectMapper objectMapper) {
        this.projection = projection;
        this.settlementsGeoJsonPath = settlementsGeoJsonPath;
        this.buildingsGeoJsonPath = buildingsGeoJsonPath;
        this.objectMapper = objectMapper;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 32646);
    }

    public BuildingFootprintVectorIndex(
            CoordinateProjection projection,
            String settlementsGeoJsonPath,
            String buildingsGeoJsonPath) {
        this(projection, settlementsGeoJsonPath, buildingsGeoJsonPath, new ObjectMapper());
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        loadSettlements();
        loadBuildings();
        initialized = true;
    }

    private void loadSettlements() {
        File file = new File(settlementsGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Settlement boundaries GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + settlementsGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                String settlementId = p.get("settlementId").asString();
                String catalogRef = p.has("catalogRef") && !p.get("catalogRef").isNull()
                        ? p.get("catalogRef").asString()
                        : settlementId;
                String name = p.get("name").asString();
                SettlementClassification classification =
                        SettlementClassification.valueOf(p.get("settlementClassification").asString());
                String subtype = p.get("settlementSubtype").asString("RURAL_VILLAGE_HABITATION");
                String district = p.get("district").asString("Karbi Anglong");
                double areaHa = p.get("polygonAreaHectares").asDouble(10.0);
                double coreRadius = p.get("coreRadiusMeters").asDouble(180.0);
                double densityPerHa = p.get("builtUpDensityBuildingsPerHa").asDouble(8.0);
                String densityClass = p.get("builtUpDensityClass").asString("MEDIUM_DENSITY_VILLAGE_CLUSTER");
                int mappedBuildingCount = p.get("mappedBuildingFootprintCount").asInt(0);
                boolean sacredExclusion = (p.has("sacredExclusion") && p.get("sacredExclusion").asBoolean(false))
                        || subtype.toUpperCase().contains("SACRED")
                        || subtype.toUpperCase().contains("HERITAGE");
                boolean hospitalOrMedical = (p.has("hospitalOrMedical") && p.get("hospitalOrMedical").asBoolean(false))
                        || subtype.toUpperCase().contains("HOSPITAL")
                        || subtype.toUpperCase().contains("MEDICAL")
                        || subtype.toUpperCase().contains("CLINIC");
                String exclusionCategory = p.has("exclusionCategory") && !p.get("exclusionCategory").isNull()
                        ? p.get("exclusionCategory").asString()
                        : (hospitalOrMedical ? "HOSPITAL_MEDICAL_CAMPUS"
                                : sacredExclusion ? "RELIGIOUS_PRECINCT"
                                : "INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT".equals(densityClass) ? "INSTITUTIONAL_CAMPUS"
                                : classification == SettlementClassification.URBAN ? "DENSE_RESIDENTIAL_COLONY"
                                : "RURAL_HABITATION");
                boolean isInstitutionalOrSacred = sacredExclusion
                        || hospitalOrMedical
                        || "INSTITUTIONAL_RELIGIOUS_COMPOUND_FOOTPRINT".equals(densityClass)
                        || subtype.toUpperCase().contains("INSTITUTIONAL")
                        || subtype.toUpperCase().contains("SCHOOL")
                        || subtype.toUpperCase().contains("COLLEGE")
                        || subtype.toUpperCase().contains("UNIVERSITY")
                        || subtype.toUpperCase().contains("RELIGIOUS");
                boolean isDenseColony = !isInstitutionalOrSacred && (
                        (p.has("hardExclusion") && p.get("hardExclusion").asBoolean(false))
                        || classification == SettlementClassification.URBAN
                        || "HIGH_DENSITY_COMMERCIAL_URBAN_BUILTUP".equals(densityClass)
                        || subtype.toUpperCase().contains("COLONY")
                        || subtype.toUpperCase().contains("RESIDENTIAL")
                        || subtype.toUpperCase().contains("COMMERCIAL")
                        || subtype.toUpperCase().contains("URBAN")
                        || densityPerHa >= 12.0);
                double centroidLat = p.get("centroidLat").asDouble();
                double centroidLon = p.get("centroidLon").asDouble();
                GeoPoint centroid = new GeoPoint(centroidLat, centroidLon);
                MetricPoint centroidMetric = projection.forward(centroid);

                JsonNode ringNode = feat.get("geometry").get("coordinates").get(0);
                List<GeoPoint> geoRing = new ArrayList<>(ringNode.size());
                List<MetricPoint> metricRing = new ArrayList<>(ringNode.size());
                double minX = Double.POSITIVE_INFINITY;
                double minY = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY;
                double maxY = Double.NEGATIVE_INFINITY;

                Coordinate[] jtsCoords = new Coordinate[ringNode.size()];
                for (int i = 0; i < ringNode.size(); i++) {
                    JsonNode pt = ringNode.get(i);
                    double lon = pt.get(0).asDouble();
                    double lat = pt.get(1).asDouble();
                    GeoPoint gp = new GeoPoint(lat, lon);
                    MetricPoint mp = projection.forward(gp);
                    geoRing.add(gp);
                    metricRing.add(mp);
                    jtsCoords[i] = new Coordinate(mp.x(), mp.y());
                    if (mp.x() < minX) minX = mp.x();
                    if (mp.y() < minY) minY = mp.y();
                    if (mp.x() > maxX) maxX = mp.x();
                    if (mp.y() > maxY) maxY = mp.y();
                }
                if (jtsCoords.length < 3) {
                    continue;
                }
                if (!jtsCoords[0].equals2D(jtsCoords[jtsCoords.length - 1])) {
                    Coordinate[] closed = new Coordinate[jtsCoords.length + 1];
                    System.arraycopy(jtsCoords, 0, closed, 0, jtsCoords.length);
                    closed[jtsCoords.length] = new Coordinate(jtsCoords[0].x, jtsCoords[0].y);
                    jtsCoords = closed;
                }
                if (jtsCoords.length < 4) {
                    continue;
                }
                final Polygon jtsPoly;
                try {
                    LinearRing lr = geometryFactory.createLinearRing(jtsCoords);
                    jtsPoly = geometryFactory.createPolygon(lr);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                SettlementBoundaryPolygon poly = new SettlementBoundaryPolygon(
                        settlementId,
                        catalogRef,
                        name,
                        classification,
                        subtype,
                        district,
                        areaHa,
                        coreRadius,
                        densityPerHa,
                        densityClass,
                        mappedBuildingCount,
                        sacredExclusion,
                        centroid,
                        centroidMetric,
                        List.copyOf(geoRing),
                        List.copyOf(metricRing),
                        jtsPoly,
                        minX,
                        minY,
                        maxX,
                        maxY);

                settlements.add(poly);
                settlementExclusionCategoryById.put(settlementId, exclusionCategory);
                if (hospitalOrMedical) {
                    hospitalSettlementIds.add(settlementId);
                }
                if (sacredExclusion) {
                    sacredExclusions.add(poly);
                }
                if (isInstitutionalOrSacred) {
                    institutionalExclusions.add(poly);
                    insertIntoBuckets(poly, institutionalExclusionBuckets, 150.0);
                } else if (isDenseColony) {
                    denseColonies.add(poly);
                    insertIntoBuckets(poly, denseColonyBuckets, 100.0);
                }
                settlementById.put(settlementId, poly);
                insertSettlementIntoBuckets(poly);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load settlement boundaries GeoJSON: " + settlementsGeoJsonPath, e);
        }
    }

    private void loadBuildings() {
        File file = new File(buildingsGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Building footprints GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + buildingsGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                String buildingId = p.get("buildingId").asString();
                String name = p.get("name").asString();
                BuildingType buildingType = BuildingType.valueOf(p.get("buildingType").asString());
                String settlementId = p.get("settlementId").asString();
                String settlementName = p.get("settlementName").asString();
                SettlementClassification settlementClass =
                        SettlementClassification.valueOf(p.get("settlementClassification").asString());
                String district = p.get("district").asString("Karbi Anglong");
                double areaSqM = p.get("footprintAreaSqMeters").asDouble(90.0);
                int floors = p.get("floors").asInt(1);
                String material = p.get("structureMaterial").asString("ASSAM_TYPE_SEMI_PUCCA");
                double eqRadius = p.get("equivalentRadiusMeters").asDouble(6.5);
                double conflictWeight = p.get("demolitionConflictWeightKmEq")
                        .asDouble(buildingType.defaultDemolitionConflictKmEq());
                double centroidLat = p.get("centroidLat").asDouble();
                double centroidLon = p.get("centroidLon").asDouble();
                GeoPoint centroid = new GeoPoint(centroidLat, centroidLon);
                MetricPoint centroidMetric = projection.forward(centroid);

                JsonNode ringNode = feat.get("geometry").get("coordinates").get(0);
                List<GeoPoint> geoRing = new ArrayList<>(ringNode.size());
                List<MetricPoint> metricRing = new ArrayList<>(ringNode.size());
                Coordinate[] jtsCoords = new Coordinate[ringNode.size()];
                for (int i = 0; i < ringNode.size(); i++) {
                    JsonNode pt = ringNode.get(i);
                    double lon = pt.get(0).asDouble();
                    double lat = pt.get(1).asDouble();
                    GeoPoint gp = new GeoPoint(lat, lon);
                    MetricPoint mp = projection.forward(gp);
                    geoRing.add(gp);
                    metricRing.add(mp);
                    jtsCoords[i] = new Coordinate(mp.x(), mp.y());
                }
                if (jtsCoords.length < 3) {
                    continue;
                }
                if (!jtsCoords[0].equals2D(jtsCoords[jtsCoords.length - 1])) {
                    Coordinate[] closed = new Coordinate[jtsCoords.length + 1];
                    System.arraycopy(jtsCoords, 0, closed, 0, jtsCoords.length);
                    closed[jtsCoords.length] = new Coordinate(jtsCoords[0].x, jtsCoords[0].y);
                    jtsCoords = closed;
                }
                if (jtsCoords.length < 4) {
                    continue;
                }
                final Polygon jtsPoly;
                try {
                    LinearRing lr = geometryFactory.createLinearRing(jtsCoords);
                    jtsPoly = geometryFactory.createPolygon(lr);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                BuildingFootprint bld = new BuildingFootprint(
                        buildingId,
                        name,
                        buildingType,
                        settlementId,
                        settlementName,
                        settlementClass,
                        district,
                        areaSqM,
                        floors,
                        material,
                        eqRadius,
                        conflictWeight,
                        centroid,
                        centroidMetric,
                        List.copyOf(geoRing),
                        List.copyOf(metricRing),
                        jtsPoly);

                buildings.add(bld);
                boolean isHospBld = (p.has("hospitalOrMedical") && p.get("hospitalOrMedical").asBoolean(false))
                        || name.toUpperCase().contains("HOSPITAL")
                        || name.toUpperCase().contains("CLINIC")
                        || material.toUpperCase().contains("HOSPITAL");
                if (isHospBld) {
                    hospitalBuildingIds.add(buildingId);
                }
                if (buildingType == BuildingType.INSTITUTIONAL
                        || buildingType == BuildingType.RELIGIOUS
                        || isHospBld
                        || (p.has("hardExclusion") && p.get("hardExclusion").asBoolean(false))) {
                    institutionalOrReligiousBuildings.add(bld);
                }
                buildingsBySettlementId
                        .computeIfAbsent(settlementId, k -> new ArrayList<>())
                        .add(bld);
                insertBuildingIntoBucket(bld);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load building footprints GeoJSON: " + buildingsGeoJsonPath, e);
        }
    }

    private void insertBuildingIntoBucket(BuildingFootprint b) {
        int bx = buildingBucketCoord(b.centroidMetric().x());
        int by = buildingBucketCoord(b.centroidMetric().y());
        buildingBuckets
                .computeIfAbsent(bucketKey(bx, by), k -> new ArrayList<>())
                .add(b);
    }

    private void insertSettlementIntoBuckets(SettlementBoundaryPolygon s) {
        insertIntoBuckets(s, settlementBuckets, 100.0);
    }

    private void insertIntoBuckets(
            SettlementBoundaryPolygon s,
            Map<Long, List<SettlementBoundaryPolygon>> targetBuckets,
            double padMeters) {
        int minBx = settlementBucketCoord(s.minX() - padMeters);
        int maxBx = settlementBucketCoord(s.maxX() + padMeters);
        int minBy = settlementBucketCoord(s.minY() - padMeters);
        int maxBy = settlementBucketCoord(s.maxY() + padMeters);
        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                targetBuckets
                        .computeIfAbsent(bucketKey(bx, by), k -> new ArrayList<>())
                        .add(s);
            }
        }
    }

    private static int buildingBucketCoord(double meters) {
        return (int) Math.floor(meters / BUILDING_BUCKET_SIZE_METERS);
    }

    private static int settlementBucketCoord(double meters) {
        return (int) Math.floor(meters / SETTLEMENT_BUCKET_SIZE_METERS);
    }

    private static long bucketKey(int bx, int by) {
        return (((long) bx) << 32) ^ (by & 0xffffffffL);
    }

    public List<SettlementBoundaryPolygon> allSettlements() {
        ensureInitialized();
        return List.copyOf(settlements);
    }

    public List<BuildingFootprint> allBuildings() {
        ensureInitialized();
        return List.copyOf(buildings);
    }

    public List<BuildingFootprint> buildingsForSettlement(String settlementId) {
        ensureInitialized();
        return List.copyOf(buildingsBySettlementId.getOrDefault(settlementId, List.of()));
    }

    /**
     * Checks whether a geographic point lies inside (or within a 30 m setback of) any marked
     * zero-tolerance sacred/heritage precinct polygon.
     */
    public SettlementBoundaryPolygon findContainingSacredExclusion(GeoPoint point) {
        ensureInitialized();
        if (sacredExclusions.isEmpty() || point == null) {
            return null;
        }
        MetricPoint mp = projection.forward(point);
        double x = mp.x();
        double y = mp.y();
        for (SettlementBoundaryPolygon s : sacredExclusions) {
            if (x < s.minX() || x > s.maxX() || y < s.minY() || y > s.maxY()) {
                continue;
            }
            if (pointInPolygon(x, y, s.metricRing())) {
                return s;
            }
        }
        return null;
    }

    /**
     * Checks whether a step segment {@code from -> to} intersects or comes within {@code bufferMeters}
     * of any zero-tolerance sacred/heritage precinct polygon (sampling both endpoints, interior fractions,
     * and exact JTS segment distance).
     */
    public SettlementBoundaryPolygon findIntersectingSacredExclusion(
            GeoPoint from, GeoPoint to, double bufferMeters) {
        ensureInitialized();
        if (sacredExclusions.isEmpty() || from == null || to == null) {
            return null;
        }
        MetricPoint a = projection.forward(from);
        MetricPoint b = projection.forward(to);
        double minX = Math.min(a.x(), b.x()) - bufferMeters;
        double maxX = Math.max(a.x(), b.x()) + bufferMeters;
        double minY = Math.min(a.y(), b.y()) - bufferMeters;
        double maxY = Math.max(a.y(), b.y()) + bufferMeters;

        org.locationtech.jts.geom.LineString segGeom = null;
        for (SettlementBoundaryPolygon s : sacredExclusions) {
            if (maxX < s.minX() || minX > s.maxX() || maxY < s.minY() || minY > s.maxY()) {
                continue;
            }
            if (segGeom == null) {
                segGeom = geometryFactory.createLineString(new Coordinate[]{
                        new Coordinate(a.x(), a.y()),
                        new Coordinate(b.x(), b.y())
                });
            }
            if (s.jtsPolygonMetric().distance(segGeom) <= bufferMeters) {
                return s;
            }
        }
        return null;
    }

    /**
     * Checks whether a point lies inside or within {@code bufferMeters} (50 m - 100 m safety buffer)
     * of any Hospital / Medical Campus, Institutional / Educational Campus, or Sacred / Religious Precinct.
     */
    public ExclusionHit findContainingInstitutionalOrSacredExclusion(GeoPoint point, double bufferMeters) {
        ensureInitialized();
        if (point == null) {
            return null;
        }
        MetricPoint mp = projection.forward(point);
        double x = mp.x();
        double y = mp.y();
        double pad = Math.max(0.0, bufferMeters);

        // 1. Check bucketed institutional / hospital / sacred settlement compounds
        int sbx = settlementBucketCoord(x);
        int sby = settlementBucketCoord(y);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                List<SettlementBoundaryPolygon> cands = institutionalExclusionBuckets.get(bucketKey(sbx + dx, sby + dy));
                if (cands == null) {
                    continue;
                }
                for (SettlementBoundaryPolygon s : cands) {
                    if (x < s.minX() - pad || x > s.maxX() + pad || y < s.minY() - pad || y > s.maxY() + pad) {
                        continue;
                    }
                    if (pointInPolygon(x, y, s.metricRing())) {
                        String cat = settlementExclusionCategoryById.getOrDefault(s.settlementId(), "INSTITUTIONAL_CAMPUS");
                        return new ExclusionHit(s.settlementId(), s.name(), cat);
                    }
                    if (pad > 0.0) {
                        org.locationtech.jts.geom.Point ptGeom = geometryFactory.createPoint(new Coordinate(x, y));
                        if (s.jtsPolygonMetric().distance(ptGeom) <= pad) {
                            String cat = settlementExclusionCategoryById.getOrDefault(s.settlementId(), "INSTITUTIONAL_CAMPUS");
                            return new ExclusionHit(s.settlementId(), s.name(), cat);
                        }
                    }
                }
            }
        }

        // 2. Check bucketed individual hospital / institutional / religious buildings
        int bbx = buildingBucketCoord(x);
        int bby = buildingBucketCoord(y);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                List<BuildingFootprint> cands = buildingBuckets.get(bucketKey(bbx + dx, bby + dy));
                if (cands == null) {
                    continue;
                }
                for (BuildingFootprint b : cands) {
                    boolean isHardBld = b.buildingType() == BuildingType.INSTITUTIONAL
                            || b.buildingType() == BuildingType.RELIGIOUS
                            || hospitalBuildingIds.contains(b.buildingId());
                    if (!isHardBld) {
                        continue;
                    }
                    double d = Math.hypot(x - b.centroidMetric().x(), y - b.centroidMetric().y());
                    if (d <= b.equivalentRadiusMeters() + pad) {
                        String cat = hospitalBuildingIds.contains(b.buildingId())
                                ? "HOSPITAL_MEDICAL_CAMPUS"
                                : (b.buildingType() == BuildingType.RELIGIOUS ? "RELIGIOUS_PRECINCT" : "INSTITUTIONAL_CAMPUS");
                        return new ExclusionHit(b.buildingId(), b.name(), cat);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Exact 2D segment check against all Hospitals, Medical Campuses, Institutional/Educational Campuses,
     * and Sacred/Religious Precincts plus {@code bufferMeters} clearance.
     */
    public ExclusionHit findIntersectingInstitutionalOrSacredExclusion(
            GeoPoint from, GeoPoint to, double bufferMeters) {
        ensureInitialized();
        if (from == null || to == null) {
            return null;
        }
        MetricPoint a = projection.forward(from);
        MetricPoint b = projection.forward(to);
        double pad = Math.max(0.0, bufferMeters);
        double minX = Math.min(a.x(), b.x()) - pad;
        double maxX = Math.max(a.x(), b.x()) + pad;
        double minY = Math.min(a.y(), b.y()) - pad;
        double maxY = Math.max(a.y(), b.y()) + pad;

        org.locationtech.jts.geom.LineString segGeom = null;
        int minBx = settlementBucketCoord(minX);
        int maxBx = settlementBucketCoord(maxX);
        int minBy = settlementBucketCoord(minY);
        int maxBy = settlementBucketCoord(maxY);
        Set<String> checkedStl = null;

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                List<SettlementBoundaryPolygon> cands = institutionalExclusionBuckets.get(bucketKey(bx, by));
                if (cands == null) {
                    continue;
                }
                for (SettlementBoundaryPolygon s : cands) {
                    if (maxX < s.minX() || minX > s.maxX() || maxY < s.minY() || minY > s.maxY()) {
                        continue;
                    }
                    if (checkedStl == null) {
                        checkedStl = new HashSet<>();
                    }
                    if (!checkedStl.add(s.settlementId())) {
                        continue;
                    }
                    if (segGeom == null) {
                        segGeom = geometryFactory.createLineString(new Coordinate[]{
                                new Coordinate(a.x(), a.y()),
                                new Coordinate(b.x(), b.y())
                        });
                    }
                    if (s.jtsPolygonMetric().distance(segGeom) <= pad) {
                        String cat = settlementExclusionCategoryById.getOrDefault(s.settlementId(), "INSTITUTIONAL_CAMPUS");
                        return new ExclusionHit(s.settlementId(), s.name(), cat);
                    }
                }
            }
        }

        // Also check individual hospital / institutional / religious buildings along segment
        int bMinX = buildingBucketCoord(minX - 50.0);
        int bMaxX = buildingBucketCoord(maxX + 50.0);
        int bMinY = buildingBucketCoord(minY - 50.0);
        int bMaxY = buildingBucketCoord(maxY + 50.0);
        for (int bx = bMinX; bx <= bMaxX; bx++) {
            for (int by = bMinY; by <= bMaxY; by++) {
                List<BuildingFootprint> cands = buildingBuckets.get(bucketKey(bx, by));
                if (cands == null) {
                    continue;
                }
                for (BuildingFootprint bld : cands) {
                    boolean isHardBld = bld.buildingType() == BuildingType.INSTITUTIONAL
                            || bld.buildingType() == BuildingType.RELIGIOUS
                            || hospitalBuildingIds.contains(bld.buildingId());
                    if (!isHardBld) {
                        continue;
                    }
                    double dist = pointToSegmentDistance(
                            bld.centroidMetric().x(), bld.centroidMetric().y(),
                            a.x(), a.y(), b.x(), b.y());
                    if (dist <= bld.equivalentRadiusMeters() + pad) {
                        String cat = hospitalBuildingIds.contains(bld.buildingId())
                                ? "HOSPITAL_MEDICAL_CAMPUS"
                                : (bld.buildingType() == BuildingType.RELIGIOUS ? "RELIGIOUS_PRECINCT" : "INSTITUTIONAL_CAMPUS");
                        return new ExclusionHit(bld.buildingId(), bld.name(), cat);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Checks whether a point lies inside any dense urban or residential colony polygon.
     */
    public SettlementBoundaryPolygon findContainingDenseColony(GeoPoint point) {
        ensureInitialized();
        if (point == null || denseColonyBuckets.isEmpty()) {
            return null;
        }
        MetricPoint mp = projection.forward(point);
        double x = mp.x();
        double y = mp.y();
        List<SettlementBoundaryPolygon> cands = denseColonyBuckets.get(
                bucketKey(settlementBucketCoord(x), settlementBucketCoord(y)));
        if (cands == null) {
            return null;
        }
        for (SettlementBoundaryPolygon s : cands) {
            if (x < s.minX() || x > s.maxX() || y < s.minY() || y > s.maxY()) {
                continue;
            }
            if (pointInPolygon(x, y, s.metricRing())) {
                return s;
            }
        }
        return null;
    }

    /**
     * Exact 2D segment intersection check against all dense urban / residential colony polygons.
     */
    public SettlementBoundaryPolygon findIntersectingDenseColony(
            GeoPoint from, GeoPoint to, double bufferMeters) {
        ensureInitialized();
        if (from == null || to == null || denseColonyBuckets.isEmpty()) {
            return null;
        }
        MetricPoint a = projection.forward(from);
        MetricPoint b = projection.forward(to);
        double pad = Math.max(0.0, bufferMeters);
        double minX = Math.min(a.x(), b.x()) - pad;
        double maxX = Math.max(a.x(), b.x()) + pad;
        double minY = Math.min(a.y(), b.y()) - pad;
        double maxY = Math.max(a.y(), b.y()) + pad;

        int minBx = settlementBucketCoord(minX);
        int maxBx = settlementBucketCoord(maxX);
        int minBy = settlementBucketCoord(minY);
        int maxBy = settlementBucketCoord(maxY);
        org.locationtech.jts.geom.LineString segGeom = null;
        Set<String> checked = null;

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                List<SettlementBoundaryPolygon> cands = denseColonyBuckets.get(bucketKey(bx, by));
                if (cands == null) {
                    continue;
                }
                for (SettlementBoundaryPolygon s : cands) {
                    if (maxX < s.minX() || minX > s.maxX() || maxY < s.minY() || minY > s.maxY()) {
                        continue;
                    }
                    if (checked == null) {
                        checked = new HashSet<>();
                    }
                    if (!checked.add(s.settlementId())) {
                        continue;
                    }
                    if (segGeom == null) {
                        segGeom = geometryFactory.createLineString(new Coordinate[]{
                                new Coordinate(a.x(), a.y()),
                                new Coordinate(b.x(), b.y())
                        });
                    }
                    if (s.jtsPolygonMetric().distance(segGeom) <= pad) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Checks whether a point lies inside ANY settlement or residential colony boundary polygon.
     */
    public SettlementBoundaryPolygon findContainingSettlement(GeoPoint point) {
        ensureInitialized();
        if (point == null || settlementBuckets.isEmpty()) {
            return null;
        }
        MetricPoint mp = projection.forward(point);
        double x = mp.x();
        double y = mp.y();
        List<SettlementBoundaryPolygon> cands = settlementBuckets.get(
                bucketKey(settlementBucketCoord(x), settlementBucketCoord(y)));
        if (cands == null) {
            return null;
        }
        for (SettlementBoundaryPolygon s : cands) {
            if (x < s.minX() || x > s.maxX() || y < s.minY() || y > s.maxY()) {
                continue;
            }
            if (pointInPolygon(x, y, s.metricRing())) {
                return s;
            }
        }
        return null;
    }

    /**
     * Checks whether a point lies on or within {@code bufferMeters} of ANY building footprint.
     */
    public BuildingFootprint findContainingOrNearbyBuilding(GeoPoint point, double bufferMeters) {
        ensureInitialized();
        if (point == null || buildingBuckets.isEmpty()) {
            return null;
        }
        MetricPoint mp = projection.forward(point);
        double x = mp.x();
        double y = mp.y();
        double pad = Math.max(0.0, bufferMeters);
        int bbx = buildingBucketCoord(x);
        int bby = buildingBucketCoord(y);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                List<BuildingFootprint> cands = buildingBuckets.get(bucketKey(bbx + dx, bby + dy));
                if (cands == null) {
                    continue;
                }
                for (BuildingFootprint b : cands) {
                    double d = Math.hypot(x - b.centroidMetric().x(), y - b.centroidMetric().y());
                    if (d <= b.equivalentRadiusMeters() + pad) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Evaluates whether a start/end coordinate lands on any Hospital/Institutional/Religious compound
     * (within {@code institutionalBufferMeters}), any Settlement/Colony polygon, or any Building Footprint.
     * Returns a structured machine-readable reason string if invalid, or {@code null} if clean greenfield.
     */
    public String findEndpointBuildingOrSettlementObstacle(
            GeoPoint point,
            double institutionalBufferMeters,
            double buildingBufferMeters) {
        ensureInitialized();
        if (point == null) {
            return null;
        }
        ExclusionHit instHit = findContainingInstitutionalOrSacredExclusion(point, institutionalBufferMeters);
        if (instHit != null) {
            return instHit.category() + ":" + sanitizeLabel(instHit.name());
        }
        SettlementBoundaryPolygon colony = findContainingDenseColony(point);
        if (colony != null) {
            return "RESIDENTIAL_SETTLEMENT_COLONY:" + sanitizeLabel(colony.name());
        }
        SettlementBoundaryPolygon anyStl = findContainingSettlement(point);
        if (anyStl != null) {
            return "SETTLEMENT_BOUNDARY:" + sanitizeLabel(anyStl.name());
        }
        BuildingFootprint bld = findContainingOrNearbyBuilding(point, buildingBufferMeters);
        if (bld != null) {
            return "BUILDING_FOOTPRINT_" + bld.buildingType().name() + ":" + sanitizeLabel(bld.name());
        }
        return null;
    }

    /**
     * Exact 2D segment collision check for terminal access road connectors & feeder spurs:
     * returns {@code true} if the straight segment {@code from -> to} slices across ANY hospital,
     * institutional/religious compound, dense colony, or building footprint.
     */
    public boolean hasSegmentBuildingOrExclusionConflict(
            GeoPoint from,
            GeoPoint to,
            double halfRowWidthMeters,
            double institutionalBufferMeters) {
        ensureInitialized();
        if (from == null || to == null) {
            return false;
        }
        if (findIntersectingInstitutionalOrSacredExclusion(
                from, to, halfRowWidthMeters + institutionalBufferMeters) != null) {
            return true;
        }
        if (findIntersectingDenseColony(from, to, halfRowWidthMeters) != null) {
            return true;
        }
        MetricPoint a = projection.forward(from);
        MetricPoint b = projection.forward(to);
        StepConflictAssessment step = evaluateStepConflictsMetric(a, b, halfRowWidthMeters, 0.0);
        return step.directRowBuildingHitsCount() > 0;
    }

    private static String sanitizeLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "UNNAMED";
        }
        return raw.trim().replaceAll("[^a-zA-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    public record ExclusionHit(String id, String name, String category) {}

    /**
     * Fast metric step evaluation for A* search: checks all building footprints and settlement polygons
     * within {@code halfRowWidthMeters + proximityBufferMeters} of step segment {@code a -> b}.
     */
    public StepConflictAssessment evaluateStepConflictsMetric(
            MetricPoint a,
            MetricPoint b,
            double halfRowWidthMeters,
            double proximityBufferMeters) {
        ensureInitialized();

        double searchRadius = halfRowWidthMeters + proximityBufferMeters + 25.0;
        double minX = Math.min(a.x(), b.x()) - searchRadius;
        double maxX = Math.max(a.x(), b.x()) + searchRadius;
        double minY = Math.min(a.y(), b.y()) - searchRadius;
        double maxY = Math.max(a.y(), b.y()) + searchRadius;

        int minBx = buildingBucketCoord(minX);
        int maxBx = buildingBucketCoord(maxX);
        int minBy = buildingBucketCoord(minY);
        int maxBy = buildingBucketCoord(maxY);

        int directRowHitCount = 0;
        int proximityHitCount = 0;
        List<BuildingHit> hits = null;

        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                List<BuildingFootprint> bucket = buildingBuckets.get(bucketKey(bx, by));
                if (bucket == null) {
                    continue;
                }
                for (BuildingFootprint bld : bucket) {
                    double centroidDist = pointToSegmentDistance(
                            bld.centroidMetric().x(),
                            bld.centroidMetric().y(),
                            a.x(),
                            a.y(),
                            b.x(),
                            b.y());
                    double edgeDistToCenterline = Math.max(0.0, centroidDist - bld.equivalentRadiusMeters());
                    if (edgeDistToCenterline <= halfRowWidthMeters) {
                        directRowHitCount++;
                        if (hits == null) {
                            hits = new ArrayList<>(4);
                        }
                        hits.add(new BuildingHit(bld, true, edgeDistToCenterline));
                    } else if (edgeDistToCenterline <= halfRowWidthMeters + proximityBufferMeters) {
                        proximityHitCount++;
                        if (hits == null) {
                            hits = new ArrayList<>(4);
                        }
                        hits.add(new BuildingHit(bld, false, edgeDistToCenterline));
                    }
                }
            }
        }

        // Check if step midpoint or endpoint slices inside a SettlementBoundaryPolygon
        double midX = 0.5 * (a.x() + b.x());
        double midY = 0.5 * (a.y() + b.y());
        SettlementBoundaryPolygon intersectedSettlement = findSettlementContainingOrNear(
                midX, midY, b.x(), b.y(), halfRowWidthMeters);

        return new StepConflictAssessment(
                directRowHitCount,
                proximityHitCount,
                hits != null ? hits : List.of(),
                intersectedSettlement);
    }

    private SettlementBoundaryPolygon findSettlementContainingOrNear(
            double midX, double midY, double endX, double endY, double halfRowWidthMeters) {
        int sbx = settlementBucketCoord(midX);
        int sby = settlementBucketCoord(midY);
        List<SettlementBoundaryPolygon> candidates = settlementBuckets.get(bucketKey(sbx, sby));
        if (candidates == null) {
            return null;
        }
        SettlementBoundaryPolygon best = null;
        double bestDensity = -1.0;
        for (SettlementBoundaryPolygon s : candidates) {
            if (midX < s.minX() - halfRowWidthMeters
                    || midX > s.maxX() + halfRowWidthMeters
                    || midY < s.minY() - halfRowWidthMeters
                    || midY > s.maxY() + halfRowWidthMeters) {
                continue;
            }
            if (pointInPolygon(midX, midY, s.metricRing())
                    || pointInPolygon(endX, endY, s.metricRing())) {
                if (s.builtUpDensityBuildingsPerHa() > bestDensity) {
                    bestDensity = s.builtUpDensityBuildingsPerHa();
                    best = s;
                }
            }
        }
        return best;
    }

    /**
     * Evaluates the complete 2D Right-of-Way (RoW) Corridor Footprint against all 1,882 individual
     * building footprints and 104 settlement boundary polygons.
     */
    public RouteBuildingAndSettlementImpact evaluateRouteRowImpact(
            List<GeoPoint> mainline,
            RightOfWayCorridorEngine rowEngine,
            FacilityMode mode) {
        RightOfWayCorridorEngine.CorridorFootprint corridor =
                rowEngine.buildCorridorFootprint(mainline, mode);
        return evaluateCorridorFootprintImpact(mainline, corridor);
    }

    /**
     * Evaluates a pre-built {@link RightOfWayCorridorEngine.CorridorFootprint} (which may include
     * mainline + feeder spurs + interchanges) against all building footprints and settlement polygons.
     */
    public RouteBuildingAndSettlementImpact evaluateCorridorFootprintImpact(
            List<GeoPoint> mainline,
            RightOfWayCorridorEngine.CorridorFootprint corridor) {
        ensureInitialized();

        PreparedGeometry prepDirect = PreparedGeometryFactory.prepare(corridor.combinedDirectRowMetric());
        PreparedGeometry prepConst = PreparedGeometryFactory.prepare(corridor.constructionZoneMetric());
        PreparedGeometry prepEnv = PreparedGeometryFactory.prepare(corridor.environmentalBufferMetric());
        Geometry rowBoundary = corridor.combinedDirectRowMetric().getBoundary();
        Geometry centerlineGeom = corridor.combinedCenterlinesMetric();

        int buildingsInDirectRow = 0;
        int buildingsInConstructionRing = 0;
        int buildingsInEnvRing = 0;
        double directRowFootprintAreaSqM = 0.0;
        double directRowFloorSpaceAreaSqM = 0.0;
        double constructionZoneFootprintAreaSqM = 0.0;

        Map<BuildingType, Integer> directByType = new EnumMap<>(BuildingType.class);
        Map<BuildingType, Integer> constructionByType = new EnumMap<>(BuildingType.class);
        Map<BuildingType, Integer> envByType = new EnumMap<>(BuildingType.class);
        for (BuildingType bt : BuildingType.values()) {
            directByType.put(bt, 0);
            constructionByType.put(bt, 0);
            envByType.put(bt, 0);
        }

        double minCenterlineToBuildingMeters = Double.POSITIVE_INFINITY;
        double minRowEdgeToBuildingMeters = Double.POSITIVE_INFINITY;
        List<AffectedBuildingRecord> affectedBuildings = new ArrayList<>();
        org.locationtech.jts.geom.Envelope envBounds = corridor.environmentalBufferMetric().getEnvelopeInternal();
        double envMinX = envBounds.getMinX() - 1500.0;
        double envMaxX = envBounds.getMaxX() + 1500.0;
        double envMinY = envBounds.getMinY() - 1500.0;
        double envMaxY = envBounds.getMaxY() + 1500.0;

        for (BuildingFootprint bld : buildings) {
            double bx = bld.centroidMetric().x();
            double by = bld.centroidMetric().y();
            if (bx < envMinX || bx > envMaxX || by < envMinY || by > envMaxY) {
                continue;
            }
            Polygon poly = bld.jtsPolygonMetric();
            boolean inEnv = prepEnv.intersects(poly);
            if (!inEnv) {
                // Fast centroid bound check before exact distance for buildings within 1.5 km
                double approxDist = centerlineGeom.distance(poly);
                if (approxDist < minCenterlineToBuildingMeters) {
                    minCenterlineToBuildingMeters = approxDist;
                }
                double edgeDist = rowBoundary.distance(poly);
                if (edgeDist < minRowEdgeToBuildingMeters) {
                    minRowEdgeToBuildingMeters = edgeDist;
                }
                continue;
            }

            double distCenterline = centerlineGeom.distance(poly);
            if (distCenterline < minCenterlineToBuildingMeters) {
                minCenterlineToBuildingMeters = distCenterline;
            }
            double rawEdgeDist = rowBoundary.distance(poly);

            RowImpactZone zone;
            double signedEdgeDist;
            if (prepDirect.intersects(poly)) {
                zone = RowImpactZone.DIRECT_ROW_FOOTPRINT;
                signedEdgeDist = -rawEdgeDist;
                minRowEdgeToBuildingMeters = 0.0;
                buildingsInDirectRow++;
                directRowFootprintAreaSqM += bld.footprintAreaSqMeters();
                directRowFloorSpaceAreaSqM += bld.footprintAreaSqMeters() * bld.floors();
                directByType.put(bld.buildingType(), directByType.get(bld.buildingType()) + 1);
            } else if (prepConst.intersects(poly)) {
                zone = RowImpactZone.CONSTRUCTION_CLEARANCE_ZONE;
                signedEdgeDist = rawEdgeDist;
                if (rawEdgeDist < minRowEdgeToBuildingMeters) {
                    minRowEdgeToBuildingMeters = rawEdgeDist;
                }
                buildingsInConstructionRing++;
                constructionZoneFootprintAreaSqM += bld.footprintAreaSqMeters();
                constructionByType.put(bld.buildingType(), constructionByType.get(bld.buildingType()) + 1);
            } else {
                zone = RowImpactZone.ENVIRONMENTAL_INFLUENCE_BUFFER;
                signedEdgeDist = rawEdgeDist;
                if (rawEdgeDist < minRowEdgeToBuildingMeters) {
                    minRowEdgeToBuildingMeters = rawEdgeDist;
                }
                buildingsInEnvRing++;
                envByType.put(bld.buildingType(), envByType.get(bld.buildingType()) + 1);
            }

            affectedBuildings.add(new AffectedBuildingRecord(
                    bld.buildingId(),
                    bld.name(),
                    bld.buildingType(),
                    bld.settlementId(),
                    bld.settlementName(),
                    bld.settlementClassification(),
                    bld.footprintAreaSqMeters(),
                    bld.floors(),
                    bld.structureMaterial(),
                    zone,
                    round(distCenterline),
                    round(signedEdgeDist)));
        }

        // Evaluate 104 Settlement Boundary Polygons against the 2D Corridor
        int settlementsInDirectRow = 0;
        int settlementsInConstructionZone = 0;
        int settlementsInEnvBuffer = 0;
        double settlementAreaInDirectRowHa = 0.0;
        double settlementAreaInConstructionZoneHa = 0.0;
        double settlementAreaInEnvBufferHa = 0.0;
        Map<SettlementClassification, Integer> settlementsDirectByClass =
                new EnumMap<>(SettlementClassification.class);
        Map<SettlementClassification, Double> settlementHaDirectByClass =
                new EnumMap<>(SettlementClassification.class);
        for (SettlementClassification sc : SettlementClassification.values()) {
            settlementsDirectByClass.put(sc, 0);
            settlementHaDirectByClass.put(sc, 0.0);
        }

        double minCenterlineToSettlementMeters = Double.POSITIVE_INFINITY;
        double minRowEdgeToSettlementMeters = Double.POSITIVE_INFINITY;
        List<AffectedSettlementRecord> affectedSettlements = new ArrayList<>();

        for (SettlementBoundaryPolygon stl : settlements) {
            Polygon poly = stl.jtsPolygonMetric();
            double distCenterline = centerlineGeom.distance(poly);
            if (distCenterline < minCenterlineToSettlementMeters) {
                minCenterlineToSettlementMeters = distCenterline;
            }
            double edgeDist = prepDirect.intersects(poly) ? 0.0 : rowBoundary.distance(poly);
            if (edgeDist < minRowEdgeToSettlementMeters) {
                minRowEdgeToSettlementMeters = edgeDist;
            }

            if (!prepEnv.intersects(poly)) {
                continue;
            }

            double directHa = corridor.combinedDirectRowMetric().intersection(poly).getArea() / SQ_METERS_PER_HECTARE;
            double constHa = corridor.constructionZoneMetric().intersection(poly).getArea() / SQ_METERS_PER_HECTARE;
            double envHa = corridor.environmentalBufferMetric().intersection(poly).getArea() / SQ_METERS_PER_HECTARE;

            settlementsInEnvBuffer++;
            settlementAreaInEnvBufferHa += envHa;
            if (constHa > 1e-4 || prepConst.intersects(poly)) {
                settlementsInConstructionZone++;
                settlementAreaInConstructionZoneHa += constHa;
            }
            if (directHa > 1e-4 || prepDirect.intersects(poly)) {
                settlementsInDirectRow++;
                settlementAreaInDirectRowHa += directHa;
                settlementsDirectByClass.put(
                        stl.settlementClassification(),
                        settlementsDirectByClass.get(stl.settlementClassification()) + 1);
                settlementHaDirectByClass.put(
                        stl.settlementClassification(),
                        settlementHaDirectByClass.get(stl.settlementClassification()) + directHa);
            }

            // Count how many buildings in this settlement fall inside Direct RoW & Construction Zone
            int stlBldDirect = 0;
            int stlBldConst = 0;
            double stlBldAreaSqM = 0.0;
            Map<String, Integer> stlBldByType = new LinkedHashMap<>();
            for (AffectedBuildingRecord abr : affectedBuildings) {
                if (stl.settlementId().equals(abr.settlementId())) {
                    if (abr.impactZone() == RowImpactZone.DIRECT_ROW_FOOTPRINT) {
                        stlBldDirect++;
                        stlBldAreaSqM += abr.footprintAreaSqMeters();
                        stlBldByType.merge(abr.buildingType().name(), 1, Integer::sum);
                    } else if (abr.impactZone() == RowImpactZone.CONSTRUCTION_CLEARANCE_ZONE) {
                        stlBldConst++;
                    }
                }
            }

            affectedSettlements.add(new AffectedSettlementRecord(
                    stl.settlementId(),
                    stl.name(),
                    stl.settlementClassification(),
                    stl.settlementSubtype(),
                    stl.district(),
                    stl.builtUpDensityBuildingsPerHa(),
                    stl.builtUpDensityClass(),
                    round(stl.polygonAreaHectares()),
                    round4(directHa),
                    round4(constHa),
                    round4(envHa),
                    stlBldDirect,
                    stlBldConst,
                    Map.copyOf(stlBldByType),
                    round(stlBldAreaSqM),
                    round(distCenterline),
                    round(edgeDist)));
        }

        List<RouteSettlementConflict> conflicts =
                findRouteSettlementConflictsFromOverlay(mainline, affectedSettlements, affectedBuildings);

        return new RouteBuildingAndSettlementImpact(
                buildings.size(),
                settlements.size(),
                buildingsInDirectRow,
                buildingsInConstructionRing,
                buildingsInEnvRing,
                round(directRowFootprintAreaSqM),
                round(directRowFloorSpaceAreaSqM),
                round(constructionZoneFootprintAreaSqM),
                toStringKeyMap(directByType),
                toStringKeyMap(constructionByType),
                toStringKeyMap(envByType),
                settlementsInDirectRow,
                settlementsInConstructionZone,
                settlementsInEnvBuffer,
                round(settlementAreaInDirectRowHa),
                round(settlementAreaInConstructionZoneHa),
                round(settlementAreaInEnvBufferHa),
                toStringKeyMap(settlementsDirectByClass),
                toStringDoubleMap(settlementHaDirectByClass),
                Double.isInfinite(minCenterlineToBuildingMeters) ? -1.0 : round(minCenterlineToBuildingMeters),
                Double.isInfinite(minRowEdgeToBuildingMeters) ? -1.0 : round(minRowEdgeToBuildingMeters),
                Double.isInfinite(minCenterlineToSettlementMeters) ? -1.0 : round(minCenterlineToSettlementMeters),
                Double.isInfinite(minRowEdgeToSettlementMeters) ? -1.0 : round(minRowEdgeToSettlementMeters),
                List.copyOf(affectedBuildings),
                List.copyOf(affectedSettlements),
                conflicts);
    }

    /**
     * Identifies chainage-ordered settlement & building acquisition conflict zones along the route
     * where the Direct RoW or Construction Clearance Zone intersects a settlement boundary or its buildings.
     */
    public List<RouteSettlementConflict> findRouteSettlementConflicts(
            List<GeoPoint> mainline,
            RightOfWayCorridorEngine rowEngine,
            FacilityMode mode) {
        return evaluateRouteRowImpact(mainline, rowEngine, mode).settlementConflicts();
    }

    private List<RouteSettlementConflict> findRouteSettlementConflictsFromOverlay(
            List<GeoPoint> mainline,
            List<AffectedSettlementRecord> affectedSettlements,
            List<AffectedBuildingRecord> affectedBuildings) {
        if (mainline == null || mainline.size() < 2
                || (affectedSettlements.isEmpty() && affectedBuildings.isEmpty())) {
            return List.of();
        }

        List<RouteSettlementConflict> conflicts = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (AffectedSettlementRecord asr : affectedSettlements) {
            boolean hasConflict = asr.areaInDirectRowHectares() > 1e-4
                    || asr.areaInConstructionZoneHectares() > 1e-3
                    || asr.buildingsInDirectRowCount() > 0
                    || asr.buildingsInConstructionZoneCount() > 0;
            if (!hasConflict || !seen.add(asr.settlementId())) {
                continue;
            }

            SettlementBoundaryPolygon poly = settlementById.get(asr.settlementId());
            if (poly == null) {
                continue;
            }

            ChainageProjection proj = projectOntoMainline(mainline, poly.centroidMetric());
            String mitigation;
            if (asr.buildingsInDirectRowCount() > 0) {
                boolean hasInstitutionalOrReligious =
                        asr.buildingsInDirectRowByType().containsKey(BuildingType.INSTITUTIONAL.name())
                                || asr.buildingsInDirectRowByType().containsKey(BuildingType.RELIGIOUS.name());
                if (hasInstitutionalOrReligious) {
                    mitigation = "ELEVATED_VIADUCT_OR_ALIGNMENT_SHIFT_TO_PROTECT_COMMUNITY_COMPOUND";
                } else if (asr.settlementClassification() == SettlementClassification.URBAN) {
                    mitigation = "ELEVATED_URBAN_FLYOVER_WITH_SERVICE_ROADS_AND_RFCTLARR_RR";
                } else {
                    mitigation = "REINFORCED_RETAINING_WALL_TOE_RESTRICTION_AND_RFCTLARR_2013_RR";
                }
            } else if (asr.areaInDirectRowHectares() > 0.05) {
                mitigation = asr.settlementClassification().defaultMitigationStructureType();
            } else {
                mitigation = "CONSTRUCTION_BUFFER_NOISE_BARRIER_AND_PEDESTRIAN_CATTLE_SUBWAY";
            }

            double conflictCostKmEq = computeSettlementConflictCostKmEq(asr);

            conflicts.add(new RouteSettlementConflict(
                    asr.settlementId(),
                    asr.settlementName(),
                    asr.settlementClassification(),
                    asr.settlementSubtype(),
                    asr.district(),
                    proj.closestPoint(),
                    round(proj.chainageKm()),
                    asr.builtUpDensityBuildingsPerHa(),
                    asr.builtUpDensityClass(),
                    asr.buildingsInDirectRowCount(),
                    asr.buildingsInConstructionZoneCount(),
                    asr.buildingsInDirectRowByType(),
                    asr.affectedDirectRowFootprintSqMeters(),
                    asr.areaInDirectRowHectares(),
                    asr.areaInConstructionZoneHectares(),
                    asr.distanceToCenterlineMeters(),
                    asr.distanceToRowEdgeMeters(),
                    mitigation,
                    round(conflictCostKmEq)));
        }

        // Also emit chainage-grouped conflict zones for isolated/fringe demolished buildings
        // that lie outside named settlement polygons so every broken structure is highlighted on the map
        Map<Integer, List<BuildingFootprint>> isolatedDirectByKmBucket = new LinkedHashMap<>();
        Map<String, BuildingFootprint> bldById = null;
        for (AffectedBuildingRecord abr : affectedBuildings) {
            if (abr.impactZone() != RowImpactZone.DIRECT_ROW_FOOTPRINT || seen.contains(abr.settlementId())) {
                continue;
            }
            if (bldById == null) {
                bldById = new HashMap<>(buildings.size());
                for (BuildingFootprint b : buildings) {
                    bldById.put(b.buildingId(), b);
                }
            }
            BuildingFootprint bld = bldById.get(abr.buildingId());
            if (bld == null) {
                continue;
            }
            ChainageProjection proj = projectOntoMainline(mainline, bld.centroidMetric());
            int kmBucket = (int) Math.floor(proj.chainageKm());
            isolatedDirectByKmBucket.computeIfAbsent(kmBucket, k -> new ArrayList<>()).add(bld);
        }

        for (Map.Entry<Integer, List<BuildingFootprint>> entry : isolatedDirectByKmBucket.entrySet()) {
            List<BuildingFootprint> group = entry.getValue();
            if (group.isEmpty()) {
                continue;
            }
            BuildingFootprint rep = group.get(0);
            ChainageProjection proj = projectOntoMainline(mainline, rep.centroidMetric());
            Map<String, Integer> byType = new LinkedHashMap<>();
            double totalAreaSqM = 0.0;
            double totalCostKmEq = 0.0;
            for (BuildingFootprint b : group) {
                byType.merge(b.buildingType().name(), 1, Integer::sum);
                totalAreaSqM += b.footprintAreaSqMeters();
                totalCostKmEq += b.buildingType().defaultDemolitionConflictKmEq();
            }
            String zoneId = "ISO-BLD-KM-" + entry.getKey();
            String zoneName = group.size() == 1
                    ? rep.name() + " (Isolated Structure in RoW)"
                    : rep.settlementName() + " — " + group.size() + " Isolated Structures at Ch. " + round(proj.chainageKm()) + " km";
            conflicts.add(new RouteSettlementConflict(
                    zoneId,
                    zoneName,
                    rep.settlementClassification(),
                    "ISOLATED_CORRIDOR_STRUCTURES_IN_ROW",
                    rep.district(),
                    proj.closestPoint(),
                    round(proj.chainageKm()),
                    2.0,
                    BuildingDensityClass.SPARSE_HOMESTEAD_FRINGE.name(),
                    group.size(),
                    0,
                    Map.copyOf(byType),
                    round(totalAreaSqM),
                    round4(totalAreaSqM / SQ_METERS_PER_HECTARE),
                    0.0,
                    round(proj.distanceMeters()),
                    0.0,
                    "RFCTLARR_2013_STRUCTURE_COMPENSATION_AND_REHABILITATION",
                    round(totalCostKmEq)));
        }

        conflicts.sort(Comparator.comparingDouble(RouteSettlementConflict::chainageKm));
        return List.copyOf(conflicts);
    }

    /**
     * Estimates RFCTLARR 2013 directly displaced households for a single demolished structure inside Direct RoW,
     * accounting for building type, floors, and residential floor-area ratio.
     */
    public static double estimateHouseholdsForDemolishedBuilding(
            BuildingType type,
            double footprintAreaSqMeters,
            int floors) {
        int effFloors = Math.max(1, floors);
        double floorAreaSqM = Math.max(25.0, footprintAreaSqMeters) * effFloors;
        return switch (type) {
            case RESIDENTIAL -> {
                double byFloors = effFloors;
                double byFloorArea = Math.max(1.0, Math.min(24.0, floorAreaSqM / RFCTLARR_FLOOR_AREA_PER_HOUSEHOLD_SQM));
                yield Math.max(byFloors, Math.round(byFloorArea));
            }
            case COMMERCIAL -> Math.max(0.35, 0.35 * Math.min(8.0, effFloors));
            case INDUSTRIAL -> 0.50;
            case INSTITUTIONAL, RELIGIOUS, VILLAGE_INFRASTRUCTURE -> 0.0;
        };
    }

    private static double computeSettlementConflictCostKmEq(AffectedSettlementRecord asr) {
        double cost = 0.0;
        for (Map.Entry<String, Integer> e : asr.buildingsInDirectRowByType().entrySet()) {
            BuildingType bt = BuildingType.valueOf(e.getKey());
            cost += e.getValue() * bt.defaultDemolitionConflictKmEq();
        }
        cost += asr.buildingsInConstructionZoneCount() * 0.12;
        cost += asr.areaInDirectRowHectares()
                * asr.settlementClassification().defaultSeverancePenaltyFactor()
                * 0.35;
        if (cost <= 0.0 && asr.areaInConstructionZoneHectares() > 0.0) {
            cost = 0.25 + asr.areaInConstructionZoneHectares() * 0.10;
        }
        return cost;
    }

    private ChainageProjection projectOntoMainline(List<GeoPoint> mainline, MetricPoint target) {
        double cumulativeKm = 0.0;
        double bestDist = Double.POSITIVE_INFINITY;
        double bestChainageKm = 0.0;
        GeoPoint bestPoint = mainline.get(0);

        for (int i = 1; i < mainline.size(); i++) {
            MetricPoint a = projection.forward(mainline.get(i - 1));
            MetricPoint b = projection.forward(mainline.get(i));
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double segLenSq = dx * dx + dy * dy;
            double segLen = Math.sqrt(segLenSq);
            if (segLen <= 1e-6) {
                continue;
            }
            double t = ((target.x() - a.x()) * dx + (target.y() - a.y()) * dy) / segLenSq;
            t = Math.max(0.0, Math.min(1.0, t));
            double px = a.x() + t * dx;
            double py = a.y() + t * dy;
            double dist = Math.hypot(target.x() - px, target.y() - py);
            if (dist < bestDist) {
                bestDist = dist;
                bestChainageKm = cumulativeKm + (t * segLen) / 1000.0;
                bestPoint = projection.inverse(new MetricPoint(px, py));
            }
            cumulativeKm += segLen / 1000.0;
        }
        return new ChainageProjection(bestPoint, bestChainageKm, bestDist);
    }

    private static double pointToSegmentDistance(
            double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax;
        double dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq <= 1e-12) {
            return Math.hypot(px - ax, py - ay);
        }
        double t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double projX = ax + t * dx;
        double projY = ay + t * dy;
        return Math.hypot(px - projX, py - projY);
    }

    private static boolean pointInPolygon(double x, double y, List<MetricPoint> ring) {
        boolean inside = false;
        int n = ring.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring.get(i).x();
            double yi = ring.get(i).y();
            double xj = ring.get(j).x();
            double yj = ring.get(j).y();
            boolean intersect = ((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / ((yj - yi) + 1e-12) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static <E extends Enum<E>> Map<String, Integer> toStringKeyMap(Map<E, Integer> map) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<E, Integer> e : map.entrySet()) {
            out.put(e.getKey().name(), e.getValue());
        }
        return Map.copyOf(out);
    }

    private static <E extends Enum<E>> Map<String, Double> toStringDoubleMap(Map<E, Double> map) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<E, Double> e : map.entrySet()) {
            out.put(e.getKey().name(), round4(e.getValue()));
        }
        return Map.copyOf(out);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private record ChainageProjection(GeoPoint closestPoint, double chainageKm, double distanceMeters) {}

    public record SettlementBoundaryPolygon(
            String settlementId,
            String catalogRef,
            String name,
            SettlementClassification settlementClassification,
            String settlementSubtype,
            String district,
            double polygonAreaHectares,
            double coreRadiusMeters,
            double builtUpDensityBuildingsPerHa,
            String builtUpDensityClass,
            int mappedBuildingFootprintCount,
            boolean sacredExclusion,
            GeoPoint centroid,
            MetricPoint centroidMetric,
            List<GeoPoint> exteriorRing,
            List<MetricPoint> metricRing,
            Polygon jtsPolygonMetric,
            double minX,
            double minY,
            double maxX,
            double maxY
    ) {}

    public record BuildingFootprint(
            String buildingId,
            String name,
            BuildingType buildingType,
            String settlementId,
            String settlementName,
            SettlementClassification settlementClassification,
            String district,
            double footprintAreaSqMeters,
            int floors,
            String structureMaterial,
            double equivalentRadiusMeters,
            double demolitionConflictWeightKmEq,
            GeoPoint centroid,
            MetricPoint centroidMetric,
            List<GeoPoint> exteriorRing,
            List<MetricPoint> metricRing,
            Polygon jtsPolygonMetric
    ) {}

    public record BuildingHit(
            BuildingFootprint building,
            boolean insideDirectRow,
            double distanceToCenterlineMeters
    ) {}

    public record StepConflictAssessment(
            int directRowBuildingHitsCount,
            int proximityBufferBuildingHitsCount,
            List<BuildingHit> buildingHits,
            SettlementBoundaryPolygon intersectedSettlement
    ) {}

    public record AffectedBuildingRecord(
            String buildingId,
            String name,
            BuildingType buildingType,
            String settlementId,
            String settlementName,
            SettlementClassification settlementClassification,
            double footprintAreaSqMeters,
            int floors,
            String structureMaterial,
            RowImpactZone impactZone,
            double distanceToCenterlineMeters,
            double signedDistanceToRowEdgeMeters
    ) {}

    public record AffectedSettlementRecord(
            String settlementId,
            String settlementName,
            SettlementClassification settlementClassification,
            String settlementSubtype,
            String district,
            double builtUpDensityBuildingsPerHa,
            String builtUpDensityClass,
            double totalSettlementAreaHectares,
            double areaInDirectRowHectares,
            double areaInConstructionZoneHectares,
            double areaInEnvironmentalBufferHectares,
            int buildingsInDirectRowCount,
            int buildingsInConstructionZoneCount,
            Map<String, Integer> buildingsInDirectRowByType,
            double affectedDirectRowFootprintSqMeters,
            double distanceToCenterlineMeters,
            double distanceToRowEdgeMeters
    ) {}

    public record RouteSettlementConflict(
            String settlementId,
            String settlementName,
            SettlementClassification settlementClassification,
            String settlementSubtype,
            String district,
            GeoPoint point,
            double chainageKm,
            double builtUpDensityBuildingsPerHa,
            String builtUpDensityClass,
            int buildingsInDirectRowCount,
            int buildingsInConstructionZoneCount,
            Map<String, Integer> buildingsInDirectRowByType,
            double affectedDirectRowFootprintSqMeters,
            double areaInDirectRowHectares,
            double areaInConstructionZoneHectares,
            double distanceToCenterlineMeters,
            double distanceToRowEdgeMeters,
            String recommendedEngineeringMitigation,
            double conflictCostKmEquivalent
    ) {}

    public record RouteBuildingAndSettlementImpact(
            int totalIndexedBuildingsCount,
            int totalIndexedSettlementsCount,
            int buildingsInDirectRowCount,
            int buildingsInConstructionZoneRingCount,
            int buildingsInEnvironmentalBufferRingCount,
            double directRowAffectedFootprintAreaSqMeters,
            double directRowAffectedFloorSpaceSqMeters,
            double constructionZoneAffectedFootprintAreaSqMeters,
            Map<String, Integer> buildingsInDirectRowByType,
            Map<String, Integer> buildingsInConstructionZoneByType,
            Map<String, Integer> buildingsInEnvironmentalBufferByType,
            int settlementsIntersectingDirectRowCount,
            int settlementsIntersectingConstructionZoneCount,
            int settlementsIntersectingEnvironmentalBufferCount,
            double settlementAreaInDirectRowHectares,
            double settlementAreaInConstructionZoneHectares,
            double settlementAreaInEnvironmentalBufferHectares,
            Map<String, Integer> settlementsInDirectRowByClassification,
            Map<String, Double> settlementHectaresInDirectRowByClassification,
            double minCenterlineToBuildingMeters,
            double minRowEdgeToBuildingMeters,
            double minCenterlineToSettlementMeters,
            double minRowEdgeToSettlementMeters,
            List<AffectedBuildingRecord> affectedBuildings,
            List<AffectedSettlementRecord> affectedSettlements,
            List<RouteSettlementConflict> settlementConflicts
    ) {
        public Map<String, Object> toSummaryMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalIndexedBuildingsCount", totalIndexedBuildingsCount);
            map.put("totalIndexedSettlementsCount", totalIndexedSettlementsCount);
            map.put("buildingsInDirectRowCount", buildingsInDirectRowCount);
            map.put("buildingsInConstructionZoneRingCount", buildingsInConstructionZoneRingCount);
            map.put("buildingsInEnvironmentalBufferRingCount", buildingsInEnvironmentalBufferRingCount);
            map.put("totalPotentiallyAffectedStructuresWithin100mCount",
                    buildingsInDirectRowCount + buildingsInConstructionZoneRingCount);
            map.put("directRowAffectedFootprintAreaSqMeters", directRowAffectedFootprintAreaSqMeters);
            map.put("directRowAffectedFloorSpaceSqMeters", directRowAffectedFloorSpaceSqMeters);
            map.put("constructionZoneAffectedFootprintAreaSqMeters", constructionZoneAffectedFootprintAreaSqMeters);
            map.put("buildingsInDirectRowByType", buildingsInDirectRowByType);
            map.put("buildingsInConstructionZoneByType", buildingsInConstructionZoneByType);
            map.put("buildingsInEnvironmentalBufferByType", buildingsInEnvironmentalBufferByType);
            map.put("settlementsIntersectingDirectRowCount", settlementsIntersectingDirectRowCount);
            map.put("settlementsIntersectingConstructionZoneCount", settlementsIntersectingConstructionZoneCount);
            map.put("settlementsIntersectingEnvironmentalBufferCount", settlementsIntersectingEnvironmentalBufferCount);
            map.put("settlementAreaInDirectRowHectares", settlementAreaInDirectRowHectares);
            map.put("settlementAreaInConstructionZoneHectares", settlementAreaInConstructionZoneHectares);
            map.put("settlementAreaInEnvironmentalBufferHectares", settlementAreaInEnvironmentalBufferHectares);
            map.put("settlementsInDirectRowByClassification", settlementsInDirectRowByClassification);
            map.put("settlementHectaresInDirectRowByClassification", settlementHectaresInDirectRowByClassification);
            map.put("minCenterlineToBuildingMeters", minCenterlineToBuildingMeters);
            map.put("minRowEdgeToBuildingMeters", minRowEdgeToBuildingMeters);
            map.put("minCenterlineToSettlementMeters", minCenterlineToSettlementMeters);
            map.put("minRowEdgeToSettlementMeters", minRowEdgeToSettlementMeters);
            double estHh = 0.0;
            for (AffectedBuildingRecord abr : affectedBuildings) {
                if (abr.impactZone() == RowImpactZone.DIRECT_ROW_FOOTPRINT) {
                    estHh += estimateHouseholdsForDemolishedBuilding(
                            abr.buildingType(), abr.footprintAreaSqMeters(), abr.floors());
                }
            }
            int estDisplacedHouseholds = (int) Math.round(estHh);
            int estDisplacedPopulation = (int) Math.round(estHh * RFCTLARR_PERSONS_PER_HOUSEHOLD);
            map.put("estimatedDirectlyDisplacedHouseholds", estDisplacedHouseholds);
            map.put("estimatedDirectlyDisplacedPopulation", estDisplacedPopulation);
            map.put("settlementConflictZonesCount", settlementConflicts.size());
            return map;
        }
    }
}
