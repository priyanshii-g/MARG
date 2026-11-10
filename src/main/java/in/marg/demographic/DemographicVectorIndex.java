package in.marg.demographic;

import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.building.BuildingType;
import in.marg.building.SettlementClassification;
import in.marg.model.GeoPoint;
import in.marg.row.RightOfWayCorridorEngine;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.transport.FacilityMode;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory EPSG:32646 spatial vector index for Layer 12: Population Density,
 * Village/Urban Census Population & Demographic Vulnerability Tracts
 * ({@code assam_population_demographics.geojson}).
 * <p>
 * Complements Layer 11 (Building Footprints & Settlement Boundaries) by distinguishing
 * between a 200-resident rural hamlet and an 8,000-resident settlement at similar
 * boundary distances, while strictly separating <b>physically displaced population</b>
 * (requiring Direct RoW building/parcel acquisition) from <b>buffer-exposed population</b>
 * (residents within 100 m, 250 m, 500 m, and 1,000 m influence buffers).
 */
@Component
public class DemographicVectorIndex {

    private static final double BUCKET_SIZE_METERS = 1500.0;
    private static final double SQ_METERS_PER_HECTARE = 10_000.0;
    private static final double SECONDARY_BUFFER_HALF_WIDTH_METERS = 500.0;
    private static final double CATCHMENT_BUFFER_HALF_WIDTH_METERS = 1000.0;
    private static final int BUFFER_QUADRANT_SEGMENTS = 8;

    private final CoordinateProjection projection;
    private final String demographicsGeoJsonPath;
    private final ObjectMapper objectMapper;
    private final GeometryFactory geometryFactory;

    private boolean initialized = false;
    private final List<DemographicTractPolygon> tracts = new ArrayList<>();
    private final Map<String, DemographicTractPolygon> tractById = new HashMap<>();
    private final Map<String, DemographicTractPolygon> tractBySettlementId = new HashMap<>();
    private final Map<Long, List<DemographicTractPolygon>> tractBuckets = new HashMap<>();

    @Autowired
    public DemographicVectorIndex(
            CoordinateProjection projection,
            @Value("${marg.demographic.geojson-path:data/processed/demographics/assam_population_demographics.geojson}")
            String demographicsGeoJsonPath,
            ObjectMapper objectMapper) {
        this.projection = projection;
        this.demographicsGeoJsonPath = demographicsGeoJsonPath;
        this.objectMapper = objectMapper;
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 32646);
    }

    public DemographicVectorIndex(
            CoordinateProjection projection,
            String demographicsGeoJsonPath) {
        this(projection, demographicsGeoJsonPath, new ObjectMapper());
    }

    private synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        loadDemographicTracts();
        initialized = true;
    }

    private void loadDemographicTracts() {
        File file = new File(demographicsGeoJsonPath);
        if (!file.exists()) {
            throw new IllegalStateException(
                    "Demographics GeoJSON does not exist: " + file.getAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode features = root.get("features");
            if (features == null || !features.isArray()) {
                throw new IllegalStateException("Invalid FeatureCollection in " + demographicsGeoJsonPath);
            }
            for (JsonNode feat : features) {
                JsonNode p = feat.get("properties");
                String tractId = p.get("tractId").asString();
                String settlementId = p.get("settlementId").asString();
                String censusCode = p.get("censusCode").asString();
                String name = p.get("name").asString();
                SettlementClassification classification =
                        SettlementClassification.valueOf(p.get("settlementClassification").asString());
                String district = p.get("district").asString("Karbi Anglong");
                int totalPop = p.get("totalPopulation").asInt(500);
                int ruralPop = p.get("ruralPopulation").asInt(0);
                int urbanPop = p.get("urbanPopulation").asInt(0);
                int households = p.get("householdsCount").asInt(100);
                double avgHhSize = p.get("averageHouseholdSize").asDouble(5.0);
                double areaHa = p.get("polygonAreaHectares").asDouble(10.0);
                double densitySqKm = p.get("populationDensityPerSqKm").asDouble(500.0);
                double densityHa = p.get("populationDensityPerHectare").asDouble(5.0);
                PopulationDensityClass densityClass =
                        PopulationDensityClass.valueOf(p.get("populationDensityClass").asString());

                double childrenShare = p.get("children0To14Share").asDouble(0.30);
                double workingShare = p.get("workingAge15To59Share").asDouble(0.61);
                double elderlyShare = p.get("elderly60PlusShare").asDouble(0.09);
                double depRatio = p.get("vulnerableAgeDependencyRatio").asDouble(0.64);
                double stScShare = p.get("scheduledTribeAndCastesShare").asDouble(0.65);
                double literacyRate = p.get("literacyRate").asDouble(0.74);
                double agriShare = p.get("agriculturalAndPrimaryLivelihoodShare").asDouble(0.60);
                double svi = p.get("socialVulnerabilityIndex").asDouble(0.62);
                DemographicVulnerabilityTier vulnTier =
                        DemographicVulnerabilityTier.valueOf(p.get("vulnerabilityTier").asString());

                double corePopShare = p.get("corePopulationShare").asDouble(0.65);
                double fringeDispersionRad = p.get("fringeDispersionRadiusMeters").asDouble(350.0);
                double coreRad = p.get("coreRadiusMeters").asDouble(220.0);
                int mappedBlds = p.get("mappedBuildingFootprintCount").asInt(12);
                int resBlds = p.get("residentialBuildingCount").asInt(8);

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
                if (jtsCoords.length >= 4 && !jtsCoords[0].equals2D(jtsCoords[jtsCoords.length - 1])) {
                    Coordinate[] closed = new Coordinate[jtsCoords.length + 1];
                    System.arraycopy(jtsCoords, 0, closed, 0, jtsCoords.length);
                    closed[jtsCoords.length] = new Coordinate(jtsCoords[0].x, jtsCoords[0].y);
                    jtsCoords = closed;
                }
                LinearRing lr = geometryFactory.createLinearRing(jtsCoords);
                Polygon jtsCorePoly = geometryFactory.createPolygon(lr);

                Point centerPt = geometryFactory.createPoint(new Coordinate(centroidMetric.x(), centroidMetric.y()));
                Geometry jtsDispersionGeom = BufferOp.bufferOp(
                        centerPt, fringeDispersionRad, BUFFER_QUADRANT_SEGMENTS);

                DemographicTractPolygon tract = new DemographicTractPolygon(
                        tractId,
                        settlementId,
                        censusCode,
                        name,
                        classification,
                        district,
                        totalPop,
                        ruralPop,
                        urbanPop,
                        households,
                        avgHhSize,
                        areaHa,
                        densitySqKm,
                        densityHa,
                        densityClass,
                        childrenShare,
                        workingShare,
                        elderlyShare,
                        depRatio,
                        stScShare,
                        literacyRate,
                        agriShare,
                        svi,
                        vulnTier,
                        corePopShare,
                        fringeDispersionRad,
                        coreRad,
                        mappedBlds,
                        resBlds,
                        centroid,
                        centroidMetric,
                        List.copyOf(geoRing),
                        List.copyOf(metricRing),
                        jtsCorePoly,
                        jtsDispersionGeom,
                        minX,
                        minY,
                        maxX,
                        maxY);

                tracts.add(tract);
                tractById.put(tractId, tract);
                tractBySettlementId.put(settlementId, tract);
                insertTractIntoBuckets(tract);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load population demographics GeoJSON: " + demographicsGeoJsonPath, e);
        }
    }

    private void insertTractIntoBuckets(DemographicTractPolygon t) {
        double reach = Math.max(t.fringeDispersionRadiusMeters(), SECONDARY_BUFFER_HALF_WIDTH_METERS) + 100.0;
        int minBx = bucketCoord(t.minX() - reach);
        int maxBx = bucketCoord(t.maxX() + reach);
        int minBy = bucketCoord(t.minY() - reach);
        int maxBy = bucketCoord(t.maxY() + reach);
        for (int bx = minBx; bx <= maxBx; bx++) {
            for (int by = minBy; by <= maxBy; by++) {
                tractBuckets
                        .computeIfAbsent(bucketKey(bx, by), k -> new ArrayList<>())
                        .add(t);
            }
        }
    }

    private static int bucketCoord(double meters) {
        return (int) Math.floor(meters / BUCKET_SIZE_METERS);
    }

    private static long bucketKey(int bx, int by) {
        return (((long) bx) << 32) ^ (by & 0xffffffffL);
    }

    public List<DemographicTractPolygon> allTracts() {
        ensureInitialized();
        return List.copyOf(tracts);
    }

    public DemographicTractPolygon tractForSettlementId(String settlementId) {
        ensureInitialized();
        return tractBySettlementId.get(settlementId);
    }

    /**
     * Fast metric step evaluation for A* search: evaluates demographic exposure across
     * the Direct RoW, Primary Environmental Buffer ({@code primaryBufferMeters}, e.g. 250 m),
     * and Secondary Social Influence Buffer ({@code secondaryBufferMeters}, e.g. 500 m).
     * <p>
     * Explicitly differentiates a 200-resident hamlet from an 8,000-resident settlement
     * even when both are at the same lateral distance from the alignment.
     */
    public StepDemographicExposure evaluateStepDemographicExposureMetric(
            MetricPoint a,
            MetricPoint b,
            double halfRowWidthMeters,
            double primaryBufferMeters,
            double secondaryBufferMeters) {
        ensureInitialized();

        double midX = 0.5 * (a.x() + b.x());
        double midY = 0.5 * (a.y() + b.y());
        int bx = bucketCoord(midX);
        int by = bucketCoord(midY);

        List<DemographicTractPolygon> candidates = tractBuckets.get(bucketKey(bx, by));
        if (candidates == null || candidates.isEmpty()) {
            return StepDemographicExposure.NONE;
        }

        double directRowPopUnits = 0.0;
        double primaryBufferPopUnits = 0.0;
        double secondaryBufferPopUnits = 0.0;
        DemographicTractPolygon dominantTract = null;
        double maxTractWeight = 0.0;

        for (DemographicTractPolygon t : candidates) {
            double centroidDist = pointToSegmentDistance(
                    t.centroidMetric().x(),
                    t.centroidMetric().y(),
                    a.x(),
                    a.y(),
                    b.x(),
                    b.y());
            double maxReach = t.coreRadiusMeters() + secondaryBufferMeters;
            if (centroidDist > maxReach) {
                continue;
            }

            double approxBoundaryDist = Math.max(0.0, centroidDist - t.coreRadiusMeters());
            double popThousands = t.totalPopulation() / 1000.0;
            double vulnFactor = 1.0 + 0.50 * t.socialVulnerabilityIndex();
            double weightedPopUnits = popThousands * vulnFactor;

            double tractContribution = 0.0;
            if (approxBoundaryDist <= halfRowWidthMeters
                    || pointInPolygon(midX, midY, t.metricRing())) {
                directRowPopUnits += weightedPopUnits;
                tractContribution = weightedPopUnits * 3.0;
            } else if (approxBoundaryDist <= primaryBufferMeters) {
                double decay = 1.0 - (approxBoundaryDist - halfRowWidthMeters)
                        / Math.max(1.0, primaryBufferMeters - halfRowWidthMeters);
                double contrib = weightedPopUnits * Math.max(0.15, decay);
                primaryBufferPopUnits += contrib;
                tractContribution = contrib * 1.5;
            } else if (approxBoundaryDist <= secondaryBufferMeters) {
                double decay = 1.0 - (approxBoundaryDist - primaryBufferMeters)
                        / Math.max(1.0, secondaryBufferMeters - primaryBufferMeters);
                double contrib = weightedPopUnits * 0.45 * Math.max(0.05, decay);
                secondaryBufferPopUnits += contrib;
                tractContribution = contrib;
            }

            if (tractContribution > maxTractWeight) {
                maxTractWeight = tractContribution;
                dominantTract = t;
            }
        }

        if (dominantTract == null) {
            return StepDemographicExposure.NONE;
        }
        return new StepDemographicExposure(
                directRowPopUnits,
                primaryBufferPopUnits,
                secondaryBufferPopUnits,
                dominantTract);
    }

    /**
     * Evaluates the proposed 2D highway corridor against all 120 demographic tracts,
     * calculating:
     * <ul>
     *   <li>Population within the proposed Direct RoW (60 m Expressway / 45 m NH).</li>
     *   <li>Estimated directly displaced population & households (requiring building/parcel acquisition),
     *       explicitly separated from non-displaced buffer-exposed population.</li>
     *   <li>Population within selected concentric influence buffers (100 m, 250 m, 500 m, 1,000 m).</li>
     *   <li>Rural (village-level) vs. urban population exposure.</li>
     *   <li>Demographic & age cohort exposure (children 0-14, working-age 15-59, elderly 60+,
     *       Scheduled Tribe / Caste communities, agricultural livelihood households).</li>
     *   <li>Chainage-ordered demographic exposure mitigation zones.</li>
     * </ul>
     */
    public RouteDemographicImpact evaluateRouteDemographicImpact(
            List<GeoPoint> mainline,
            RightOfWayCorridorEngine rowEngine,
            FacilityMode mode,
            BuildingFootprintVectorIndex buildingIndex) {
        RightOfWayCorridorEngine.CorridorFootprint corridor =
                rowEngine.buildCorridorFootprint(mainline, mode);
        return evaluateCorridorFootprintDemographicImpact(mainline, corridor, buildingIndex);
    }

    public RouteDemographicImpact evaluateCorridorFootprintDemographicImpact(
            List<GeoPoint> mainline,
            RightOfWayCorridorEngine.CorridorFootprint corridor,
            BuildingFootprintVectorIndex buildingIndex) {
        ensureInitialized();

        Geometry directGeom = corridor.combinedDirectRowMetric();
        Geometry constGeom = corridor.constructionZoneMetric();
        Geometry env250Geom = corridor.environmentalBufferMetric();
        Geometry centerlineGeom = corridor.combinedCenterlinesMetric();
        Geometry rowBoundary = directGeom.getBoundary();

        BufferParameters bufParams = new BufferParameters(
                BUFFER_QUADRANT_SEGMENTS,
                BufferParameters.CAP_ROUND,
                BufferParameters.JOIN_ROUND,
                BufferParameters.DEFAULT_MITRE_LIMIT);
        Geometry secondary500Geom = BufferOp.bufferOp(
                centerlineGeom, SECONDARY_BUFFER_HALF_WIDTH_METERS, bufParams);
        Geometry catchment1000Geom = BufferOp.bufferOp(
                centerlineGeom, CATCHMENT_BUFFER_HALF_WIDTH_METERS, bufParams);

        PreparedGeometry prepDirect = PreparedGeometryFactory.prepare(directGeom);
        PreparedGeometry prepConst = PreparedGeometryFactory.prepare(constGeom);
        PreparedGeometry prepEnv250 = PreparedGeometryFactory.prepare(env250Geom);
        PreparedGeometry prepSec500 = PreparedGeometryFactory.prepare(secondary500Geom);
        PreparedGeometry prepCatch1000 = PreparedGeometryFactory.prepare(catchment1000Geom);

        // If BuildingFootprintVectorIndex is available, count actual residential/commercial
        // structures inside Direct RoW per settlement to compute structure-based displacement
        Map<String, Integer> directRowResBuildingsByStl = new HashMap<>();
        Map<String, Integer> directRowComBuildingsByStl = new HashMap<>();
        if (buildingIndex != null) {
            var bldImpact = buildingIndex.evaluateCorridorFootprintImpact(mainline, corridor);
            for (var abr : bldImpact.affectedBuildings()) {
                if (abr.impactZone() == in.marg.row.RowImpactZone.DIRECT_ROW_FOOTPRINT) {
                    if (abr.buildingType() == BuildingType.RESIDENTIAL) {
                        directRowResBuildingsByStl.merge(
                                abr.settlementId(), Math.max(1, abr.floors()), Integer::sum);
                    } else if (abr.buildingType() == BuildingType.COMMERCIAL) {
                        directRowComBuildingsByStl.merge(abr.settlementId(), 1, Integer::sum);
                    }
                }
            }
        }

        double popInDirectRow = 0.0;
        double ruralPopInDirectRow = 0.0;
        double urbanPopInDirectRow = 0.0;

        double estimatedDisplacedPop = 0.0;
        double estimatedDisplacedHouseholds = 0.0;

        double popIn100mBuffer = 0.0;
        double ruralPopIn100mBuffer = 0.0;
        double urbanPopIn100mBuffer = 0.0;

        double popIn250mBuffer = 0.0;
        double ruralPopIn250mBuffer = 0.0;
        double urbanPopIn250mBuffer = 0.0;

        double popIn500mBuffer = 0.0;
        double ruralPopIn500mBuffer = 0.0;
        double urbanPopIn500mBuffer = 0.0;

        double popIn1000mBuffer = 0.0;
        double ruralPopIn1000mBuffer = 0.0;
        double urbanPopIn1000mBuffer = 0.0;

        double children250 = 0.0;
        double working250 = 0.0;
        double elderly250 = 0.0;
        double stSc250 = 0.0;
        double agri250 = 0.0;

        double children500 = 0.0;
        double elderly500 = 0.0;
        double stSc500 = 0.0;

        int tractsInDirectRowCount = 0;
        int tractsIn250mBufferCount = 0;
        int tractsIn500mBufferCount = 0;
        int highPopSettlementsWithin500mCount = 0;
        int lowPopHamletsWithin500mCount = 0;

        double weightedExposureIndex = 0.0;
        List<AffectedDemographicTract> affectedTracts = new ArrayList<>();

        for (DemographicTractPolygon t : tracts) {
            Geometry dispGeom = t.jtsDispersionPolygonMetric();
            if (!prepCatch1000.intersects(dispGeom) && !prepCatch1000.intersects(t.jtsPolygonMetric())) {
                continue;
            }

            Polygon corePoly = t.jtsPolygonMetric();
            double coreAreaM2 = Math.max(1000.0, corePoly.getArea());
            double dispAreaM2 = Math.max(coreAreaM2 + 1000.0, dispGeom.getArea());
            double haloRingAreaM2 = Math.max(1000.0, dispAreaM2 - coreAreaM2);

            double corePop = t.totalPopulation() * t.corePopulationShare();
            double haloPop = t.totalPopulation() * (1.0 - t.corePopulationShare());

            double popDirect = computeZonePopulation(
                    prepDirect, directGeom, corePoly, dispGeom, coreAreaM2, haloRingAreaM2, corePop, haloPop);
            double pop100 = Math.max(popDirect, computeZonePopulation(
                    prepConst, constGeom, corePoly, dispGeom, coreAreaM2, haloRingAreaM2, corePop, haloPop));
            double pop250 = Math.max(pop100, computeZonePopulation(
                    prepEnv250, env250Geom, corePoly, dispGeom, coreAreaM2, haloRingAreaM2, corePop, haloPop));
            double pop500 = Math.max(pop250, computeZonePopulation(
                    prepSec500, secondary500Geom, corePoly, dispGeom, coreAreaM2, haloRingAreaM2, corePop, haloPop));
            double pop1000 = Math.max(pop500, computeZonePopulation(
                    prepCatch1000, catchment1000Geom, corePoly, dispGeom, coreAreaM2, haloRingAreaM2, corePop, haloPop));

            if (pop1000 < 0.5) {
                continue;
            }

            // Important distinction: Population within a buffer != Displaced population.
            // Actual displacement requires physical structure demolition inside Direct RoW
            // (from 2D building footprints when available, or core parcel acquisition fallback).
            int resUnitsInRow = directRowResBuildingsByStl.getOrDefault(t.settlementId(), 0);
            int comUnitsInRow = directRowComBuildingsByStl.getOrDefault(t.settlementId(), 0);
            double displacedHhForTract = resUnitsInRow + (0.35 * comUnitsInRow);
            if (buildingIndex == null) {
                double coreIntersectDirectM2 = prepDirect.intersects(corePoly)
                        ? directGeom.intersection(corePoly).getArea()
                        : 0.0;
                if (coreIntersectDirectM2 > 10.0) {
                    double parcelAcqHh = (corePop * (coreIntersectDirectM2 / coreAreaM2) * 0.25)
                            / Math.max(1.0, t.averageHouseholdSize());
                    displacedHhForTract = Math.max(displacedHhForTract, parcelAcqHh);
                }
            }
            double displacedPopForTract = displacedHhForTract * t.averageHouseholdSize();

            double ruralRatio = t.totalPopulation() > 0
                    ? (double) t.ruralPopulation() / t.totalPopulation()
                    : 1.0;
            double urbanRatio = 1.0 - ruralRatio;

            popInDirectRow += popDirect;
            ruralPopInDirectRow += popDirect * ruralRatio;
            urbanPopInDirectRow += popDirect * urbanRatio;

            estimatedDisplacedHouseholds += displacedHhForTract;
            estimatedDisplacedPop += displacedPopForTract;

            popIn100mBuffer += pop100;
            ruralPopIn100mBuffer += pop100 * ruralRatio;
            urbanPopIn100mBuffer += pop100 * urbanRatio;

            popIn250mBuffer += pop250;
            ruralPopIn250mBuffer += pop250 * ruralRatio;
            urbanPopIn250mBuffer += pop250 * urbanRatio;

            popIn500mBuffer += pop500;
            ruralPopIn500mBuffer += pop500 * ruralRatio;
            urbanPopIn500mBuffer += pop500 * urbanRatio;

            popIn1000mBuffer += pop1000;
            ruralPopIn1000mBuffer += pop1000 * ruralRatio;
            urbanPopIn1000mBuffer += pop1000 * urbanRatio;

            children250 += pop250 * t.children0To14Share();
            working250 += pop250 * t.workingAge15To59Share();
            elderly250 += pop250 * t.elderly60PlusShare();
            stSc250 += pop250 * t.scheduledTribeAndCastesShare();
            agri250 += pop250 * t.agriculturalAndPrimaryLivelihoodShare();

            children500 += pop500 * t.children0To14Share();
            elderly500 += pop500 * t.elderly60PlusShare();
            stSc500 += pop500 * t.scheduledTribeAndCastesShare();

            double distCenterline = centerlineGeom.distance(corePoly);
            double distRowEdge = prepDirect.intersects(corePoly) ? 0.0 : rowBoundary.distance(corePoly);

            if (popDirect >= 1.0 || prepDirect.intersects(corePoly)) {
                tractsInDirectRowCount++;
            }
            if (pop250 >= 1.0 || distCenterline <= 250.0) {
                tractsIn250mBufferCount++;
            }
            if (pop500 >= 1.0 || distCenterline <= 500.0) {
                tractsIn500mBufferCount++;
                if (t.totalPopulation() >= 1000) {
                    highPopSettlementsWithin500mCount++;
                } else if (t.totalPopulation() < 500) {
                    lowPopHamletsWithin500mCount++;
                }
            }

            double tractExposureScore =
                    (popDirect * 3.5 + (pop250 - popDirect) * 1.2 + (pop500 - pop250) * 0.4)
                            * (1.0 + 0.5 * t.socialVulnerabilityIndex())
                            / 100.0;
            weightedExposureIndex += tractExposureScore;

            if (pop500 >= 1.0 || distCenterline <= 500.0) {
                affectedTracts.add(new AffectedDemographicTract(
                        t.tractId(),
                        t.settlementId(),
                        t.censusCode(),
                        t.name(),
                        t.settlementClassification(),
                        t.district(),
                        t.totalPopulation(),
                        t.ruralPopulation(),
                        t.urbanPopulation(),
                        t.householdsCount(),
                        t.populationDensityPerSqKm(),
                        t.populationDensityClass(),
                        t.socialVulnerabilityIndex(),
                        t.vulnerabilityTier(),
                        (int) Math.round(popDirect),
                        (int) Math.round(displacedPopForTract),
                        (int) Math.round(displacedHhForTract),
                        (int) Math.round(pop100),
                        (int) Math.round(pop250),
                        (int) Math.round(pop500),
                        (int) Math.round(pop1000),
                        (int) Math.round(pop250 * (t.children0To14Share() + t.elderly60PlusShare())),
                        (int) Math.round(pop250 * t.scheduledTribeAndCastesShare()),
                        round(distCenterline),
                        round(distRowEdge),
                        round(tractExposureScore)));
            }
        }

        affectedTracts.sort(Comparator.comparingInt(AffectedDemographicTract::populationWithin250mBuffer).reversed());

        List<RouteDemographicExposureZone> exposureZones =
                buildChainageExposureZones(mainline, affectedTracts);

        int roundedPopDirect = (int) Math.round(popInDirectRow);
        int roundedDisplacedPop = (int) Math.round(estimatedDisplacedPop);
        int roundedDisplacedHh = (int) Math.round(estimatedDisplacedHouseholds);
        int roundedPop100 = (int) Math.round(popIn100mBuffer);
        int roundedPop250 = (int) Math.round(popIn250mBuffer);
        int roundedPop500 = (int) Math.round(popIn500mBuffer);
        int roundedPop1000 = (int) Math.round(popIn1000mBuffer);

        int nonDisplacedIn250m = Math.max(0, roundedPop250 - roundedDisplacedPop);
        int nonDisplacedIn500m = Math.max(0, roundedPop500 - roundedDisplacedPop);

        return new RouteDemographicImpact(
                tracts.size(),
                tractsInDirectRowCount,
                tractsIn250mBufferCount,
                tractsIn500mBufferCount,
                highPopSettlementsWithin500mCount,
                lowPopHamletsWithin500mCount,
                roundedPopDirect,
                (int) Math.round(ruralPopInDirectRow),
                (int) Math.round(urbanPopInDirectRow),
                roundedDisplacedPop,
                roundedDisplacedHh,
                roundedPop100,
                (int) Math.round(ruralPopIn100mBuffer),
                (int) Math.round(urbanPopIn100mBuffer),
                roundedPop250,
                (int) Math.round(ruralPopIn250mBuffer),
                (int) Math.round(urbanPopIn250mBuffer),
                nonDisplacedIn250m,
                roundedPop500,
                (int) Math.round(ruralPopIn500mBuffer),
                (int) Math.round(urbanPopIn500mBuffer),
                nonDisplacedIn500m,
                roundedPop1000,
                (int) Math.round(ruralPopIn1000mBuffer),
                (int) Math.round(urbanPopIn1000mBuffer),
                (int) Math.round(children250),
                (int) Math.round(working250),
                (int) Math.round(elderly250),
                (int) Math.round(children250 + elderly250),
                (int) Math.round(stSc250),
                (int) Math.round(agri250),
                (int) Math.round(children500 + elderly500),
                (int) Math.round(stSc500),
                round(weightedExposureIndex),
                "Population within an influence buffer (100 m / 250 m / 500 m) represents residents "
                        + "exposed to noise, air quality, and community severance impacts — NOT physically "
                        + "displaced population. Actual displacement requires Direct RoW building footprint "
                        + "and land-parcel acquisition.",
                List.copyOf(affectedTracts),
                exposureZones);
    }

    /**
     * Compares two candidate alignments (e.g., Route A passing near a 200-resident settlement
     * vs. Route B passing near an 8,000-resident settlement, or 11-Layer vs. 12-Layer).
     */
    public CandidateDemographicComparison compareCandidateAlignments(
            String candidateAName,
            RouteDemographicImpact candidateA,
            String candidateBName,
            RouteDemographicImpact candidateB) {

        int deltaDirectRowPop = candidateB.populationWithinDirectRow() - candidateA.populationWithinDirectRow();
        int deltaDisplacedPop = candidateB.estimatedDirectlyDisplacedPopulation()
                - candidateA.estimatedDirectlyDisplacedPopulation();
        int delta100mPop = candidateB.populationWithin100mConstructionBuffer()
                - candidateA.populationWithin100mConstructionBuffer();
        int delta250mPop = candidateB.populationWithin250mEnvironmentalBuffer()
                - candidateA.populationWithin250mEnvironmentalBuffer();
        int delta500mPop = candidateB.populationWithin500mSecondaryBuffer()
                - candidateA.populationWithin500mSecondaryBuffer();
        int deltaVulnerableAge250m = candidateB.vulnerableAgePopulationExposedWithin250m()
                - candidateA.vulnerableAgePopulationExposedWithin250m();
        int deltaStSc250m = candidateB.scheduledTribeAndCastesExposedWithin250m()
                - candidateA.scheduledTribeAndCastesExposedWithin250m();

        double pctChange250m = candidateA.populationWithin250mEnvironmentalBuffer() > 0
                ? ((double) delta250mPop / candidateA.populationWithin250mEnvironmentalBuffer()) * 100.0
                : 0.0;
        double pctChange500m = candidateA.populationWithin500mSecondaryBuffer() > 0
                ? ((double) delta500mPop / candidateA.populationWithin500mSecondaryBuffer()) * 100.0
                : 0.0;

        String preferred = candidateB.weightedPopulationExposureIndex() <= candidateA.weightedPopulationExposureIndex()
                ? candidateBName
                : candidateAName;

        return new CandidateDemographicComparison(
                candidateAName,
                candidateBName,
                preferred,
                candidateA.populationWithinDirectRow(),
                candidateB.populationWithinDirectRow(),
                deltaDirectRowPop,
                candidateA.estimatedDirectlyDisplacedPopulation(),
                candidateB.estimatedDirectlyDisplacedPopulation(),
                deltaDisplacedPop,
                candidateA.populationWithin100mConstructionBuffer(),
                candidateB.populationWithin100mConstructionBuffer(),
                delta100mPop,
                candidateA.populationWithin250mEnvironmentalBuffer(),
                candidateB.populationWithin250mEnvironmentalBuffer(),
                delta250mPop,
                round(pctChange250m),
                candidateA.populationWithin500mSecondaryBuffer(),
                candidateB.populationWithin500mSecondaryBuffer(),
                delta500mPop,
                round(pctChange500m),
                candidateA.vulnerableAgePopulationExposedWithin250m(),
                candidateB.vulnerableAgePopulationExposedWithin250m(),
                deltaVulnerableAge250m,
                candidateA.scheduledTribeAndCastesExposedWithin250m(),
                candidateB.scheduledTribeAndCastesExposedWithin250m(),
                deltaStSc250m,
                candidateA.highPopulationSettlementsWithin500mCount(),
                candidateB.highPopulationSettlementsWithin500mCount(),
                candidateA.lowPopulationHamletsWithin500mCount(),
                candidateB.lowPopulationHamletsWithin500mCount(),
                candidateA.weightedPopulationExposureIndex(),
                candidateB.weightedPopulationExposureIndex());
    }

    private static double computeZonePopulation(
            PreparedGeometry prepZone,
            Geometry zoneGeom,
            Polygon corePoly,
            Geometry dispGeom,
            double coreAreaM2,
            double haloRingAreaM2,
            double corePop,
            double haloPop) {
        double pop = 0.0;
        double coreInterM2 = 0.0;
        if (prepZone.intersects(corePoly)) {
            coreInterM2 = Math.min(coreAreaM2, zoneGeom.intersection(corePoly).getArea());
            pop += corePop * (coreInterM2 / coreAreaM2);
        }
        if (prepZone.intersects(dispGeom)) {
            double totalDispInterM2 = Math.min(coreAreaM2 + haloRingAreaM2, zoneGeom.intersection(dispGeom).getArea());
            double haloInterM2 = Math.max(0.0, Math.min(haloRingAreaM2, totalDispInterM2 - coreInterM2));
            pop += haloPop * (haloInterM2 / haloRingAreaM2);
        }
        return pop;
    }

    private List<RouteDemographicExposureZone> buildChainageExposureZones(
            List<GeoPoint> mainline,
            List<AffectedDemographicTract> affectedTracts) {
        if (mainline == null || mainline.size() < 2 || affectedTracts.isEmpty()) {
            return List.of();
        }
        List<RouteDemographicExposureZone> zones = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (AffectedDemographicTract adt : affectedTracts) {
            if (adt.populationWithin250mBuffer() < 15 && adt.populationWithin500mBuffer() < 60) {
                continue;
            }
            if (!seen.add(adt.tractId())) {
                continue;
            }
            DemographicTractPolygon poly = tractById.get(adt.tractId());
            if (poly == null) {
                continue;
            }
            ChainageProjection proj = projectOntoMainline(mainline, poly.centroidMetric());
            String mitigation;
            if (adt.estimatedDirectlyDisplacedPopulation() > 0) {
                mitigation = "RFCTLARR_2013_REHABILITATION_RESETTLEMENT_AND_SERVICE_ROAD_VIADUCT";
            } else if (adt.populationWithin250mBuffer() >= 500) {
                mitigation = "HIGH_DENSITY_ACOUSTIC_BARRIER_PEDESTRIAN_OVERPASS_AND_AIR_QUALITY_BUFFER";
            } else if (adt.populationWithin250mBuffer() >= 50) {
                mitigation = adt.vulnerabilityTier().defaultSocialMitigationMeasure();
            } else {
                mitigation = "SECONDARY_BUFFER_COMMUNITY_ACCESS_SUBWAY_AND_VEGETATIVE_SCREEN";
            }

            zones.add(new RouteDemographicExposureZone(
                    adt.tractId(),
                    adt.settlementId(),
                    adt.name(),
                    adt.settlementClassification(),
                    adt.district(),
                    proj.closestPoint(),
                    round(proj.chainageKm()),
                    adt.totalSettlementPopulation(),
                    adt.populationDensityPerSqKm(),
                    adt.populationDensityClass(),
                    adt.socialVulnerabilityIndex(),
                    adt.vulnerabilityTier(),
                    adt.populationWithinDirectRow(),
                    adt.estimatedDirectlyDisplacedPopulation(),
                    adt.populationWithin100mBuffer(),
                    adt.populationWithin250mBuffer(),
                    adt.populationWithin500mBuffer(),
                    adt.vulnerableAgeExposedWithin250m(),
                    adt.scheduledTribeAndCastesExposedWithin250m(),
                    adt.distanceToCenterlineMeters(),
                    adt.distanceToRowEdgeMeters(),
                    mitigation,
                    adt.exposureScore()));
        }
        zones.sort(Comparator.comparingDouble(RouteDemographicExposureZone::chainageKm));
        return List.copyOf(zones);
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

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private record ChainageProjection(GeoPoint closestPoint, double chainageKm, double distanceMeters) {}

    public record DemographicTractPolygon(
            String tractId,
            String settlementId,
            String censusCode,
            String name,
            SettlementClassification settlementClassification,
            String district,
            int totalPopulation,
            int ruralPopulation,
            int urbanPopulation,
            int householdsCount,
            double averageHouseholdSize,
            double polygonAreaHectares,
            double populationDensityPerSqKm,
            double populationDensityPerHectare,
            PopulationDensityClass populationDensityClass,
            double children0To14Share,
            double workingAge15To59Share,
            double elderly60PlusShare,
            double vulnerableAgeDependencyRatio,
            double scheduledTribeAndCastesShare,
            double literacyRate,
            double agriculturalAndPrimaryLivelihoodShare,
            double socialVulnerabilityIndex,
            DemographicVulnerabilityTier vulnerabilityTier,
            double corePopulationShare,
            double fringeDispersionRadiusMeters,
            double coreRadiusMeters,
            int mappedBuildingFootprintCount,
            int residentialBuildingCount,
            GeoPoint centroid,
            MetricPoint centroidMetric,
            List<GeoPoint> exteriorRing,
            List<MetricPoint> metricRing,
            Polygon jtsPolygonMetric,
            Geometry jtsDispersionPolygonMetric,
            double minX,
            double minY,
            double maxX,
            double maxY
    ) {}

    public record StepDemographicExposure(
            double directRowWeightedPopUnits,
            double primaryBufferWeightedPopUnits,
            double secondaryBufferWeightedPopUnits,
            DemographicTractPolygon dominantTract
    ) {
        public static final StepDemographicExposure NONE =
                new StepDemographicExposure(0.0, 0.0, 0.0, null);

        public boolean hasExposure() {
            return dominantTract != null
                    && (directRowWeightedPopUnits > 0.0
                    || primaryBufferWeightedPopUnits > 0.0
                    || secondaryBufferWeightedPopUnits > 0.0);
        }
    }

    public record AffectedDemographicTract(
            String tractId,
            String settlementId,
            String censusCode,
            String name,
            SettlementClassification settlementClassification,
            String district,
            int totalSettlementPopulation,
            int ruralSettlementPopulation,
            int urbanSettlementPopulation,
            int householdsCount,
            double populationDensityPerSqKm,
            PopulationDensityClass populationDensityClass,
            double socialVulnerabilityIndex,
            DemographicVulnerabilityTier vulnerabilityTier,
            int populationWithinDirectRow,
            int estimatedDirectlyDisplacedPopulation,
            int estimatedDirectlyDisplacedHouseholds,
            int populationWithin100mBuffer,
            int populationWithin250mBuffer,
            int populationWithin500mBuffer,
            int populationWithin1000mBuffer,
            int vulnerableAgeExposedWithin250m,
            int scheduledTribeAndCastesExposedWithin250m,
            double distanceToCenterlineMeters,
            double distanceToRowEdgeMeters,
            double exposureScore
    ) {}

    public record RouteDemographicExposureZone(
            String tractId,
            String settlementId,
            String settlementName,
            SettlementClassification settlementClassification,
            String district,
            GeoPoint point,
            double chainageKm,
            int totalSettlementPopulation,
            double populationDensityPerSqKm,
            PopulationDensityClass populationDensityClass,
            double socialVulnerabilityIndex,
            DemographicVulnerabilityTier vulnerabilityTier,
            int populationWithinDirectRow,
            int estimatedDirectlyDisplacedPopulation,
            int populationWithin100mBuffer,
            int populationWithin250mBuffer,
            int populationWithin500mBuffer,
            int vulnerableAgeExposedWithin250m,
            int scheduledTribeAndCastesExposedWithin250m,
            double distanceToCenterlineMeters,
            double distanceToRowEdgeMeters,
            String recommendedSocialMitigationMeasure,
            double exposureCostIndex
    ) {}

    public record CandidateDemographicComparison(
            String candidateAName,
            String candidateBName,
            String preferredLowerExposureCandidate,
            int candidateAPopulationInDirectRow,
            int candidateBPopulationInDirectRow,
            int directRowPopulationDelta,
            int candidateAEstimatedDisplacedPopulation,
            int candidateBEstimatedDisplacedPopulation,
            int estimatedDisplacedPopulationDelta,
            int candidateAPopulationWithin100mBuffer,
            int candidateBPopulationWithin100mBuffer,
            int buffer100mPopulationDelta,
            int candidateAPopulationWithin250mBuffer,
            int candidateBPopulationWithin250mBuffer,
            int buffer250mPopulationDelta,
            double percentageChangeIn250mPopulationExposure,
            int candidateAPopulationWithin500mBuffer,
            int candidateBPopulationWithin500mBuffer,
            int buffer500mPopulationDelta,
            double percentageChangeIn500mPopulationExposure,
            int candidateAVulnerableAgeExposedWithin250m,
            int candidateBVulnerableAgeExposedWithin250m,
            int vulnerableAge250mDelta,
            int candidateAScheduledTribeAndCastesExposedWithin250m,
            int candidateBScheduledTribeAndCastesExposedWithin250m,
            int scheduledTribeAndCastes250mDelta,
            int candidateAHighPopSettlementsWithin500mCount,
            int candidateBHighPopSettlementsWithin500mCount,
            int candidateALowPopHamletsWithin500mCount,
            int candidateBLowPopHamletsWithin500mCount,
            double candidateAWeightedExposureIndex,
            double candidateBWeightedExposureIndex
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("candidateAName", candidateAName);
            map.put("candidateBName", candidateBName);
            map.put("preferredLowerExposureCandidate", preferredLowerExposureCandidate);
            map.put("candidateAPopulationInDirectRow", candidateAPopulationInDirectRow);
            map.put("candidateBPopulationInDirectRow", candidateBPopulationInDirectRow);
            map.put("directRowPopulationDelta", directRowPopulationDelta);
            map.put("candidateAEstimatedDisplacedPopulation", candidateAEstimatedDisplacedPopulation);
            map.put("candidateBEstimatedDisplacedPopulation", candidateBEstimatedDisplacedPopulation);
            map.put("estimatedDisplacedPopulationDelta", estimatedDisplacedPopulationDelta);
            map.put("candidateAPopulationWithin100mBuffer", candidateAPopulationWithin100mBuffer);
            map.put("candidateBPopulationWithin100mBuffer", candidateBPopulationWithin100mBuffer);
            map.put("buffer100mPopulationDelta", buffer100mPopulationDelta);
            map.put("candidateAPopulationWithin250mBuffer", candidateAPopulationWithin250mBuffer);
            map.put("candidateBPopulationWithin250mBuffer", candidateBPopulationWithin250mBuffer);
            map.put("buffer250mPopulationDelta", buffer250mPopulationDelta);
            map.put("percentageChangeIn250mPopulationExposure", percentageChangeIn250mPopulationExposure);
            map.put("candidateAPopulationWithin500mBuffer", candidateAPopulationWithin500mBuffer);
            map.put("candidateBPopulationWithin500mBuffer", candidateBPopulationWithin500mBuffer);
            map.put("buffer500mPopulationDelta", buffer500mPopulationDelta);
            map.put("percentageChangeIn500mPopulationExposure", percentageChangeIn500mPopulationExposure);
            map.put("candidateAVulnerableAgeExposedWithin250m", candidateAVulnerableAgeExposedWithin250m);
            map.put("candidateBVulnerableAgeExposedWithin250m", candidateBVulnerableAgeExposedWithin250m);
            map.put("vulnerableAge250mDelta", vulnerableAge250mDelta);
            map.put("candidateAScheduledTribeAndCastesExposedWithin250m",
                    candidateAScheduledTribeAndCastesExposedWithin250m);
            map.put("candidateBScheduledTribeAndCastesExposedWithin250m",
                    candidateBScheduledTribeAndCastesExposedWithin250m);
            map.put("scheduledTribeAndCastes250mDelta", scheduledTribeAndCastes250mDelta);
            map.put("candidateAHighPopSettlementsWithin500mCount", candidateAHighPopSettlementsWithin500mCount);
            map.put("candidateBHighPopSettlementsWithin500mCount", candidateBHighPopSettlementsWithin500mCount);
            map.put("candidateALowPopHamletsWithin500mCount", candidateALowPopHamletsWithin500mCount);
            map.put("candidateBLowPopHamletsWithin500mCount", candidateBLowPopHamletsWithin500mCount);
            map.put("candidateAWeightedExposureIndex", candidateAWeightedExposureIndex);
            map.put("candidateBWeightedExposureIndex", candidateBWeightedExposureIndex);
            return map;
        }
    }

    public record RouteDemographicImpact(
            int totalIndexedEnumerationTractsCount,
            int tractsIntersectingDirectRowCount,
            int tractsIntersecting250mEnvironmentalBufferCount,
            int tractsIntersecting500mSecondaryBufferCount,
            int highPopulationSettlementsWithin500mCount,
            int lowPopulationHamletsWithin500mCount,
            int populationWithinDirectRow,
            int ruralPopulationWithinDirectRow,
            int urbanPopulationWithinDirectRow,
            int estimatedDirectlyDisplacedPopulation,
            int estimatedDirectlyDisplacedHouseholds,
            int populationWithin100mConstructionBuffer,
            int ruralPopulationWithin100mConstructionBuffer,
            int urbanPopulationWithin100mConstructionBuffer,
            int populationWithin250mEnvironmentalBuffer,
            int ruralPopulationWithin250mEnvironmentalBuffer,
            int urbanPopulationWithin250mEnvironmentalBuffer,
            int nonDisplacedBufferExposedPopulationWithin250m,
            int populationWithin500mSecondaryBuffer,
            int ruralPopulationWithin500mSecondaryBuffer,
            int urbanPopulationWithin500mSecondaryBuffer,
            int nonDisplacedBufferExposedPopulationWithin500m,
            int populationWithin1000mCatchmentBuffer,
            int ruralPopulationWithin1000mCatchmentBuffer,
            int urbanPopulationWithin1000mCatchmentBuffer,
            int children0To14ExposedWithin250m,
            int workingAge15To59ExposedWithin250m,
            int elderly60PlusExposedWithin250m,
            int vulnerableAgePopulationExposedWithin250m,
            int scheduledTribeAndCastesExposedWithin250m,
            int agriculturalLivelihoodPopulationExposedWithin250m,
            int vulnerableAgePopulationExposedWithin500m,
            int scheduledTribeAndCastesExposedWithin500m,
            double weightedPopulationExposureIndex,
            String displacementVsBufferMethodologyNote,
            List<AffectedDemographicTract> affectedTracts,
            List<RouteDemographicExposureZone> exposureZones
    ) {
        public Map<String, Object> toSummaryMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalIndexedEnumerationTractsCount", totalIndexedEnumerationTractsCount);
            map.put("tractsIntersectingDirectRowCount", tractsIntersectingDirectRowCount);
            map.put("tractsIntersecting250mEnvironmentalBufferCount",
                    tractsIntersecting250mEnvironmentalBufferCount);
            map.put("tractsIntersecting500mSecondaryBufferCount",
                    tractsIntersecting500mSecondaryBufferCount);
            map.put("highPopulationSettlementsWithin500mCount", highPopulationSettlementsWithin500mCount);
            map.put("lowPopulationHamletsWithin500mCount", lowPopulationHamletsWithin500mCount);
            map.put("populationWithinDirectRow", populationWithinDirectRow);
            map.put("ruralPopulationWithinDirectRow", ruralPopulationWithinDirectRow);
            map.put("urbanPopulationWithinDirectRow", urbanPopulationWithinDirectRow);
            map.put("estimatedDirectlyDisplacedPopulation", estimatedDirectlyDisplacedPopulation);
            map.put("estimatedDirectlyDisplacedHouseholds", estimatedDirectlyDisplacedHouseholds);
            map.put("populationWithin100mConstructionBuffer", populationWithin100mConstructionBuffer);
            map.put("ruralPopulationWithin100mConstructionBuffer", ruralPopulationWithin100mConstructionBuffer);
            map.put("urbanPopulationWithin100mConstructionBuffer", urbanPopulationWithin100mConstructionBuffer);
            map.put("populationWithin250mEnvironmentalBuffer", populationWithin250mEnvironmentalBuffer);
            map.put("ruralPopulationWithin250mEnvironmentalBuffer", ruralPopulationWithin250mEnvironmentalBuffer);
            map.put("urbanPopulationWithin250mEnvironmentalBuffer", urbanPopulationWithin250mEnvironmentalBuffer);
            map.put("nonDisplacedBufferExposedPopulationWithin250m",
                    nonDisplacedBufferExposedPopulationWithin250m);
            map.put("populationWithin500mSecondaryBuffer", populationWithin500mSecondaryBuffer);
            map.put("ruralPopulationWithin500mSecondaryBuffer", ruralPopulationWithin500mSecondaryBuffer);
            map.put("urbanPopulationWithin500mSecondaryBuffer", urbanPopulationWithin500mSecondaryBuffer);
            map.put("nonDisplacedBufferExposedPopulationWithin500m",
                    nonDisplacedBufferExposedPopulationWithin500m);
            map.put("populationWithin1000mCatchmentBuffer", populationWithin1000mCatchmentBuffer);
            map.put("ruralPopulationWithin1000mCatchmentBuffer", ruralPopulationWithin1000mCatchmentBuffer);
            map.put("urbanPopulationWithin1000mCatchmentBuffer", urbanPopulationWithin1000mCatchmentBuffer);
            map.put("demographicCohortsExposedWithin250m", Map.of(
                    "children0To14Exposed", children0To14ExposedWithin250m,
                    "workingAge15To59Exposed", workingAge15To59ExposedWithin250m,
                    "elderly60PlusExposed", elderly60PlusExposedWithin250m,
                    "vulnerableAgeChildrenAndElderlyExposed", vulnerableAgePopulationExposedWithin250m,
                    "scheduledTribeAndCastesExposed", scheduledTribeAndCastesExposedWithin250m,
                    "agriculturalLivelihoodPopulationExposed", agriculturalLivelihoodPopulationExposedWithin250m));
            map.put("demographicCohortsExposedWithin500m", Map.of(
                    "vulnerableAgeChildrenAndElderlyExposed", vulnerableAgePopulationExposedWithin500m,
                    "scheduledTribeAndCastesExposed", scheduledTribeAndCastesExposedWithin500m));
            map.put("weightedPopulationExposureIndex", weightedPopulationExposureIndex);
            map.put("displacementVsBufferMethodologyNote", displacementVsBufferMethodologyNote);
            map.put("demographicExposureZonesCount", exposureZones.size());
            return map;
        }
    }
}
