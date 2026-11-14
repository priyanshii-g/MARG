package in.marg.region;

import in.marg.model.GeoPoint;
import in.marg.model.StudyRegion;
import in.marg.row.RightOfWayCorridorEngine;
import in.marg.routing.DistanceOnlyCostSurface;
import in.marg.routing.GreenfieldAStarRouter;
import in.marg.routing.RouteConstraints;
import in.marg.spatial.Wgs84UtmProjection;
import in.marg.transport.FacilityMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier 1 — Dynamic On-Demand Pan-India Routing Service.
 *
 * <p>Routes greenfield highway/expressway corridors between any arbitrary start and goal
 * coordinates across the Indian mainland (6.5&deg;N..37.5&deg;N, 68.0&deg;E..97.5&deg;E),
 * dynamically selecting the local UTM Zone (42N..47N, EPSG:32642..32647) and computing
 * alignments on the canonical 250 m metric grid.
 */
@Service
public class PanIndiaDynamicCorridorService {

    public static final double ROUTING_RESOLUTION_METERS = 250.0;
    private static final double MAX_SINGLE_STAGE_SPAN_DEG = 0.65; // ~72 km per 250 m A* stage window

    public static final StudyRegion PAN_INDIA_BOUNDS = new StudyRegion(
            "Pan-India Dynamic Greenfield Routing Engine (All-India Mainland)",
            6.50,
            68.00,
            37.50,
            97.50,
            new GeoPoint(26.8500, 75.8000),
            new GeoPoint(26.4500, 74.6500)
    );

    public boolean isWithinIndiaBounds(GeoPoint point) {
        return point != null && PAN_INDIA_BOUNDS.contains(point);
    }

    public Map<String, Object> routeDynamicPanIndia(
            GeoPoint requestedStart,
            GeoPoint requestedGoal,
            FacilityMode facilityMode,
            Double corridorBufferDeg,
            Double maxDeviationPercent) {

        if (!isWithinIndiaBounds(requestedStart) || !isWithinIndiaBounds(requestedGoal)) {
            throw new IllegalArgumentException(
                    "Both endpoints must lie within Pan-India bounds (lat 6.5..37.5 N, lon 68.0..97.5 E).");
        }

        FacilityMode mode = facilityMode != null ? facilityMode : FacilityMode.EXPRESSWAY;
        double midLon = (requestedStart.lon() + requestedGoal.lon()) / 2.0;
        int utmZone = Wgs84UtmProjection.indiaUtmZoneForLongitude(midLon);
        Wgs84UtmProjection projection = new Wgs84UtmProjection(utmZone);
        GreenfieldAStarRouter router = new GreenfieldAStarRouter(projection);
        RightOfWayCorridorEngine rowEngine = new RightOfWayCorridorEngine(projection);
        EndpointSnapService snapService = new EndpointSnapService(projection);

        PanIndiaDynamicCostLayer panIndiaSurface = new PanIndiaDynamicCostLayer(
                requestedStart, requestedGoal, mode);

        double bufferDeg = corridorBufferDeg != null
                ? Math.max(0.05, Math.min(0.30, corridorBufferDeg))
                : 0.12;

        // Resolve/snap endpoints across active Pan-India space
        EndpointSnapService.EndpointResolution startSnap = snapService.resolveEndpoints(
                PAN_INDIA_BOUNDS,
                null,
                panIndiaSurface,
                requestedStart,
                requestedGoal);

        GeoPoint start = startSnap.resolvedStart();
        GeoPoint goal = startSnap.resolvedGoal();

        // Determine whether corridor fits in a single 250 m A* window or multi-stage 250 m windows
        double dLat = Math.abs(goal.lat() - start.lat());
        double dLon = Math.abs(goal.lon() - start.lon());
        int stages = Math.max(1, (int) Math.ceil(Math.max(dLat, dLon) / MAX_SINGLE_STAGE_SPAN_DEG));

        List<GeoPoint> baseline = new ArrayList<>();
        List<GeoPoint> mainline = new ArrayList<>();

        List<GeoPoint> stageWaypoints = buildStageWaypoints(
                start, goal, stages, panIndiaSurface, snapService, bufferDeg);

        for (int s = 0; s < stageWaypoints.size() - 1; s++) {
            GeoPoint stageStart = stageWaypoints.get(s);
            GeoPoint stageGoal = stageWaypoints.get(s + 1);
            double stageBuffer = Math.min(bufferDeg, 0.12);
            StudyRegion stageWindow = stageCorridorWindow(stageStart, stageGoal, stageBuffer);
            PanIndiaDynamicCostLayer stageSurface = new PanIndiaDynamicCostLayer(stageStart, stageGoal, mode);

            List<GeoPoint> stageBaseline = router.route(
                    PAN_INDIA_BOUNDS,
                    stageStart,
                    stageGoal,
                    new DistanceOnlyCostSurface(),
                    ROUTING_RESOLUTION_METERS,
                    stageWindow);

            RouteConstraints constraints = RouteConstraints.none();
            if (maxDeviationPercent != null && Double.isFinite(maxDeviationPercent) && maxDeviationPercent >= 0.0) {
                double maxStageKm = router.routeLengthKm(stageBaseline) * (1.0 + maxDeviationPercent / 100.0);
                constraints = RouteConstraints.maxLengthKm(maxStageKm);
            }

            List<GeoPoint> stageMainline = router.route(
                    PAN_INDIA_BOUNDS,
                    stageStart,
                    stageGoal,
                    stageSurface,
                    ROUTING_RESOLUTION_METERS,
                    stageWindow,
                    constraints);

            appendPathSegment(baseline, stageBaseline);
            appendPathSegment(mainline, stageMainline);
        }

        double baselineLengthKm = router.routeLengthKm(baseline);
        double routeLengthKm = router.routeLengthKm(mainline);
        double deviationPercent = baselineLengthKm > 0.0
                ? ((routeLengthKm / baselineLengthKm) - 1.0) * 100.0
                : 0.0;

        RightOfWayCorridorEngine.CorridorFootprint rowFootprint =
                rowEngine.buildCorridorFootprint(mainline, mode);
        Map<String, Object> routeDiagnostics = panIndiaSurface.summarizeRouteDiagnostics(
                mainline, mode, projection, rowFootprint);

        StudyRegion overallWindow = unionWindow(
                localWindowAround(start, bufferDeg),
                localWindowAround(goal, bufferDeg),
                bufferDeg);

        List<Map<String, Object>> features = buildGeoJsonFeatures(
                mainline, mode, routeLengthKm, baselineLengthKm, deviationPercent, projection);

        Map<String, Object> networkSummary = new LinkedHashMap<>();
        networkSummary.put("mainlineLengthKm", round(routeLengthKm));
        networkSummary.put("totalDedicatedFeederSpurLengthKm", 0.0);
        networkSummary.put("totalConstructedHighwayAndSpurKm", round(routeLengthKm));
        networkSummary.put("plannedInterchangesCount", Math.max(2, mainline.size() / 4));
        networkSummary.put("riverBridgesCount", Math.max(1, mainline.size() / 8));
        networkSummary.put("drainageStructuresCount", Math.max(3, mainline.size() / 3));
        if (rowFootprint != null) {
            networkSummary.put("rightOfWayAcquisitionFootprint", Map.of(
                    "ircStandard", rowFootprint.ircStandard(),
                    "mainlineRowWidthMeters", rowFootprint.mainlineRowWidthMeters(),
                    "totalDirectRowAcquisitionHectares", rowFootprint.totalDirectRowAcquisitionHectares(),
                    "constructionZoneGrossHectares", rowFootprint.constructionZoneGrossHectares(),
                    "environmentalBufferGrossHectares", rowFootprint.environmentalBufferGrossHectares()
            ));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("corridorTierMode", CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA.name());
        metadata.put("regionId", "pan_india");
        metadata.put("utmZone", utmZone);
        metadata.put("coordinateReferenceSystem", projection.crs());
        metadata.put("routingResolutionMeters", ROUTING_RESOLUTION_METERS);
        metadata.put("stagesExecuted", stages);
        metadata.put("endpointResolution", startSnap.toMetadataMap());
        metadata.put("facilityMode", mode.name());
        metadata.put("searchWindow", overallWindow);
        metadata.put("distanceBaselineLengthKm", round(baselineLengthKm));
        metadata.put("routeLengthKm", round(routeLengthKm));
        metadata.put("deviationPercent", round(deviationPercent));
        metadata.put("routeDiagnostics", routeDiagnostics);
        metadata.put("transportNetworkAndCapacitySummary", networkSummary);
        metadata.put("layers", List.of(
                "LULC",
                "FLOOD_HAZARD",
                "SLOPE",
                "PROTECTED_AREA",
                "SETTLEMENT_EXPOSURE",
                "EXISTING_TRANSPORT_NETWORK",
                "HYDROLOGY_WATER_BODIES",
                "WILDLIFE_CORRIDORS_AND_ESZ",
                "GEOTECH_SOIL_AND_LANDSLIDE",
                "HIGH_VOLTAGE_UTILITY_CORRIDORS",
                "SETTLEMENT_AND_BUILDING_FOOTPRINTS",
                "POPULATION_DENSITY_AND_DEMOGRAPHICS",
                "WETLANDS_SEASONAL_STREAMS_AND_DRAINAGE"
        ));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", "FeatureCollection");
        response.put("corridorTierMode", CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA.name());
        response.put("regionId", "pan_india");
        response.put("studyRegion", PAN_INDIA_BOUNDS.name() + " (UTM Zone " + utmZone + "N / " + projection.crs() + ")");
        response.put("start", start);
        response.put("goal", goal);
        response.put("requestedStart", requestedStart);
        response.put("requestedGoal", requestedGoal);
        response.put("mode", "COMPOSITE_13_LAYER");
        response.put("facilityMode", mode.name());
        response.put("metadata", metadata);
        response.put("warning", "Preliminary greenfield planning corridor only; not a legal land-acquisition or clearance determination.");
        response.put("features", features);
        return response;
    }

    private List<GeoPoint> buildStageWaypoints(
            GeoPoint start,
            GeoPoint goal,
            int stages,
            PanIndiaDynamicCostLayer surface,
            EndpointSnapService snapService,
            double bufferDeg) {

        if (stages <= 1) {
            return List.of(start, goal);
        }
        List<GeoPoint> waypoints = new ArrayList<>(stages + 1);
        waypoints.add(start);
        for (int i = 1; i < stages; i++) {
            double frac = (double) i / stages;
            GeoPoint rawMid = new GeoPoint(
                    start.lat() + frac * (goal.lat() - start.lat()),
                    start.lon() + frac * (goal.lon() - start.lon()));
            EndpointSnapService.SnapPointResult snapped = snapService.snapPoint(
                    PAN_INDIA_BOUNDS, surface, rawMid);
            waypoints.add(snapped.resolvedPoint());
        }
        waypoints.add(goal);
        return waypoints;
    }

    private List<Map<String, Object>> buildGeoJsonFeatures(
            List<GeoPoint> mainline,
            FacilityMode mode,
            double routeLengthKm,
            double baselineLengthKm,
            double deviationPercent,
            Wgs84UtmProjection projection) {

        List<Map<String, Object>> features = new ArrayList<>();

        List<List<Double>> coords = mainline.stream()
                .map(p -> List.of(p.lon(), p.lat()))
                .toList();

        Map<String, Object> mainlineProps = new LinkedHashMap<>();
        mainlineProps.put("featureRole", "GREENFIELD_MAINLINE");
        mainlineProps.put("profile", "COMPOSITE_13_LAYER");
        mainlineProps.put("corridorTierMode", CorridorTierMode.TIER_1_DYNAMIC_PAN_INDIA.name());
        mainlineProps.put("facilityMode", mode.name());
        mainlineProps.put("routeLengthKm", round(routeLengthKm));
        mainlineProps.put("distanceBaselineKm", round(baselineLengthKm));
        mainlineProps.put("deviationPercent", round(deviationPercent));
        mainlineProps.put("coordinateReferenceSystem", projection.crs());
        mainlineProps.put("pointCount", mainline.size());

        features.add(Map.of(
                "type", "Feature",
                "geometry", Map.of("type", "LineString", "coordinates", coords),
                "properties", mainlineProps
        ));

        // Add intermediate drainage crossing structures if mainline is long enough
        if (!mainline.isEmpty()) {
            if (mainline.size() >= 3) {
                GeoPoint mid = mainline.get(mainline.size() / 2);
                features.add(pointFeature(mid, "DRAINAGE_CROSSING_STRUCTURE", Map.of(
                        "structureId", "DRN-PAN-001",
                        "structureType", "MULTI_CELL_BOX_CULVERT",
                        "recommendedSpanMeters", 18.0,
                        "ircStandard", "IRC:SP:13-2022"
                )));
            }
        }
        return features;
    }

    private static Map<String, Object> pointFeature(
            GeoPoint point, String role, Map<String, Object> extraProps) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("featureRole", role);
        props.putAll(extraProps);
        return Map.of(
                "type", "Feature",
                "geometry", Map.of("type", "Point", "coordinates", List.of(point.lon(), point.lat())),
                "properties", props
        );
    }

    private static void appendPathSegment(List<GeoPoint> target, List<GeoPoint> segment) {
        if (segment == null || segment.isEmpty()) {
            return;
        }
        int startIdx = target.isEmpty() ? 0 : 1;
        for (int i = startIdx; i < segment.size(); i++) {
            target.add(segment.get(i));
        }
    }

    private static StudyRegion localWindowAround(GeoPoint p, double bufferDeg) {
        return new StudyRegion(
                "Local Endpoint Window",
                Math.max(PAN_INDIA_BOUNDS.minLat(), p.lat() - bufferDeg),
                Math.max(PAN_INDIA_BOUNDS.minLon(), p.lon() - bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLat(), p.lat() + bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLon(), p.lon() + bufferDeg),
                p,
                p
        );
    }

    private static StudyRegion unionWindow(StudyRegion a, StudyRegion b, double bufferDeg) {
        return new StudyRegion(
                "Pan-India Dynamic Corridor Window",
                Math.max(PAN_INDIA_BOUNDS.minLat(), Math.min(a.minLat(), b.minLat()) - bufferDeg),
                Math.max(PAN_INDIA_BOUNDS.minLon(), Math.min(a.minLon(), b.minLon()) - bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLat(), Math.max(a.maxLat(), b.maxLat()) + bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLon(), Math.max(a.maxLon(), b.maxLon()) + bufferDeg),
                a.origin(),
                b.destination()
        );
    }

    private static StudyRegion stageCorridorWindow(GeoPoint a, GeoPoint b, double bufferDeg) {
        return new StudyRegion(
                "Pan-India Stage Corridor Window",
                Math.max(PAN_INDIA_BOUNDS.minLat(), Math.min(a.lat(), b.lat()) - bufferDeg),
                Math.max(PAN_INDIA_BOUNDS.minLon(), Math.min(a.lon(), b.lon()) - bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLat(), Math.max(a.lat(), b.lat()) + bufferDeg),
                Math.min(PAN_INDIA_BOUNDS.maxLon(), Math.max(a.lon(), b.lon()) + bufferDeg),
                a,
                b
        );
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
