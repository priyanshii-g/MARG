package in.marg.row;

import in.marg.building.BuildingDensityClass;
import in.marg.building.BuildingDensityRasterProvider;
import in.marg.building.BuildingFootprintVectorIndex;
import in.marg.config.RowCorridorProperties;
import in.marg.demographic.DemographicVectorIndex;
import in.marg.demographic.PopulationDensityClass;
import in.marg.demographic.PopulationDensityRasterProvider;
import in.marg.drainage.DrainageWetlandClass;
import in.marg.drainage.DrainageWetlandRasterProvider;
import in.marg.drainage.DrainageWetlandVectorIndex;
import in.marg.environment.ProtectedAreaRasterProvider;
import in.marg.geotech.GeotechHazardClass;
import in.marg.geotech.GeotechRasterProvider;
import in.marg.hydrology.WaterBodyClass;
import in.marg.hydrology.WaterBodyRasterProvider;
import in.marg.model.GeoPoint;
import in.marg.provider.LulcProvider;
import in.marg.routing.LulcClass;
import in.marg.settlement.SettlementExposureClass;
import in.marg.settlement.SettlementRasterProvider;
import in.marg.spatial.CoordinateProjection;
import in.marg.spatial.MetricPoint;
import in.marg.terrain.SlopeRasterProvider;
import in.marg.transport.FacilityMode;
import in.marg.utility.UtilityCorridorClass;
import in.marg.utility.UtilityNetworkVectorIndex;
import in.marg.utility.UtilityRasterProvider;
import in.marg.wildlife.WildlifeRasterProvider;
import in.marg.wildlife.WildlifeZoneClass;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2D Right-of-Way (RoW) Corridor Buffering, Polygon Overlay & Cross-Sectional Swath Impact Engine.
 * <p>
 * Upgrades MARG from 1D centerline sampling to full 2D metric polygon corridor geometry in
 * {@code EPSG:32646} (WGS 84 / UTM Zone 46N) using JTS:
 * <ul>
 *     <li><b>Direct RoW Acquisition Footprint:</b> Mainline buffered by IRC facility width
 *     (60 m for Expressway per IRC:SP:84/99, 45 m for National Highway per IRC:SP:73) +
 *     Dedicated Feeder Spurs (30 m per IRC:73) + Grade-Separated Interchange ramp footprints (150 m radius).</li>
 *     <li><b>Construction Clearance Zone:</b> 100 m half-width (200 m total) cut/fill & staging buffer.</li>
 *     <li><b>Environmental Influence Buffer:</b> 250 m half-width (500 m total) noise, air & severance zone.</li>
 *     <li><b>Vector Feature Overlay API:</b> Exact point-in-polygon, line-clipping, and polygon-area
 *     intersection for buildings, settlements, administrative units, wetlands, and heritage zones.</li>
 *     <li><b>2D Cross-Sectional Swath Raster Audit:</b> Samples left RoW edge, quarter-left, centerline,
 *     quarter-right, and right RoW edge to compute land-acquisition hectares by class and verify boundary clearance.</li>
 * </ul>
 */
@Component
public class RightOfWayCorridorEngine {

    private static final double SQ_METERS_PER_HECTARE = 10_000.0;
    private static final int BUFFER_QUADRANT_SEGMENTS = 8;

    private final CoordinateProjection projection;
    private final RowCorridorProperties properties;
    private final GeometryFactory geometryFactory;

    @Autowired
    public RightOfWayCorridorEngine(
            CoordinateProjection projection,
            RowCorridorProperties properties) {
        this.projection = projection;
        this.properties = properties != null ? properties : new RowCorridorProperties();
        this.geometryFactory = new GeometryFactory(new PrecisionModel(), 32646);
    }

    public RightOfWayCorridorEngine(CoordinateProjection projection) {
        this(projection, new RowCorridorProperties());
    }

    public RowCorridorProperties properties() {
        return properties;
    }

    /**
     * Builds the 2D metric corridor polygons for a mainline alignment (without spurs or interchanges).
     */
    public CorridorFootprint buildCorridorFootprint(
            List<GeoPoint> mainline,
            FacilityMode mode) {
        return buildCorridorFootprint(mainline, mode, List.of(), List.of());
    }

    /**
     * Builds the complete 2D metric corridor polygons for the mainline, dedicated feeder spurs,
     * and grade-separated interchanges, computing exact unioned land-acquisition areas in hectares.
     */
    public CorridorFootprint buildCorridorFootprint(
            List<GeoPoint> mainline,
            FacilityMode mode,
            List<List<GeoPoint>> feederSpurPaths,
            List<GeoPoint> interchangePoints) {

        if (mainline == null || mainline.size() < 2) {
            throw new IllegalArgumentException("Mainline must contain at least 2 points to build a RoW corridor.");
        }

        FacilityMode effectiveMode = mode != null ? mode : FacilityMode.NATIONAL_HIGHWAY;
        double mainlineRowWidthMeters = properties.rowWidthMetersFor(effectiveMode);
        double mainlineHalfWidth = mainlineRowWidthMeters / 2.0;
        double spurRowWidthMeters = properties.getFeederSpurRowWidthMeters();
        double spurHalfWidth = spurRowWidthMeters / 2.0;
        double interchangeRadius = properties.getInterchangeFootprintRadiusMeters();
        double constructionHalfWidth = Math.max(mainlineHalfWidth, properties.getConstructionZoneHalfWidthMeters());
        double envHalfWidth = Math.max(constructionHalfWidth, properties.getEnvironmentalBufferHalfWidthMeters());

        BufferParameters bufParams = new BufferParameters(
                BUFFER_QUADRANT_SEGMENTS,
                BufferParameters.CAP_ROUND,
                BufferParameters.JOIN_ROUND,
                BufferParameters.DEFAULT_MITRE_LIMIT);

        LineString mainlineLine = toMetricLineString(mainline);
        Geometry mainlineRow = BufferOp.bufferOp(mainlineLine, mainlineHalfWidth, bufParams);

        List<LineString> spurLines = new ArrayList<>();
        List<Geometry> spurRowList = new ArrayList<>();
        if (feederSpurPaths != null) {
            for (List<GeoPoint> spurPath : feederSpurPaths) {
                if (spurPath != null && spurPath.size() >= 2) {
                    LineString sl = toMetricLineString(spurPath);
                    spurLines.add(sl);
                    spurRowList.add(BufferOp.bufferOp(sl, spurHalfWidth, bufParams));
                }
            }
        }
        Geometry spursRow = spurRowList.isEmpty()
                ? geometryFactory.createPolygon()
                : UnaryUnionOp.union(spurRowList);

        List<Geometry> icCircles = new ArrayList<>();
        if (interchangePoints != null) {
            for (GeoPoint icPt : interchangePoints) {
                if (icPt != null) {
                    Point mp = toMetricPoint(icPt);
                    icCircles.add(BufferOp.bufferOp(mp, interchangeRadius, bufParams));
                }
            }
        }
        Geometry interchangesRow = icCircles.isEmpty()
                ? geometryFactory.createPolygon()
                : UnaryUnionOp.union(icCircles);

        List<Geometry> directParts = new ArrayList<>();
        directParts.add(mainlineRow);
        if (!spursRow.isEmpty()) {
            directParts.add(spursRow);
        }
        if (!interchangesRow.isEmpty()) {
            directParts.add(interchangesRow);
        }
        Geometry combinedDirectRow = UnaryUnionOp.union(directParts);

        // Construction clearance zone & environmental influence buffer around all alignments
        List<Geometry> allCenterlineGeoms = new ArrayList<>();
        allCenterlineGeoms.add(mainlineLine);
        allCenterlineGeoms.addAll(spurLines);
        Geometry combinedCenterlines = UnaryUnionOp.union(allCenterlineGeoms);

        Geometry constructionZone = BufferOp.bufferOp(combinedCenterlines, constructionHalfWidth, bufParams);
        if (!interchangesRow.isEmpty()) {
            constructionZone = UnaryUnionOp.union(List.of(
                    constructionZone,
                    BufferOp.bufferOp(interchangesRow, Math.max(0.0, constructionHalfWidth - mainlineHalfWidth), bufParams)));
        }

        Geometry environmentalBuffer = BufferOp.bufferOp(combinedCenterlines, envHalfWidth, bufParams);
        if (!interchangesRow.isEmpty()) {
            environmentalBuffer = UnaryUnionOp.union(List.of(
                    environmentalBuffer,
                    BufferOp.bufferOp(interchangesRow, Math.max(0.0, envHalfWidth - mainlineHalfWidth), bufParams)));
        }

        double mainlineHa = mainlineRow.getArea() / SQ_METERS_PER_HECTARE;
        double spursHa = spursRow.getArea() / SQ_METERS_PER_HECTARE;
        double roadOnlyUnionHa = spurRowList.isEmpty()
                ? mainlineHa
                : UnaryUnionOp.union(List.of(mainlineRow, spursRow)).getArea() / SQ_METERS_PER_HECTARE;
        double totalDirectHa = combinedDirectRow.getArea() / SQ_METERS_PER_HECTARE;
        double icNetAdditionalHa = Math.max(0.0, totalDirectHa - roadOnlyUnionHa);

        double constructionGrossHa = constructionZone.getArea() / SQ_METERS_PER_HECTARE;
        double constructionNetRingHa = Math.max(0.0, constructionGrossHa - totalDirectHa);
        double envGrossHa = environmentalBuffer.getArea() / SQ_METERS_PER_HECTARE;
        double envNetRingHa = Math.max(0.0, envGrossHa - totalDirectHa);

        return new CorridorFootprint(
                effectiveMode,
                properties.ircStandardFor(effectiveMode),
                mainlineRowWidthMeters,
                spurRowWidthMeters,
                interchangeRadius,
                constructionHalfWidth,
                envHalfWidth,
                mainlineLine,
                List.copyOf(spurLines),
                combinedCenterlines,
                mainlineRow,
                List.copyOf(spurRowList),
                spursRow,
                interchangesRow,
                combinedDirectRow,
                constructionZone,
                environmentalBuffer,
                round(mainlineHa),
                round(spursHa),
                round(icNetAdditionalHa),
                round(totalDirectHa),
                round(constructionGrossHa),
                round(constructionNetRingHa),
                round(envGrossHa),
                round(envNetRingHa));
    }

    /**
     * Evaluates point features (e.g., buildings, schools, hospitals, transmission towers, monuments)
     * against the 2D concentric corridor polygons.
     */
    public PointOverlayReport intersectPoints(
            CorridorFootprint corridor,
            List<SpatialPointFeature> points) {

        if (points == null || points.isEmpty()) {
            return new PointOverlayReport(0, 0, 0, 0, Map.of(), Double.POSITIVE_INFINITY, List.of());
        }

        PreparedGeometry prepDirect = PreparedGeometryFactory.prepare(corridor.combinedDirectRowMetric());
        PreparedGeometry prepConstruction = PreparedGeometryFactory.prepare(corridor.constructionZoneMetric());
        PreparedGeometry prepEnv = PreparedGeometryFactory.prepare(corridor.environmentalBufferMetric());
        Geometry rowBoundary = corridor.combinedDirectRowMetric().getBoundary();

        int directCount = 0;
        int constructionRingCount = 0;
        int envRingCount = 0;
        Map<String, Integer> directByCategory = new LinkedHashMap<>();
        double minClearanceOutsideRowMeters = Double.POSITIVE_INFINITY;
        List<PointIntersectionResult> affected = new ArrayList<>();

        for (SpatialPointFeature f : points) {
            Point pt = toMetricPoint(f.point());
            RowImpactZone zone;
            double distToCenterline = corridor.combinedCenterlinesMetric().distance(pt);
            double rawBoundaryDist = rowBoundary.distance(pt);
            double signedDistToRowEdge;

            if (prepDirect.intersects(pt)) {
                zone = RowImpactZone.DIRECT_ROW_FOOTPRINT;
                signedDistToRowEdge = -rawBoundaryDist;
                directCount++;
                directByCategory.merge(f.category(), 1, Integer::sum);
            } else if (prepConstruction.intersects(pt)) {
                zone = RowImpactZone.CONSTRUCTION_CLEARANCE_ZONE;
                signedDistToRowEdge = rawBoundaryDist;
                constructionRingCount++;
                if (rawBoundaryDist < minClearanceOutsideRowMeters) {
                    minClearanceOutsideRowMeters = rawBoundaryDist;
                }
            } else if (prepEnv.intersects(pt)) {
                zone = RowImpactZone.ENVIRONMENTAL_INFLUENCE_BUFFER;
                signedDistToRowEdge = rawBoundaryDist;
                envRingCount++;
                if (rawBoundaryDist < minClearanceOutsideRowMeters) {
                    minClearanceOutsideRowMeters = rawBoundaryDist;
                }
            } else {
                if (rawBoundaryDist < minClearanceOutsideRowMeters) {
                    minClearanceOutsideRowMeters = rawBoundaryDist;
                }
                continue;
            }

            affected.add(new PointIntersectionResult(
                    f.id(),
                    f.name(),
                    f.category(),
                    f.point(),
                    zone,
                    round(distToCenterline),
                    round(signedDistToRowEdge),
                    f.attributes()));
        }

        return new PointOverlayReport(
                 points.size(),
                directCount,
                constructionRingCount,
                envRingCount,
                Map.copyOf(directByCategory),
                Double.isInfinite(minClearanceOutsideRowMeters) ? -1.0 : round(minClearanceOutsideRowMeters),
                List.copyOf(affected));
    }

    /**
     * Clips linear features (e.g., streams, canals, roads, power lines) against the 2D corridor polygons,
     * computing exact intersected segment lengths in metres.
     */
    public List<LineIntersectionResult> intersectLines(
            CorridorFootprint corridor,
            List<SpatialLineFeature> lines) {

        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        PreparedGeometry prepEnv = PreparedGeometryFactory.prepare(corridor.environmentalBufferMetric());
        List<LineIntersectionResult> results = new ArrayList<>();

        for (SpatialLineFeature line : lines) {
            if (line.points() == null || line.points().size() < 2) {
                continue;
            }
            LineString ls = toMetricLineString(line.points());
            if (!prepEnv.intersects(ls)) {
                continue;
            }
            double lenDirect = corridor.combinedDirectRowMetric().intersection(ls).getLength();
            double lenConst = corridor.constructionZoneMetric().intersection(ls).getLength();
            double lenEnv = corridor.environmentalBufferMetric().intersection(ls).getLength();
            if (lenEnv > 1e-3) {
                results.add(new LineIntersectionResult(
                        line.id(),
                        line.name(),
                        line.category(),
                        round(lenDirect),
                        round(lenConst),
                        round(lenEnv),
                        line.attributes()));
            }
        }
        return List.copyOf(results);
    }

    /**
     * Intersects polygon features (e.g., building footprints, village boundaries, wetlands, agricultural plots,
     * or circular asset footprints) against the 2D corridor polygons, computing exact area overlap in hectares.
     */
    public List<PolygonIntersectionResult> intersectPolygons(
            CorridorFootprint corridor,
            List<SpatialPolygonFeature> polygons) {

        if (polygons == null || polygons.isEmpty()) {
            return List.of();
        }

        PreparedGeometry prepEnv = PreparedGeometryFactory.prepare(corridor.environmentalBufferMetric());
        List<PolygonIntersectionResult> results = new ArrayList<>();

        for (SpatialPolygonFeature polyFeat : polygons) {
            Geometry poly = toMetricPolygon(polyFeat);
            if (poly == null || poly.isEmpty() || !prepEnv.intersects(poly)) {
                continue;
            }
            double totalHa = poly.getArea() / SQ_METERS_PER_HECTARE;
            double directHa = corridor.combinedDirectRowMetric().intersection(poly).getArea() / SQ_METERS_PER_HECTARE;
            double constHa = corridor.constructionZoneMetric().intersection(poly).getArea() / SQ_METERS_PER_HECTARE;
            double envHa = corridor.environmentalBufferMetric().intersection(poly).getArea() / SQ_METERS_PER_HECTARE;
            if (envHa > 1e-5) {
                double pctDirect = totalHa > 1e-6 ? Math.min(100.0, (directHa / totalHa) * 100.0) : 0.0;
                results.add(new PolygonIntersectionResult(
                        polyFeat.id(),
                        polyFeat.name(),
                        polyFeat.category(),
                        round4(totalHa),
                        round4(directHa),
                        round4(constHa),
                        round4(envHa),
                        round(pctDirect),
                        polyFeat.attributes()));
            }
        }
        return List.copyOf(results);
    }

    /**
     * Performs a 2D cross-sectional swath audit across the full width of the RoW strip
     * (Left RoW Edge -W/2, Left-Quarter -W/4, Centerline 0, Right-Quarter +W/4, Right RoW Edge +W/2)
     * plus vector asset overlay on existing layers.
     */
    public SwathCorridorAudit evaluateSwathAudit(
            List<GeoPoint> mainline,
            List<List<GeoPoint>> spurPaths,
            CorridorFootprint corridor,
            LulcProvider lulcProvider,
            SlopeRasterProvider slopeProvider,
            ProtectedAreaRasterProvider paProvider,
            SettlementRasterProvider settlementProvider,
            WaterBodyRasterProvider waterProvider,
            WildlifeRasterProvider wildlifeProvider,
            GeotechRasterProvider geotechProvider,
            UtilityRasterProvider utilityRasterProvider,
            UtilityNetworkVectorIndex utilityVectorIndex) {
        return evaluateSwathAudit(
                mainline,
                spurPaths,
                corridor,
                lulcProvider,
                slopeProvider,
                paProvider,
                settlementProvider,
                waterProvider,
                wildlifeProvider,
                geotechProvider,
                utilityRasterProvider,
                utilityVectorIndex,
                null,
                null);
    }

    public SwathCorridorAudit evaluateSwathAudit(
            List<GeoPoint> mainline,
            List<List<GeoPoint>> spurPaths,
            CorridorFootprint corridor,
            LulcProvider lulcProvider,
            SlopeRasterProvider slopeProvider,
            ProtectedAreaRasterProvider paProvider,
            SettlementRasterProvider settlementProvider,
            WaterBodyRasterProvider waterProvider,
            WildlifeRasterProvider wildlifeProvider,
            GeotechRasterProvider geotechProvider,
            UtilityRasterProvider utilityRasterProvider,
            UtilityNetworkVectorIndex utilityVectorIndex,
            BuildingDensityRasterProvider buildingRasterProvider,
            BuildingFootprintVectorIndex buildingVectorIndex) {
        return evaluateSwathAudit(
                mainline,
                spurPaths,
                corridor,
                lulcProvider,
                slopeProvider,
                paProvider,
                settlementProvider,
                waterProvider,
                wildlifeProvider,
                geotechProvider,
                utilityRasterProvider,
                utilityVectorIndex,
                buildingRasterProvider,
                buildingVectorIndex,
                null,
                null);
    }

    public SwathCorridorAudit evaluateSwathAudit(
            List<GeoPoint> mainline,
            List<List<GeoPoint>> spurPaths,
            CorridorFootprint corridor,
            LulcProvider lulcProvider,
            SlopeRasterProvider slopeProvider,
            ProtectedAreaRasterProvider paProvider,
            SettlementRasterProvider settlementProvider,
            WaterBodyRasterProvider waterProvider,
            WildlifeRasterProvider wildlifeProvider,
            GeotechRasterProvider geotechProvider,
            UtilityRasterProvider utilityRasterProvider,
            UtilityNetworkVectorIndex utilityVectorIndex,
            BuildingDensityRasterProvider buildingRasterProvider,
            BuildingFootprintVectorIndex buildingVectorIndex,
            PopulationDensityRasterProvider demographicRasterProvider,
            DemographicVectorIndex demographicVectorIndex) {
        return evaluateSwathAudit(
                mainline,
                spurPaths,
                corridor,
                lulcProvider,
                slopeProvider,
                paProvider,
                settlementProvider,
                waterProvider,
                wildlifeProvider,
                geotechProvider,
                utilityRasterProvider,
                utilityVectorIndex,
                buildingRasterProvider,
                buildingVectorIndex,
                demographicRasterProvider,
                demographicVectorIndex,
                null,
                null);
    }

    public SwathCorridorAudit evaluateSwathAudit(
            List<GeoPoint> mainline,
            List<List<GeoPoint>> spurPaths,
            CorridorFootprint corridor,
            LulcProvider lulcProvider,
            SlopeRasterProvider slopeProvider,
            ProtectedAreaRasterProvider paProvider,
            SettlementRasterProvider settlementProvider,
            WaterBodyRasterProvider waterProvider,
            WildlifeRasterProvider wildlifeProvider,
            GeotechRasterProvider geotechProvider,
            UtilityRasterProvider utilityRasterProvider,
            UtilityNetworkVectorIndex utilityVectorIndex,
            BuildingDensityRasterProvider buildingRasterProvider,
            BuildingFootprintVectorIndex buildingVectorIndex,
            PopulationDensityRasterProvider demographicRasterProvider,
            DemographicVectorIndex demographicVectorIndex,
            DrainageWetlandRasterProvider drainageRasterProvider,
            DrainageWetlandVectorIndex drainageVectorIndex) {

        double stepMeters = Math.max(50.0, properties.getCrossSectionSampleStepMeters());
        double halfW = corridor.mainlineRowWidthMeters() / 2.0;
        double[] lateralOffsets = new double[]{-halfW, -0.5 * halfW, 0.0, 0.5 * halfW, halfW};

        Map<LulcClass, Double> lulcWeights = new EnumMap<>(LulcClass.class);
        for (LulcClass c : LulcClass.values()) {
            lulcWeights.put(c, 0.0);
        }
        Map<SettlementExposureClass, Double> settlementWeights = new EnumMap<>(SettlementExposureClass.class);
        for (SettlementExposureClass c : SettlementExposureClass.values()) {
            settlementWeights.put(c, 0.0);
        }
        Map<WaterBodyClass, Double> waterWeights = new EnumMap<>(WaterBodyClass.class);
        for (WaterBodyClass c : WaterBodyClass.values()) {
            waterWeights.put(c, 0.0);
        }
        Map<WildlifeZoneClass, Double> wildlifeWeights = new EnumMap<>(WildlifeZoneClass.class);
        for (WildlifeZoneClass c : WildlifeZoneClass.values()) {
            wildlifeWeights.put(c, 0.0);
        }
        Map<GeotechHazardClass, Double> geotechWeights = new EnumMap<>(GeotechHazardClass.class);
        for (GeotechHazardClass c : GeotechHazardClass.values()) {
            geotechWeights.put(c, 0.0);
        }
        Map<UtilityCorridorClass, Double> utilityWeights = new EnumMap<>(UtilityCorridorClass.class);
        for (UtilityCorridorClass c : UtilityCorridorClass.values()) {
            utilityWeights.put(c, 0.0);
        }
        Map<BuildingDensityClass, Double> buildingWeights = new EnumMap<>(BuildingDensityClass.class);
        for (BuildingDensityClass c : BuildingDensityClass.values()) {
            buildingWeights.put(c, 0.0);
        }
        Map<PopulationDensityClass, Double> demographicWeights = new EnumMap<>(PopulationDensityClass.class);
        for (PopulationDensityClass c : PopulationDensityClass.values()) {
            demographicWeights.put(c, 0.0);
        }
        Map<DrainageWetlandClass, Double> drainageWeights = new EnumMap<>(DrainageWetlandClass.class);
        for (DrainageWetlandClass c : DrainageWetlandClass.values()) {
            drainageWeights.put(c, 0.0);
        }

        double totalWeight = 0.0;
        int totalCrossSections = 0;
        int centerlinePaViolations = 0;
        int outerEdgePaEncroachmentSamples = 0;
        int centerlineSlopeOver30Violations = 0;
        int outerEdgeSlopeOver30Samples = 0;

        for (int i = 1; i < mainline.size(); i++) {
            MetricPoint a = projection.forward(mainline.get(i - 1));
            MetricPoint b = projection.forward(mainline.get(i));
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double segLen = Math.hypot(dx, dy);
            if (segLen <= 1e-6) {
                continue;
            }
            // Unit normal vector (-ty, tx) pointing left of travel direction
            double nx = -dy / segLen;
            double ny = dx / segLen;

            int steps = Math.max(1, (int) Math.ceil(segLen / stepMeters));
            double sliceWeight = (segLen / steps) / lateralOffsets.length;

            for (int s = 0; s < steps; s++) {
                double frac = (s + 0.5) / steps;
                double cx = a.x() + frac * dx;
                double cy = a.y() + frac * dy;
                totalCrossSections++;

                for (int k = 0; k < lateralOffsets.length; k++) {
                    double offset = lateralOffsets[k];
                    boolean isCenterline = Math.abs(offset) < 1e-6;
                    boolean isOuterEdge = Math.abs(Math.abs(offset) - halfW) < 1e-6;

                    GeoPoint samplePt = projection.inverse(new MetricPoint(cx + offset * nx, cy + offset * ny));
                    totalWeight += sliceWeight;

                    if (lulcProvider != null) {
                        LulcClass lc = lulcProvider.sample(samplePt).type();
                        lulcWeights.put(lc, lulcWeights.get(lc) + sliceWeight);
                    }
                    if (settlementProvider != null) {
                        SettlementExposureClass sc = settlementProvider.sampleClass(samplePt.lat(), samplePt.lon());
                        settlementWeights.put(sc, settlementWeights.get(sc) + sliceWeight);
                    }
                    if (waterProvider != null) {
                        WaterBodyClass wc = waterProvider.sampleClass(samplePt.lat(), samplePt.lon());
                        waterWeights.put(wc, waterWeights.get(wc) + sliceWeight);
                    }
                    if (wildlifeProvider != null) {
                        WildlifeZoneClass wlc = wildlifeProvider.sampleClass(samplePt.lat(), samplePt.lon());
                        wildlifeWeights.put(wlc, wildlifeWeights.get(wlc) + sliceWeight);
                    }
                    if (geotechProvider != null) {
                        GeotechHazardClass gc = geotechProvider.sampleClass(samplePt.lat(), samplePt.lon());
                        geotechWeights.put(gc, geotechWeights.get(gc) + sliceWeight);
                    }
                    if (utilityRasterProvider != null) {
                        UtilityCorridorClass uc = utilityRasterProvider.sampleClass(samplePt.lat(), samplePt.lon());
                        utilityWeights.put(uc, utilityWeights.get(uc) + sliceWeight);
                    }
                    if (buildingRasterProvider != null) {
                        BuildingDensityClass bdc = buildingRasterProvider.sampleClass(samplePt.lat(), samplePt.lon());
                        buildingWeights.put(bdc, buildingWeights.get(bdc) + sliceWeight);
                    }
                    if (demographicRasterProvider != null) {
                        PopulationDensityClass pdc = demographicRasterProvider.sampleClass(samplePt.lat(), samplePt.lon());
                        demographicWeights.put(pdc, demographicWeights.get(pdc) + sliceWeight);
                    }
                    if (drainageRasterProvider != null) {
                        DrainageWetlandClass dwc = drainageRasterProvider.sampleClass(samplePt.lat(), samplePt.lon());
                        drainageWeights.put(dwc, drainageWeights.get(dwc) + sliceWeight);
                    }

                    if (paProvider != null) {
                        Double pa = paProvider.sampleOrNull(samplePt.lat(), samplePt.lon());
                        if (pa != null && Math.round(pa) == 1L) {
                            if (isCenterline) {
                                centerlinePaViolations++;
                            } else if (isOuterEdge) {
                                outerEdgePaEncroachmentSamples++;
                            }
                        }
                    }
                    if (slopeProvider != null) {
                        Double slp = slopeProvider.sampleOrNull(samplePt.lat(), samplePt.lon());
                        if (slp != null && slp > 30.0) {
                            if (isCenterline) {
                                centerlineSlopeOver30Violations++;
                            } else if (isOuterEdge) {
                                outerEdgeSlopeOver30Samples++;
                            }
                        }
                    }
                }
            }
        }

        double targetHa = corridor.mainlineRowAreaHectares();
        double scaleToHa = totalWeight > 0.0 ? (targetHa / totalWeight) : 0.0;

        Map<String, Double> lulcHa = scaleEnumWeights(lulcWeights, scaleToHa);
        Map<String, Double> settlementHa = scaleEnumWeights(settlementWeights, scaleToHa);
        Map<String, Double> waterHa = scaleEnumWeights(waterWeights, scaleToHa);
        Map<String, Double> wildlifeHa = scaleEnumWeights(wildlifeWeights, scaleToHa);
        Map<String, Double> geotechHa = scaleEnumWeights(geotechWeights, scaleToHa);
        Map<String, Double> utilityHa = scaleEnumWeights(utilityWeights, scaleToHa);
        Map<String, Double> buildingDensityHa = scaleEnumWeights(buildingWeights, scaleToHa);
        Map<String, Double> populationDensityHa = scaleEnumWeights(demographicWeights, scaleToHa);
        Map<String, Double> drainageWetlandHa = scaleEnumWeights(drainageWeights, scaleToHa);

        // Vector Asset Overlay on Substations & Transmission Towers
        int towersInDirectRow = 0;
        int towersInConstructionZone = 0;
        int substationsInDirectRow = 0;
        int substationsInConstructionZone = 0;
        double minTowerClearanceFromRowEdgeMeters = Double.POSITIVE_INFINITY;

        if (utilityVectorIndex != null) {
            List<SpatialPointFeature> assetPoints = new ArrayList<>();
            for (UtilityNetworkVectorIndex.UtilityAsset asset : utilityVectorIndex.allAssets()) {
                assetPoints.add(new SpatialPointFeature(
                        asset.id(),
                        asset.name(),
                        asset.assetType(),
                        asset.point(),
                        Map.of("footprintRadiusMeters", asset.footprintRadiusMeters())));
            }
            PointOverlayReport assetOverlay = intersectPoints(corridor, assetPoints);
            minTowerClearanceFromRowEdgeMeters = assetOverlay.minClearanceOutsideRowMeters();
            for (PointIntersectionResult hit : assetOverlay.affectedPoints()) {
                boolean isTower = "TRANSMISSION_TOWER".equals(hit.category());
                if (hit.impactZone() == RowImpactZone.DIRECT_ROW_FOOTPRINT) {
                    if (isTower) {
                        towersInDirectRow++;
                    } else {
                        substationsInDirectRow++;
                    }
                } else if (hit.impactZone() == RowImpactZone.CONSTRUCTION_CLEARANCE_ZONE) {
                    if (isTower) {
                        towersInConstructionZone++;
                    } else {
                        substationsInConstructionZone++;
                    }
                }
            }
        }

        Map<String, Object> buildingAndSettlementOverlay = Map.of();
        if (buildingVectorIndex != null) {
            buildingAndSettlementOverlay =
                    buildingVectorIndex.evaluateCorridorFootprintImpact(mainline, corridor).toSummaryMap();
        }

        Map<String, Object> populationAndDemographicOverlay = Map.of();
        if (demographicVectorIndex != null) {
            populationAndDemographicOverlay =
                    demographicVectorIndex.evaluateCorridorFootprintDemographicImpact(
                            mainline, corridor, buildingVectorIndex).toSummaryMap();
        }

        Map<String, Object> drainageAndWetlandOverlay = Map.of();
        if (drainageVectorIndex != null) {
            drainageAndWetlandOverlay =
                    drainageVectorIndex.evaluateCorridorDrainageAndWetlandImpact(mainline, corridor).toSummaryMap();
        }

        return new SwathCorridorAudit(
                totalCrossSections,
                lateralOffsets.length,
                lulcHa,
                settlementHa,
                waterHa,
                wildlifeHa,
                geotechHa,
                utilityHa,
                buildingDensityHa,
                populationDensityHa,
                drainageWetlandHa,
                centerlinePaViolations,
                outerEdgePaEncroachmentSamples,
                centerlineSlopeOver30Violations,
                outerEdgeSlopeOver30Samples,
                towersInDirectRow,
                towersInConstructionZone,
                substationsInDirectRow,
                substationsInConstructionZone,
                minTowerClearanceFromRowEdgeMeters,
                buildingAndSettlementOverlay,
                populationAndDemographicOverlay,
                drainageAndWetlandOverlay);
    }

    /**
     * Converts a metric JTS Polygon or MultiPolygon in EPSG:32646 into a simplified RFC 7946 GeoJSON
     * geometry Map ({@code type: "Polygon"} or {@code "MultiPolygon"}, {@code coordinates: [...]}).
     */
    public Map<String, Object> toGeoJsonGeometry(Geometry metricGeometry) {
        return toGeoJsonGeometry(metricGeometry, properties.getGeoJsonSimplifyToleranceMeters());
    }

    public Map<String, Object> toGeoJsonGeometry(Geometry metricGeometry, double simplifyToleranceMeters) {
        if (metricGeometry == null || metricGeometry.isEmpty()) {
            return Map.of("type", "Polygon", "coordinates", List.of());
        }

        Geometry simplified = simplifyToleranceMeters > 0.0
                ? TopologyPreservingSimplifier.simplify(metricGeometry, simplifyToleranceMeters)
                : metricGeometry;

        if (simplified instanceof Polygon poly) {
            return Map.of(
                    "type", "Polygon",
                    "coordinates", polygonToLonLatRings(poly));
        } else if (simplified instanceof MultiPolygon multi) {
            List<List<List<List<Double>>>> polygons = new ArrayList<>(multi.getNumGeometries());
            for (int i = 0; i < multi.getNumGeometries(); i++) {
                if (multi.getGeometryN(i) instanceof Polygon p && !p.isEmpty()) {
                    polygons.add(polygonToLonLatRings(p));
                }
            }
            if (polygons.size() == 1) {
                return Map.of(
                        "type", "Polygon",
                        "coordinates", polygons.get(0));
            }
            return Map.of(
                    "type", "MultiPolygon",
                    "coordinates", List.copyOf(polygons));
        }

        Geometry buffered = simplified.buffer(0.0);
        if (buffered instanceof Polygon poly) {
            return Map.of(
                    "type", "Polygon",
                    "coordinates", polygonToLonLatRings(poly));
        }
        return Map.of("type", "Polygon", "coordinates", List.of());
    }

    private List<List<List<Double>>> polygonToLonLatRings(Polygon poly) {
        List<List<List<Double>>> rings = new ArrayList<>(1 + poly.getNumInteriorRing());
        rings.add(ringToLonLat(poly.getExteriorRing().getCoordinates()));
        for (int i = 0; i < poly.getNumInteriorRing(); i++) {
            rings.add(ringToLonLat(poly.getInteriorRingN(i).getCoordinates()));
        }
        return List.copyOf(rings);
    }

    private List<List<Double>> ringToLonLat(Coordinate[] coords) {
        List<List<Double>> ring = new ArrayList<>(coords.length);
        for (Coordinate c : coords) {
            GeoPoint gp = projection.inverse(new MetricPoint(c.x, c.y));
            ring.add(List.of(round6(gp.lon()), round6(gp.lat())));
        }
        return List.copyOf(ring);
    }

    private Point toMetricPoint(GeoPoint gp) {
        MetricPoint mp = projection.forward(gp);
        return geometryFactory.createPoint(new Coordinate(mp.x(), mp.y()));
    }

    private LineString toMetricLineString(List<GeoPoint> points) {
        Coordinate[] coords = new Coordinate[points.size()];
        for (int i = 0; i < points.size(); i++) {
            MetricPoint mp = projection.forward(points.get(i));
            coords[i] = new Coordinate(mp.x(), mp.y());
        }
        return geometryFactory.createLineString(coords);
    }

    private Geometry toMetricPolygon(SpatialPolygonFeature feat) {
        if (feat.circleCenter() != null && feat.circleRadiusMeters() > 0.0) {
            Point center = toMetricPoint(feat.circleCenter());
            return BufferOp.bufferOp(center, feat.circleRadiusMeters(), BUFFER_QUADRANT_SEGMENTS);
        }
        List<GeoPoint> ringPts = feat.exteriorRing();
        if (ringPts == null || ringPts.size() < 3) {
            return null;
        }
        boolean closed = ringPts.get(0).equals(ringPts.get(ringPts.size() - 1));
        int n = closed ? ringPts.size() : ringPts.size() + 1;
        if (n < 4) {
            return null;
        }
        Coordinate[] coords = new Coordinate[n];
        for (int i = 0; i < ringPts.size(); i++) {
            MetricPoint mp = projection.forward(ringPts.get(i));
            coords[i] = new Coordinate(mp.x(), mp.y());
        }
        if (!closed) {
            coords[n - 1] = new Coordinate(coords[0].x, coords[0].y);
        }
        LinearRing lr = geometryFactory.createLinearRing(coords);
        return geometryFactory.createPolygon(lr);
    }

    private static <E extends Enum<E>> Map<String, Double> scaleEnumWeights(
            Map<E, Double> weights, double scaleToHa) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<E, Double> entry : weights.entrySet()) {
            double ha = round(entry.getValue() * scaleToHa);
            if (ha > 0.0) {
                out.put(entry.getKey().name(), ha);
            }
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static double round6(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }

    public record CorridorFootprint(
            FacilityMode facilityMode,
            String ircStandard,
            double mainlineRowWidthMeters,
            double feederSpurRowWidthMeters,
            double interchangeRadiusMeters,
            double constructionZoneHalfWidthMeters,
            double environmentalBufferHalfWidthMeters,
            LineString mainlineCenterlineMetric,
            List<LineString> spurCenterlinesMetric,
            Geometry combinedCenterlinesMetric,
            Geometry mainlineRowMetric,
            List<Geometry> spurRowsMetric,
            Geometry spursRowMetric,
            Geometry interchangesRowMetric,
            Geometry combinedDirectRowMetric,
            Geometry constructionZoneMetric,
            Geometry environmentalBufferMetric,
            double mainlineRowAreaHectares,
            double feederSpursRowAreaHectares,
            double interchangeLoopsAdditionalHectares,
            double totalDirectRowAcquisitionHectares,
            double constructionZoneGrossHectares,
            double constructionZoneNetRingHectares,
            double environmentalBufferGrossHectares,
            double environmentalBufferNetRingHectares
    ) {}

    public record SpatialPointFeature(
            String id,
            String name,
            String category,
            GeoPoint point,
            Map<String, Object> attributes
    ) {}

    public record PointIntersectionResult(
            String id,
            String name,
            String category,
            GeoPoint point,
            RowImpactZone impactZone,
            double distanceToCenterlineMeters,
            double signedDistanceToRowEdgeMeters,
            Map<String, Object> attributes
    ) {}

    public record PointOverlayReport(
            int totalFeaturesEvaluated,
            int directRowHitCount,
            int constructionZoneRingHitCount,
            int environmentalBufferRingHitCount,
            Map<String, Integer> directRowHitsByCategory,
            double minClearanceOutsideRowMeters,
            List<PointIntersectionResult> affectedPoints
    ) {}

    public record SpatialLineFeature(
            String id,
            String name,
            String category,
            List<GeoPoint> points,
            Map<String, Object> attributes
    ) {}

    public record LineIntersectionResult(
            String id,
            String name,
            String category,
            double lengthInDirectRowMeters,
            double lengthInConstructionZoneMeters,
            double lengthInEnvironmentalBufferMeters,
            Map<String, Object> attributes
    ) {}

    public record SpatialPolygonFeature(
            String id,
            String name,
            String category,
            List<GeoPoint> exteriorRing,
            GeoPoint circleCenter,
            double circleRadiusMeters,
            Map<String, Object> attributes
    ) {
        public static SpatialPolygonFeature fromRing(
                String id,
                String name,
                String category,
                List<GeoPoint> exteriorRing,
                Map<String, Object> attributes) {
            return new SpatialPolygonFeature(id, name, category, exteriorRing, null, 0.0, attributes);
        }

        public static SpatialPolygonFeature fromCircle(
                String id,
                String name,
                String category,
                GeoPoint center,
                double radiusMeters,
                Map<String, Object> attributes) {
            return new SpatialPolygonFeature(id, name, category, List.of(), center, radiusMeters, attributes);
        }
    }

    public record PolygonIntersectionResult(
            String id,
            String name,
            String category,
            double totalFeatureAreaHectares,
            double areaInDirectRowHectares,
            double areaInConstructionZoneHectares,
            double areaInEnvironmentalBufferHectares,
            double percentOfFeatureInDirectRow,
            Map<String, Object> attributes
    ) {}

    public record SwathCorridorAudit(
            int crossSectionsEvaluated,
            int lateralPointsPerCrossSection,
            Map<String, Double> lulcAcquisitionHectares,
            Map<String, Double> settlementExposureHectares,
            Map<String, Double> waterAndRiparianHectares,
            Map<String, Double> wildlifeZoneHectares,
            Map<String, Double> geotechClassHectares,
            Map<String, Double> utilityClassHectares,
            Map<String, Double> buildingDensityClassHectares,
            Map<String, Double> populationDensityClassHectares,
            Map<String, Double> drainageWetlandClassHectares,
            int centerlineProtectedAreaViolations,
            int outerRowEdgeProtectedAreaSamples,
            int centerlineSlopeOver30Violations,
            int outerRowEdgeSlopeOver30Samples,
            int transmissionTowersInDirectRowCount,
            int transmissionTowersInConstructionZoneCount,
            int substationsInDirectRowCount,
            int substationsInConstructionZoneCount,
            double minTowerClearanceFromRowEdgeMeters,
            Map<String, Object> buildingAndSettlementOverlay,
            Map<String, Object> populationAndDemographicOverlay,
            Map<String, Object> drainageAndWetlandOverlay
    ) {
        public SwathCorridorAudit(
                int crossSectionsEvaluated,
                int lateralPointsPerCrossSection,
                Map<String, Double> lulcAcquisitionHectares,
                Map<String, Double> settlementExposureHectares,
                Map<String, Double> waterAndRiparianHectares,
                Map<String, Double> wildlifeZoneHectares,
                Map<String, Double> geotechClassHectares,
                Map<String, Double> utilityClassHectares,
                Map<String, Double> buildingDensityClassHectares,
                Map<String, Double> populationDensityClassHectares,
                int centerlineProtectedAreaViolations,
                int outerRowEdgeProtectedAreaSamples,
                int centerlineSlopeOver30Violations,
                int outerRowEdgeSlopeOver30Samples,
                int transmissionTowersInDirectRowCount,
                int transmissionTowersInConstructionZoneCount,
                int substationsInDirectRowCount,
                int substationsInConstructionZoneCount,
                double minTowerClearanceFromRowEdgeMeters,
                Map<String, Object> buildingAndSettlementOverlay,
                Map<String, Object> populationAndDemographicOverlay) {
            this(
                    crossSectionsEvaluated,
                    lateralPointsPerCrossSection,
                    lulcAcquisitionHectares,
                    settlementExposureHectares,
                    waterAndRiparianHectares,
                    wildlifeZoneHectares,
                    geotechClassHectares,
                    utilityClassHectares,
                    buildingDensityClassHectares,
                    populationDensityClassHectares,
                    Map.of(),
                    centerlineProtectedAreaViolations,
                    outerRowEdgeProtectedAreaSamples,
                    centerlineSlopeOver30Violations,
                    outerRowEdgeSlopeOver30Samples,
                    transmissionTowersInDirectRowCount,
                    transmissionTowersInConstructionZoneCount,
                    substationsInDirectRowCount,
                    substationsInConstructionZoneCount,
                    minTowerClearanceFromRowEdgeMeters,
                    buildingAndSettlementOverlay,
                    populationAndDemographicOverlay,
                    Map.of());
        }

        public SwathCorridorAudit(
                int crossSectionsEvaluated,
                int lateralPointsPerCrossSection,
                Map<String, Double> lulcAcquisitionHectares,
                Map<String, Double> settlementExposureHectares,
                Map<String, Double> waterAndRiparianHectares,
                Map<String, Double> wildlifeZoneHectares,
                Map<String, Double> geotechClassHectares,
                Map<String, Double> utilityClassHectares,
                Map<String, Double> buildingDensityClassHectares,
                int centerlineProtectedAreaViolations,
                int outerRowEdgeProtectedAreaSamples,
                int centerlineSlopeOver30Violations,
                int outerRowEdgeSlopeOver30Samples,
                int transmissionTowersInDirectRowCount,
                int transmissionTowersInConstructionZoneCount,
                int substationsInDirectRowCount,
                int substationsInConstructionZoneCount,
                double minTowerClearanceFromRowEdgeMeters,
                Map<String, Object> buildingAndSettlementOverlay) {
            this(
                    crossSectionsEvaluated,
                    lateralPointsPerCrossSection,
                    lulcAcquisitionHectares,
                    settlementExposureHectares,
                    waterAndRiparianHectares,
                    wildlifeZoneHectares,
                    geotechClassHectares,
                    utilityClassHectares,
                    buildingDensityClassHectares,
                    Map.of(),
                    Map.of(),
                    centerlineProtectedAreaViolations,
                    outerRowEdgeProtectedAreaSamples,
                    centerlineSlopeOver30Violations,
                    outerRowEdgeSlopeOver30Samples,
                    transmissionTowersInDirectRowCount,
                    transmissionTowersInConstructionZoneCount,
                    substationsInDirectRowCount,
                    substationsInConstructionZoneCount,
                    minTowerClearanceFromRowEdgeMeters,
                    buildingAndSettlementOverlay,
                    Map.of(),
                    Map.of());
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("crossSectionsEvaluated", crossSectionsEvaluated);
            map.put("lateralPointsPerCrossSection", lateralPointsPerCrossSection);
            map.put("lulcAcquisitionHectares", lulcAcquisitionHectares);
            map.put("settlementExposureHectares", settlementExposureHectares);
            map.put("waterAndRiparianHectares", waterAndRiparianHectares);
            map.put("wildlifeZoneHectares", wildlifeZoneHectares);
            map.put("geotechClassHectares", geotechClassHectares);
            map.put("utilityClassHectares", utilityClassHectares);
            if (buildingDensityClassHectares != null && !buildingDensityClassHectares.isEmpty()) {
                map.put("buildingDensityClassHectares", buildingDensityClassHectares);
            }
            if (populationDensityClassHectares != null && !populationDensityClassHectares.isEmpty()) {
                map.put("populationDensityClassHectares", populationDensityClassHectares);
            }
            if (drainageWetlandClassHectares != null && !drainageWetlandClassHectares.isEmpty()) {
                map.put("drainageWetlandClassHectares", drainageWetlandClassHectares);
            }
            map.put("rowBoundaryHardConstraintAudit", Map.of(
                    "centerlineProtectedAreaViolations", centerlineProtectedAreaViolations,
                    "outerRowEdgeProtectedAreaSamples", outerRowEdgeProtectedAreaSamples,
                    "centerlineSlopeOver30Violations", centerlineSlopeOver30Violations,
                    "outerRowEdgeSlopeOver30Samples", outerRowEdgeSlopeOver30Samples));
            map.put("utilityAssetsInCorridor", Map.of(
                    "transmissionTowersInDirectRowCount", transmissionTowersInDirectRowCount,
                    "transmissionTowersInConstructionZoneCount", transmissionTowersInConstructionZoneCount,
                    "substationsInDirectRowCount", substationsInDirectRowCount,
                    "substationsInConstructionZoneCount", substationsInConstructionZoneCount,
                    "minTowerClearanceFromRowEdgeMeters", minTowerClearanceFromRowEdgeMeters));
            if (buildingAndSettlementOverlay != null && !buildingAndSettlementOverlay.isEmpty()) {
                map.put("buildingAndSettlementOverlay", buildingAndSettlementOverlay);
            }
            if (populationAndDemographicOverlay != null && !populationAndDemographicOverlay.isEmpty()) {
                map.put("populationAndDemographicOverlay", populationAndDemographicOverlay);
            }
            if (drainageAndWetlandOverlay != null && !drainageAndWetlandOverlay.isEmpty()) {
                map.put("drainageAndWetlandOverlay", drainageAndWetlandOverlay);
            }
            return map;
        }
    }
}
